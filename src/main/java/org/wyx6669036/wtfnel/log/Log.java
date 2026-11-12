package org.wyx6669036.wtfnel.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Unified, timestamped logging to both the console and a file, with support for a single
 * status line pinned to the bottom of the console (used for download progress).
 *
 * <p>Port of WtfNel.Core/Logging/Log.cs. The Spectre markup layer is replaced by plain text plus
 * ANSI colours; every caller passes plain text.</p>
 */
public final class Log {

    private static final String CLEAR_LINE = "\r\u001b[K";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Object GATE = new Object();
    private static final List<Consumer<Entry>> WRITTEN = new CopyOnWriteArrayList<>();
    private static final List<Consumer<String>> STATUS = new CopyOnWriteArrayList<>();

    private static BufferedWriter file;
    private static String status;
    private static boolean verbose;

    /** Disables console output when the host has no console window. */
    private static boolean consoleEnabled = true;

    private Log() {
    }

    /** One log entry handed to {@link #onWritten} listeners. */
    public record Entry(String level, LocalDateTime time, String message) {
    }

    public static boolean isVerbose() {
        return verbose;
    }

    public static void setVerbose(boolean value) {
        verbose = value;
    }

    public static boolean isConsoleEnabled() {
        return consoleEnabled;
    }

    public static void setConsoleEnabled(boolean value) {
        consoleEnabled = value;
    }

    /** Raised for every log entry with its level (DBG/INF/WRN/ERR/RAW), timestamp and message. */
    public static void onWritten(Consumer<Entry> listener) {
        WRITTEN.add(listener);
    }

    public static void removeWrittenListener(Consumer<Entry> listener) {
        WRITTEN.remove(listener);
    }

    /** Raised when the pinned status line changes; {@code null} when cleared. */
    public static void onStatusChanged(Consumer<String> listener) {
        STATUS.add(listener);
    }

    public static void removeStatusListener(Consumer<String> listener) {
        STATUS.remove(listener);
    }

    public static void open(Path path) {
        try {
            Path dir = path.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            synchronized (GATE) {
                closeFile();
                file = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                file.write("---- 会话开始 " + LocalDateTime.now().format(STAMP) + " ----");
                file.newLine();
                file.flush();
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法打开日志文件: " + path, e);
        }
    }

    /** Always written to the file; shown on the console only when {@link #isVerbose()} is set. */
    public static void debug(String msg) {
        write("DBG", msg);
    }

    public static void info(String msg) {
        write("INF", msg);
    }

    public static void warn(String msg) {
        write("WRN", msg);
    }

    public static void error(String msg) {
        write("ERR", msg);
    }

    /** Writes the game's own output verbatim to the file, bypassing the console prefix handling. */
    public static void raw(String line) {
        synchronized (GATE) {
            writeFile(line);
        }
        if (consoleEnabled) {
            System.out.println(line);
        }
        fire("RAW", LocalDateTime.now(), line);
    }

    private static void write(String level, String msg) {
        LocalDateTime now = LocalDateTime.now();
        String line = now.format(TIME) + " [" + level + "] " + msg;
        String color = switch (level) {
            case "WRN" -> "\u001b[33m";
            case "ERR" -> "\u001b[31m";
            case "DBG" -> "\u001b[90m";
            default -> "";
        };
        synchronized (GATE) {
            if (consoleEnabled && (!"DBG".equals(level) || verbose)) {
                if (status != null) {
                    System.out.print(CLEAR_LINE);
                }
                StringBuilder sb = new StringBuilder();
                sb.append("\u001b[90m").append(now.format(SHORT_TIME)).append("\u001b[0m ");
                if (!color.isEmpty()) {
                    sb.append(color);
                }
                sb.append(msg);
                if (!color.isEmpty()) {
                    sb.append("\u001b[0m");
                }
                System.out.println(sb);
                if (status != null) {
                    System.out.print(status);
                }
                System.out.flush();
            }
            writeFile(line);
        }
        fire(level, now, msg);
    }

    /** Sets or refreshes the pinned bottom status line; {@code null} clears it. */
    public static void status(String text) {
        synchronized (GATE) {
            status = text;
            if (consoleEnabled) {
                System.out.print(CLEAR_LINE);
                if (text != null) {
                    System.out.print(text);
                }
                System.out.flush();
            }
        }
        for (Consumer<String> listener : STATUS) {
            listener.accept(text);
        }
    }

    public static void close() {
        synchronized (GATE) {
            closeFile();
        }
    }

    private static void writeFile(String line) {
        if (file == null) {
            return;
        }
        try {
            file.write(line);
            file.newLine();
            file.flush();
        } catch (IOException ignored) {
            // logging must never take the launcher down
        }
    }

    private static void closeFile() {
        if (file == null) {
            return;
        }
        try {
            file.close();
        } catch (IOException ignored) {
            // best effort
        }
        file = null;
    }

    private static void fire(String level, LocalDateTime time, String msg) {
        Entry entry = new Entry(level, time, msg);
        for (Consumer<Entry> listener : WRITTEN) {
            listener.accept(entry);
        }
    }
}
