package org.wyx6669036.wtfnel.install;

import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.log.ProgressLine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 移植自 FandNEL 的 FastHttpDownloader：服务器支持 Range 时用 8 个并发分段下载（每段重试 3 次），
 * 否则顺序下载。先写临时 .part 文件，md5 校验通过后才替换目标文件。
 *
 * <p>移植自 WtfNel.Core/Install/FastDownloader.cs。C# 用 MemoryMappedFile 做分段落地，
 * 这里用 RandomAccessFile + FileChannel 定位写入（语义等价，且不必预先映射大文件）。</p>
 */
public final class FastDownloader {

    private static final int BUFFER_SIZE = 128 * 1024;
    private static final int MAX_CONCURRENT_SEGMENTS = 8;
    private static final int SEGMENT_RETRY_COUNT = 3;
    private static final long MINIMUM_SEGMENTED_SIZE = 1L << 20;
    private static final long TARGET_SEGMENT_SIZE = 4L << 20;

    private FastDownloader() {
    }

    /** 下载到 destination；若文件已存在且 md5 匹配则跳过。 */
    public static void download(HttpClient http, String url, Path destination, String md5) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("下载地址无效: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IOException("下载地址必须是 HTTP/HTTPS: " + url);
        }
        Path absolute = destination.toAbsolutePath();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        if (Files.exists(absolute) && md5 != null && !md5.isEmpty() && md5Matches(absolute, md5)) {
            return;
        }

        String name = absolute.getFileName().toString();
        Path temp = absolute.resolveSibling(name + "." + UUID.randomUUID().toString().replace("-", "") + ".part");

        try (ProgressLine bar = new ProgressLine(name)) {
            Log.debug("[dl] 下载 " + name + " <- " + url);
            long[] meta = tryGetMetadata(http, uri);
            boolean done = false;
            if (meta != null && meta[1] == 1 && meta[0] >= MINIMUM_SEGMENTED_SIZE) {
                try {
                    downloadInSegments(http, uri, temp, meta[0], bar);
                    done = true;
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    Log.warn("[dl] 分段下载失败，改为顺序下载: " + rootMessage(e));
                    tryDelete(temp);
                }
            }
            if (!done) {
                downloadSingle(http, uri, temp, bar);
            }

            if (md5 != null && !md5.isEmpty() && !md5Matches(temp, md5)) {
                throw new IOException("MD5 校验失败: " + name + "（期望 " + md5 + "）");
            }
            Files.move(temp, absolute, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            bar.done();
        } finally {
            tryDelete(temp);
        }
    }

    public static boolean md5Matches(Path path, String md5) {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(md5);
        } catch (Exception e) {
            return false;
        }
    }

    /** 返回 {长度, 是否支持 Range(1/0)}；拿不到返回 null。 */
    private static long[] tryGetMetadata(HttpClient http, URI uri) {
        try {
            HttpRequest req = HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 != 2) {
                return null;
            }
            long length = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (length < 0) {
                return null;
            }
            boolean ranges = resp.headers().firstValue("Accept-Ranges")
                    .map(v -> v.toLowerCase().contains("bytes"))
                    .orElse(false);
            return new long[]{length, ranges ? 1 : 0};
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void downloadInSegments(HttpClient http, URI uri, Path destination, long total, ProgressLine bar)
            throws Exception {
        int count = (int) Math.min(MAX_CONCURRENT_SEGMENTS * 3,
                Math.max(2, (long) Math.ceil(total / (double) TARGET_SEGMENT_SIZE)));

        try (FileChannel channel = FileChannel.open(destination,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.truncate(total);
        }

        Semaphore gate = new Semaphore(MAX_CONCURRENT_SEGMENTS);
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();
        AtomicLong transferred = new AtomicLong();
        AtomicBoolean cancelled = new AtomicBoolean();

        long seg = total / count;
        List<Thread> threads = new ArrayList<>();
        List<Throwable> thrown = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < count; i++) {
            long start = i * seg;
            long end = i == count - 1 ? total - 1 : (i + 1) * seg - 1;
            Thread t = new Thread(() -> {
                try {
                    rangeDownload(http, uri, destination, start, end, gate, errors, transferred, cancelled, bar);
                } catch (Throwable e) {
                    thrown.add(e);
                }
            }, "wtfnel-dl-" + i);
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) {
            t.join();
        }
        if (!thrown.isEmpty()) {
            Throwable first = thrown.get(0);
            if (first instanceof Exception ex) {
                throw ex;
            }
            throw new IOException(first);
        }
        if (!errors.isEmpty()) {
            throw new IOException("分段下载失败: " + rootMessage(errors.peek()), errors.peek());
        }
        bar.update(transferred.get(), total);
    }

    private static void rangeDownload(HttpClient http, URI uri, Path destination, long start, long end,
                                      Semaphore gate, ConcurrentLinkedQueue<Exception> errors,
                                      AtomicLong transferred, AtomicBoolean cancelled, ProgressLine bar)
            throws InterruptedException {
        gate.acquire();
        try {
            for (int attempt = 1; attempt <= SEGMENT_RETRY_COUNT; attempt++) {
                long got = 0;
                try {
                    HttpRequest req = HttpRequest.newBuilder(uri)
                            .header("Range", "bytes=" + start + "-" + end)
                            .GET()
                            .build();
                    HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() != 206) {
                        throw new IOException("服务器未返回分段响应 (" + resp.statusCode() + ")");
                    }
                    String contentRange = resp.headers().firstValue("Content-Range").orElse(null);
                    if (contentRange != null) {
                        String spec = contentRange.substring(contentRange.indexOf(' ') + 1);
                        String range = spec.split("/")[0];
                        String[] pair = range.split("-");
                        long from = Long.parseLong(pair[0].trim());
                        long to = Long.parseLong(pair[1].trim());
                        if (from != start || to != end) {
                            throw new IOException("服务器返回了错误的分段范围: " + contentRange);
                        }
                    }

                    try (InputStream in = resp.body();
                         FileChannel channel = FileChannel.open(destination,
                                 StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                        byte[] buf = new byte[BUFFER_SIZE];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                            long pos = start + got;
                            while (bb.hasRemaining()) {
                                pos += channel.write(bb, pos);
                            }
                            got += n;
                            bar.update(transferred.addAndGet(n), null);
                        }
                    }
                    long expected = end - start + 1;
                    if (got != expected) {
                        throw new IOException("分段下载不完整: " + got + "/" + expected);
                    }
                    return;
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    transferred.addAndGet(-got);
                    if (attempt == SEGMENT_RETRY_COUNT) {
                        errors.add(e);
                        return;
                    }
                    Thread.sleep(250L * attempt);
                }
            }
        } finally {
            gate.release();
        }
    }

    private static void downloadSingle(HttpClient http, URI uri, Path destination, ProgressLine bar) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(uri).GET().build();
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("下载失败 HTTP " + resp.statusCode() + ": " + uri);
        }
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        long done = 0;
        try (InputStream in = resp.body();
             FileChannel out = FileChannel.open(destination,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) {
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                while (bb.hasRemaining()) {
                    out.write(bb);
                }
                done += n;
                bar.update(done, total > 0 ? total : null);
            }
        }
        if (total > 0 && done != total) {
            throw new IOException("下载不完整: " + done + "/" + total);
        }
    }

    private static void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 尽力清理
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
