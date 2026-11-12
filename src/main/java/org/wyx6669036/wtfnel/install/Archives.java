package org.wyx6669036.wtfnel.install;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.wyx6669036.wtfnel.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 压缩包解压：7z / zip 按魔数自动识别，顺序读取（solid 7z 只解一遍），并拒绝越界条目。
 *
 * <p>对应 C# 里 {@code ArchiveFactory.Open(...).ExtractAllEntries()} 的那段代码。
 * 网易的补丁包实际都是 7z，但保留 zip 分支以防 CDN 换格式。</p>
 */
public final class Archives {

    /** 7z 文件头魔数：37 7A BC AF 27 1C */
    private static final byte[] SEVEN_ZIP_MAGIC = {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};

    private static final int BUFFER_SIZE = 128 * 1024;

    private Archives() {
    }

    /** 解压到 destination；条目路径必须落在 destination 内，否则抛 {@link InvalidArchiveEntryException}。 */
    public static void extract(Path archivePath, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);
        String rootPrefix = root.toString().endsWith(root.getFileSystem().getSeparator())
                ? root.toString()
                : root + root.getFileSystem().getSeparator();
        Log.info("[install] 解压 " + archivePath.getFileName() + " -> " + rootPrefix);

        int count = isSevenZip(archivePath) ? extractSevenZip(archivePath, root, rootPrefix)
                : extractZip(archivePath, root, rootPrefix);
        Log.info("[install] 解压完成 " + count + " 个文件");
    }

    private static boolean isSevenZip(Path archive) throws IOException {
        byte[] head = new byte[SEVEN_ZIP_MAGIC.length];
        try (InputStream in = Files.newInputStream(archive)) {
            int n = in.readNBytes(head, 0, head.length);
            if (n < head.length) {
                return false;
            }
        }
        for (int i = 0; i < head.length; i++) {
            if (head[i] != SEVEN_ZIP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static int extractSevenZip(Path archive, Path root, String rootPrefix) throws IOException {
        int count = 0;
        try (SevenZFile sevenZip = SevenZFile.builder().setFile(archive.toFile()).get()) {
            SevenZArchiveEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = sevenZip.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName() == null || entry.getName().isEmpty()) {
                    continue;
                }
                Path target = resolveTarget(root, rootPrefix, entry.getName());
                Files.createDirectories(target.getParent());
                deleteIfExists(target);
                try (OutputStream out = Files.newOutputStream(target,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    int n;
                    while ((n = sevenZip.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                count++;
            }
        }
        return count;
    }

    private static int extractZip(Path archive, Path root, String rootPrefix) throws IOException {
        int count = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[BUFFER_SIZE];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName() == null || entry.getName().isEmpty()) {
                    continue;
                }
                Path target = resolveTarget(root, rootPrefix, entry.getName());
                Files.createDirectories(target.getParent());
                deleteIfExists(target);
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(target,
                             StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                count++;
            }
        }
        return count;
    }

    /** 把 entry 名转成绝对路径，并确认它没有跳出 root。 */
    private static Path resolveTarget(Path root, String rootPrefix, String key) {
        String relative = key.replace('\\', '/');
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        Path target = root.resolve(relative).toAbsolutePath().normalize();
        if (!target.toString().regionMatches(true, 0, rootPrefix, 0, rootPrefix.length())) {
            throw new InvalidArchiveEntryException("压缩包条目越界: " + key);
        }
        return target;
    }

    private static void deleteIfExists(Path path) throws IOException {
        if (Files.exists(path)) {
            // 只读属性在 Java 里不影响覆盖，但 CLEAR 一次更贴近 C# 的 SetAttributes(Normal)
            Files.deleteIfExists(path);
        }
    }

    /** 条目落在目标目录之外（zip slip）；RuntimeException 便于在流式解压里直接抛出。 */
    public static final class InvalidArchiveEntryException extends RuntimeException {
        public InvalidArchiveEntryException(String message) {
            super(message);
        }
    }
}
