package org.wyx6669036.wtfnel.log;

/**
 * 单行下载进度：<code>\ [██████··········]  45%  文件名  12/30 MB</code>。
 * 开头的字符每 100ms 转一格（| / - \\），与字节进度无关，即使传输卡住也能看出程序还活着。
 *
 * <p>移植自 WtfNel.Core/Install/ProgressLine.cs（原文件与 FastDownloader 同文件）。</p>
 */
public final class ProgressLine implements AutoCloseable {

    private static final int BAR_WIDTH = 30;
    private static final String[] SPIN = {"|", "/", "-", "\\"};

    private final String name;
    private final Object gate = new Object();
    private final java.util.Timer timer;
    private volatile long done;
    private volatile long total;
    private int frame;
    private boolean stopped;

    public ProgressLine(String fileName) {
        this.name = fileName.length() > 32 ? fileName.substring(0, 31) + "…" : fileName;
        this.timer = new java.util.Timer("wtfnel-progress", true);
        this.timer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                render();
            }
        }, 0, 100);
    }

    public void update(long doneBytes, Long totalBytes) {
        this.done = doneBytes;
        if (totalBytes != null && totalBytes > 0) {
            this.total = totalBytes;
        }
    }

    private void render() {
        synchronized (gate) {
            if (stopped) {
                return;
            }
            frame++;
            String spin = SPIN[frame % SPIN.length];
            Log.status(line(spin, done, total));
        }
    }

    private String line(String spin, long doneBytes, long totalBytes) {
        int pct = totalBytes > 0 ? (int) Math.min(100, doneBytes * 100 / totalBytes) : 0;
        int fill = pct * BAR_WIDTH / 100;
        String size = totalBytes > 0
                ? (doneBytes >> 20) + "/" + (totalBytes >> 20) + " MB"
                : (doneBytes >> 20) + " MB";
        return spin + " [" + "#".repeat(fill) + "-".repeat(BAR_WIDTH - fill) + "] "
                + String.format("%3d", pct) + "%  " + name + "  " + size;
    }

    /** 下载结束：停止转动，留一行 100% 结果。 */
    public void done() {
        if (!stop()) {
            return;
        }
        long totalBytes = total;
        Log.info("✓ " + name + "  " + ((totalBytes > 0 ? totalBytes : done) >> 20) + " MB");
    }

    @Override
    public void close() {
        stop();
    }

    private boolean stop() {
        synchronized (gate) {
            if (stopped) {
                return false;
            }
            stopped = true;
            timer.cancel();
        }
        Log.status(null);
        return true;
    }
}
