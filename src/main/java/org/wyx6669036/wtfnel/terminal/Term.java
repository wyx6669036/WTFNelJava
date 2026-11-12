package org.wyx6669036.wtfnel.terminal;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * 终端能力探测。移植自 WtfNel.Core/Terminal/Term.cs。
 *
 * <p>中文系统的老 conhost 会把 ▀ █ · 这类"宽度不明确"的字形按两格渲染。启动时写一个 ▀、
 * 量一下光标前进了几格、再擦掉，就能知道当前控制台属于哪种。{@link #isAmbiguousWide()} 之后
 * 可以拿来决定进度条画 30 格还是 15 格。</p>
 *
 * <p>与原版一致：这个探测默认不会自动跑（C# 里 {@code Detect()} 也从未被调用），需要时由
 * 调用方显式调用 {@link #detect()}。输出被重定向到文件/管道时直接跳过，避免往日志里写控制字符。</p>
 */
public final class Term {

    /** Windows 的 STD_OUTPUT_HANDLE。 */
    private static final int STD_OUTPUT_HANDLE = -11;

    private static volatile boolean ambiguousWide;

    private Term() {
    }

    /** 当前控制台是否把宽度不明确的字形渲染成两格。 */
    public static boolean isAmbiguousWide() {
        return ambiguousWide;
    }

    /**
     * 探测一次。任何异常（没有控制台、句柄拿不到、JNA 不在）都当成"不是双宽"，
     * 并且绝不影响调用方继续做事。
     */
    public static void detect() {
        ambiguousWide = false;
        if (isOutputRedirected()) {
            return;
        }
        try {
            Kernel32Console k32 = console();
            Pointer handle = k32.GetStdHandle(STD_OUTPUT_HANDLE);
            if (handle == null || Pointer.nativeValue(handle) == 0L || Pointer.nativeValue(handle) == -1L) {
                return;
            }

            ConsoleScreenBufferInfo before = new ConsoleScreenBufferInfo();
            if (!k32.GetConsoleScreenBufferInfo(handle, before)) {
                return;
            }
            int left = before.dwCursorPosition.X;
            int top = before.dwCursorPosition.Y;

            System.out.print("\u2580");
            System.out.flush();

            ConsoleScreenBufferInfo after = new ConsoleScreenBufferInfo();
            boolean measured = k32.GetConsoleScreenBufferInfo(handle, after);
            if (measured) {
                ambiguousWide = decide(after.dwCursorPosition.X - left);
            }

            // 还原：光标回原位，写两个空格盖掉那个 ▀，再回原位
            Coord home = new Coord(left, top);
            k32.SetConsoleCursorPosition(handle, home);
            System.out.print("  ");
            System.out.flush();
            k32.SetConsoleCursorPosition(handle, home);
        } catch (Throwable ignored) {
            // 探测失败不该影响启动，和 C# 的 catch { AmbiguousWide = false; } 等价
            ambiguousWide = false;
        }
    }

    /** 光标前进了几格才算"双宽"。抽出来是为了能单测，不用真控制台。 */
    static boolean decide(int cellsAdvanced) {
        return cellsAdvanced >= 2;
    }

    private static boolean isOutputRedirected() {
        return System.console() == null;
    }

    private static volatile Kernel32Console console;

    private static Kernel32Console console() {
        Kernel32Console local = console;
        if (local == null) {
            synchronized (Term.class) {
                local = console;
                if (local == null) {
                    if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
                        throw new UnsupportedOperationException("终端探测只支持 Windows");
                    }
                    local = Native.load("kernel32", Kernel32Console.class, W32APIOptions.DEFAULT_OPTIONS);
                    console = local;
                }
            }
        }
        return local;
    }

    /** 只声明探测用到的三个函数，不把整个 kernel32 拖进来。 */
    interface Kernel32Console extends StdCallLibrary {
        Pointer GetStdHandle(int which);

        boolean GetConsoleScreenBufferInfo(Pointer handle, ConsoleScreenBufferInfo info);

        boolean SetConsoleCursorPosition(Pointer handle, Coord position);
    }

    @Structure.FieldOrder({"X", "Y"})
    public static class Coord extends Structure {
        public short X;
        public short Y;

        public Coord() {
            super();
        }

        public Coord(int x, int y) {
            super();
            this.X = (short) x;
            this.Y = (short) y;
        }
    }

    @Structure.FieldOrder({"dwSize", "dwCursorPosition", "wAttributes", "srWindow", "dwMaximumWindowSize"})
    public static class ConsoleScreenBufferInfo extends Structure {
        public Coord dwSize = new Coord();
        public Coord dwCursorPosition = new Coord();
        public short wAttributes;
        public SmallRect srWindow = new SmallRect();
        public Coord dwMaximumWindowSize = new Coord();

        public ConsoleScreenBufferInfo() {
            super();
        }
    }

    @Structure.FieldOrder({"Left", "Top", "Right", "Bottom"})
    public static class SmallRect extends Structure {
        public short Left;
        public short Top;
        public short Right;
        public short Bottom;

        public SmallRect() {
            super();
        }
    }
}
