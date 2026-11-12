package org.wyx6669036.wtfnel.injection;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 按窗口标题或进程名找工作进程。移植自 WtfNel.Core/Injection/ProcessTarget.cs。
 *
 * <p>CreationTime 用 DateTime.Ticks 口径（0001-01-01 起的 100ns 数），只用来排序取最新。</p>
 */
final class ProcessTarget {

    /** 一个候选目标进程。 */
    record ProcessCandidate(int processId, String title, String executable, long creationTime) {
    }

    /** .NET DateTime 起点到 Unix 纪元的 ticks 差，用来把 Instant 换算成同样的刻度。 */
    private static final long EPOCH_TICKS = 621355968000000000L;

    private ProcessTarget() {
    }

    static List<ProcessCandidate> findByWindow(Collection<String> keywords, boolean requireJava) {
        List<String> wanted = keywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .toList();
        if (wanted.isEmpty()) {
            return List.of();
        }

        List<ProcessCandidate> found = new ArrayList<>();
        NativeApi.EnumWindowsProc callback = (window, param) -> {
            try {
                collectWindow(window, wanted, requireJava, found);
            } catch (RuntimeException ignored) {
                // 单个窗口出错不能中断整轮枚举
            }
            return true;
        };

        NativeApi.user32().EnumWindows(callback, Pointer.NULL);
        return found;
    }

    private static void collectWindow(Pointer window, List<String> wanted, boolean requireJava,
                                      List<ProcessCandidate> found) {
        NativeApi.User32 user32 = NativeApi.user32();
        if (!user32.IsWindowVisible(window)) {
            return;
        }

        int length = user32.GetWindowTextLengthW(window);
        if (length <= 0) {
            return;
        }

        char[] buffer = new char[length + 2];
        if (user32.GetWindowTextW(window, buffer, buffer.length) <= 0) {
            return;
        }

        String title = text(buffer);
        boolean matched = false;
        for (String keyword : wanted) {
            if (title.toLowerCase().contains(keyword.toLowerCase())) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            return;
        }

        IntByReference processId = new IntByReference();
        user32.GetWindowThreadProcessId(window, processId);
        int pid = processId.getValue();
        if (pid == 0) {
            return;
        }

        String exe = processImageName(pid);
        if (requireJava && !isJava(exe)) {
            return;
        }

        found.add(new ProcessCandidate(pid, title, exe, creationTime(pid)));
    }

    static List<ProcessCandidate> findByProcessName(Collection<String> names) {
        List<String> wanted = names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> n.toLowerCase().endsWith(".exe") ? n : n + ".exe")
                .toList();
        if (wanted.isEmpty()) {
            return List.of();
        }

        List<ProcessCandidate> found = new ArrayList<>();
        Pointer snapshot = NativeApi.kernel32().CreateToolhelp32Snapshot(NativeApi.TH32CS_SNAPPROCESS, 0);
        if (NativeApi.isInvalidHandle(snapshot)) {
            return found;
        }

        try {
            NativeApi.ProcessEntry32W entry = new NativeApi.ProcessEntry32W();
            if (!NativeApi.kernel32().Process32FirstW(snapshot, entry)) {
                return found;
            }

            long self = ProcessHandle.current().pid();
            do {
                String exeFile = entry.exeFile();
                boolean matched = false;
                for (String name : wanted) {
                    if (name.equalsIgnoreCase(exeFile)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    continue;
                }

                int pid = entry.th32ProcessID;
                if (pid == (int) self) {
                    continue;
                }
                found.add(new ProcessCandidate(pid, exeFile, processImageName(pid), creationTime(pid)));
            } while (NativeApi.kernel32().Process32NextW(snapshot, entry));
        } finally {
            NativeApi.kernel32().CloseHandle(snapshot);
        }
        return found;
    }

    /** 取启动时间最新的那个；全都没有启动时间时行为与 C# 的 FirstOrDefault 一致。 */
    static ProcessCandidate newest(Collection<ProcessCandidate> candidates) {
        return candidates.stream()
                .max(Comparator.comparingLong(ProcessCandidate::creationTime))
                .orElse(null);
    }

    static boolean isJava(String executable) {
        if (executable == null || executable.isEmpty()) {
            return false;
        }
        String name;
        try {
            name = Path.of(executable).getFileName().toString();
        } catch (RuntimeException e) {
            return false;
        }
        return name.equalsIgnoreCase("java.exe") || name.equalsIgnoreCase("javaw.exe");
    }

    static long creationTime(int pid) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            return 0;
        }
        Optional<java.time.Instant> start = handle.get().info().startInstant();
        if (start.isEmpty()) {
            return 0;
        }
        java.time.Instant instant = start.get();
        return instant.getEpochSecond() * 10_000_000L + instant.getNano() / 100L + EPOCH_TICKS;
    }

    static String processImageName(int pid) {
        try {
            return ProcessHandle.of(pid)
                    .flatMap(h -> h.info().command())
                    .orElse("pid " + pid);
        } catch (RuntimeException e) {
            return "pid " + pid;
        }
    }

    /** 目标进程位宽，走 RemoteProcess 的探测逻辑；打不开进程时返回 0。 */
    static int arch(int pid) {
        String[] error = new String[1];
        RemoteProcess remote = RemoteProcess.open(pid, error);
        if (remote == null) {
            return 0;
        }
        try (remote) {
            return remote.targetArch();
        }
    }

    private static String text(char[] buffer) {
        int end = 0;
        while (end < buffer.length && buffer[end] != 0) {
            end++;
        }
        return new String(buffer, 0, end);
    }
}
