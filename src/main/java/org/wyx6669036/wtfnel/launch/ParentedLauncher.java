package org.wyx6669036.wtfnel.launch;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.util.AppPaths;
import org.wyx6669036.wtfnel.util.NativeAssets;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在指定父进程下启动子进程。普通 ProcessBuilder 无法指定父进程；
 * 这里用带扩展启动信息的 CreateProcess，使子进程的父 PID 与父镜像在系统看来就是给定的那个进程。
 *
 * <p>移植自 WtfNel.Core/Launch/ParentedLauncher.cs，P/Invoke 换成 JNA。只支持 x64。</p>
 */
public final class ParentedLauncher {

    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private static final int CREATE_NEW_CONSOLE = 0x00000010;
    private static final long PROC_THREAD_ATTRIBUTE_PARENT_PROCESS = 0x00020000L;
    private static final int PROCESS_CREATE_PROCESS = 0x0080;
    private static final int PROCESS_QUERY_INFORMATION = 0x0400;
    private static final int PROCESS_DUP_HANDLE = 0x0040;
    private static final int STARTF_USESTDHANDLES = 0x00000100;
    private static final int HANDLE_FLAG_INHERIT = 0x1;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    private ParentedLauncher() {
    }

    // ---------------------------------------------------------------- JNA bindings

    interface Kernel32 extends StdCallLibrary {
        Pointer OpenProcess(int access, boolean inherit, int pid);

        boolean CloseHandle(Pointer handle);

        boolean InitializeProcThreadAttributeList(Pointer list, int count, int flags, LongByReference size);

        boolean UpdateProcThreadAttribute(Pointer list, int flags, Pointer attribute, Pointer value,
                                          long size, Pointer previous, Pointer returnSize);

        void DeleteProcThreadAttributeList(Pointer list);

        Pointer GetStdHandle(int stdHandle);

        boolean SetHandleInformation(Pointer handle, int mask, int flags);

        boolean CreateProcessW(WString applicationName, WString commandLine, Pointer processAttributes,
                               Pointer threadAttributes, boolean inheritHandles, int creationFlags,
                               Pointer environment, WString currentDirectory,
                               StartupInfoEx startupInfo, ProcessInformation processInformation);

        boolean QueryFullProcessImageNameW(Pointer process, int flags, char[] exeName, IntByReference size);

        boolean GetExitCodeProcess(Pointer process, IntByReference code);

        int WaitForSingleObject(Pointer handle, int milliseconds);
    }

    @com.sun.jna.Structure.FieldOrder({
            "cb", "lpReserved", "lpDesktop", "lpTitle",
            "dwX", "dwY", "dwXSize", "dwYSize", "dwXCountChars", "dwYCountChars", "dwFillAttribute", "dwFlags",
            "wShowWindow", "cbReserved2", "lpReserved2", "hStdInput", "hStdOutput", "hStdError"})
    public static class StartupInfo extends com.sun.jna.Structure {
        public int cb;
        public Pointer lpReserved;
        public Pointer lpDesktop;
        public Pointer lpTitle;
        public int dwX;
        public int dwY;
        public int dwXSize;
        public int dwYSize;
        public int dwXCountChars;
        public int dwYCountChars;
        public int dwFillAttribute;
        public int dwFlags;
        public short wShowWindow;
        public short cbReserved2;
        public Pointer lpReserved2;
        public Pointer hStdInput;
        public Pointer hStdOutput;
        public Pointer hStdError;

        public StartupInfo() {
            super();
        }
    }

    // FieldOrder 里的名字必须与字段名逐字一致；写成 "StartupInfo" 会让 JNA 在算布局时直接抛 Error，
    // 也就是「第一次真正启动客户端就崩」。
    @com.sun.jna.Structure.FieldOrder({"startupInfo", "lpAttributeList"})
    public static class StartupInfoEx extends com.sun.jna.Structure {
        public StartupInfo startupInfo = new StartupInfo();
        public Pointer lpAttributeList;

        public StartupInfoEx() {
            super();
        }
    }

    @com.sun.jna.Structure.FieldOrder({"hProcess", "hThread", "dwProcessId", "dwThreadId"})
    public static class ProcessInformation extends com.sun.jna.Structure {
        public Pointer hProcess;
        public Pointer hThread;
        public int dwProcessId;
        public int dwThreadId;

        public ProcessInformation() {
            super();
        }
    }

    /**
     * 一次成功启动的子进程，外加它那个还没关闭的进程句柄 —— 只有句柄还在手上，
     * 进程退出后才能用 {@code GetExitCodeProcess} 问到真实退出码（ProcessHandle 没有 exitValue）。
     *
     * <p>用完必须 {@link #close()}；即使关掉了，{@link #tryExitCode()} 仍会退回到按 PID 查询。</p>
     */
    public static final class StartedProcess implements AutoCloseable {

        /** WaitForSingleObject 的返回值。 */
        private static final int WAIT_OBJECT_0 = 0;
        /** GetExitCodeProcess 在进程还活着时给出的「退出码」。 */
        private static final int STILL_ACTIVE = 259;

        private final Pointer handle;
        private final int pid;
        private final AtomicBoolean closed = new AtomicBoolean();

        StartedProcess(Pointer handle, int pid) {
            this.handle = handle;
            this.pid = pid;
        }

        public int pid() {
            return pid;
        }

        /** 进程是否还活着；句柄已关且进程已消失时返回 false。 */
        public boolean isAlive() {
            Integer code = tryExitCode();
            return code != null && code == STILL_ACTIVE;
        }

        /** 立刻取退出码，还在跑就返回 259；拿不到（句柄失效、进程已消失）返回 null。 */
        public Integer tryExitCode() {
            Pointer h = handle;
            if (h == null || closed.get()) {
                return tryGetExitCode(pid);
            }
            IntByReference code = new IntByReference();
            if (!k32().GetExitCodeProcess(h, code)) {
                return null;
            }
            return code.getValue();
        }

        /**
         * 阻塞等待进程退出并返回退出码；超时或拿不到返回 null。
         * timeoutMs 小于等于 0 表示一直等下去。
         */
        public Integer waitForExit(long timeoutMs) {
            Pointer h = handle;
            if (h == null || closed.get()) {
                return null;
            }
            int millis = timeoutMs <= 0 ? -1 : (int) Math.min(Integer.MAX_VALUE, timeoutMs);
            if (k32().WaitForSingleObject(h, millis) != WAIT_OBJECT_0) {
                return null;
            }
            return tryExitCode();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true) && handle != null) {
                k32().CloseHandle(handle);
            }
        }

        @Override
        public String toString() {
            return "StartedProcess[pid=" + pid + (closed.get() ? " 句柄已关" : "") + "]";
        }
    }

    private static volatile Kernel32 kernel32;

    private static Kernel32 k32() {
        Kernel32 local = kernel32;
        if (local == null) {
            synchronized (ParentedLauncher.class) {
                local = kernel32;
                if (local == null) {
                    if (!isWindows()) {
                        throw new UnsupportedOperationException("ParentedLauncher 只支持 Windows");
                    }
                    local = Native.load("kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);
                    kernel32 = local;
                }
            }
        }
        return local;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    // ---------------------------------------------------------------- start

    /**
     * 以 parentPid 为父进程启动可执行文件。env 为完整覆盖的环境变量块（null 表示继承当前进程），
     * 返回新进程 PID；失败抛 IllegalStateException。
     */
    public static int start(String exePath, List<String> args, String workingDirectory, int parentPid,
                            Map<String, String> env, boolean newConsole, boolean inheritStdio) {
        return startImpl(exePath, buildCommandLine(exePath, args), workingDirectory, parentPid,
                env, newConsole, inheritStdio);
    }

    /**
     * 原样重放整条命令行，避免拆分再拼接带来的破坏。env 为完整覆盖的环境变量块（null 表示继承）。
     */
    public static int startRaw(String exePath, String commandLine, String workingDirectory, int parentPid,
                               boolean newConsole, boolean inheritStdio, Map<String, String> env) {
        return startImpl(exePath, commandLine, workingDirectory, parentPid, env, newConsole, inheritStdio);
    }

    /**
     * 与 {@link #startRaw} 同一条路径，但把进程句柄留给调用方：只有句柄还在手上，
     * 客户端退出后才能用 {@code GetExitCodeProcess} 问到它的真实退出码。
     * 用完必须 {@link StartedProcess#close()}，否则句柄泄漏。
     */
    public static StartedProcess startRawTracked(String exePath, String commandLine, String workingDirectory,
                                                 int parentPid, boolean newConsole, boolean inheritStdio,
                                                 Map<String, String> env) {
        ProcessInformation info = createProcess(exePath, commandLine, workingDirectory, parentPid,
                env, newConsole, inheritStdio);
        return new StartedProcess(info.hProcess, info.dwProcessId);
    }

    private static int startImpl(String exePath, String commandLine, String workingDirectory, int parentPid,
                                 Map<String, String> env, boolean newConsole, boolean inheritStdio) {
        ProcessInformation info = createProcess(exePath, commandLine, workingDirectory, parentPid,
                env, newConsole, inheritStdio);
        k32().CloseHandle(info.hProcess);
        return info.dwProcessId;
    }

    private static ProcessInformation createProcess(String exePath, String commandLine, String workingDirectory,
                                                    int parentPid, Map<String, String> env, boolean newConsole,
                                                    boolean inheritStdio) {
        // 空串（而不是 null）会被 CreateProcessW 当成非法路径，只回一个 err=123，
        // 看不出问题出在 GameDir 上。这里提前给一条能读懂的。
        if (workingDirectory != null && workingDirectory.isBlank()) {
            throw new IllegalStateException("启动子进程时工作目录为空：请先给 ShadowConfig 设置 GameDir"
                    + "（GameDirLocator.resolve 可以探测已有安装），否则客户端会在错误的目录里跑");
        }
        Kernel32 k32 = k32();
        Pointer parent = k32.OpenProcess(PROCESS_CREATE_PROCESS | PROCESS_QUERY_INFORMATION | PROCESS_DUP_HANDLE,
                false, parentPid);
        if (parent == null) {
            throw new IllegalStateException("打开父进程句柄失败: pid=" + parentPid + " err=" + Native.getLastError());
        }

        Memory attrList = null;
        boolean attrListInit = false;
        Memory envBlock = null;
        try {
            LongByReference size = new LongByReference(0);
            k32.InitializeProcThreadAttributeList(null, 1, 0, size);
            attrList = new Memory(Math.max(size.getValue(), 8));
            if (!k32.InitializeProcThreadAttributeList(attrList, 1, 0, size)) {
                throw new IllegalStateException("初始化属性列表失败: err=" + Native.getLastError());
            }
            attrListInit = true;

            Memory parentValue = new Memory(Native.POINTER_SIZE);
            parentValue.setPointer(0, parent);
            if (!k32.UpdateProcThreadAttribute(attrList, 0, new Pointer(PROC_THREAD_ATTRIBUTE_PARENT_PROCESS),
                    parentValue, Native.POINTER_SIZE, null, null)) {
                throw new IllegalStateException("设置父进程属性失败: err=" + Native.getLastError());
            }

            StartupInfoEx si = new StartupInfoEx();
            si.startupInfo.cb = si.size();
            si.lpAttributeList = attrList;
            si.write();
            if (inheritStdio) {
                si.startupInfo.dwFlags = STARTF_USESTDHANDLES;
                si.startupInfo.hStdInput = makeInheritable(k32.GetStdHandle(-10));
                si.startupInfo.hStdOutput = makeInheritable(k32.GetStdHandle(-11));
                si.startupInfo.hStdError = makeInheritable(k32.GetStdHandle(-12));
                si.write();
            }

            int flags = EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT;
            if (newConsole) {
                flags |= CREATE_NEW_CONSOLE;
            }

            if (env != null) {
                envBlock = buildEnvironmentBlock(env);
            }

            ProcessInformation info = new ProcessInformation();
            boolean ok = k32.CreateProcessW(new WString(exePath), new WString(commandLine), null, null,
                    inheritStdio, flags, envBlock,
                    workingDirectory == null ? null : new WString(workingDirectory), si, info);
            if (!ok) {
                int lastError = Native.getLastError();
                // 123 = ERROR_INVALID_NAME，几乎总是 lpCurrentDirectory / exePath 带非法字符或为空
                String hint = lastError == 123
                        ? "（err=123 = ERROR_INVALID_NAME，通常是工作目录或 exe 路径非法；当前工作目录 = ["
                        + workingDirectory + "]，GameDir 建议先用 GameDirLocator 解析）"
                        : "";
                throw new IllegalStateException("创建子进程失败: " + exePath + " err=" + lastError + hint);
            }
            k32.CloseHandle(info.hThread);
            // hProcess 不在这里关：调用方留着它才能拿到退出码
            return info;
        } finally {
            if (attrListInit) {
                k32.DeleteProcThreadAttributeList(attrList);
            }
            k32.CloseHandle(parent);
        }
    }

    private static Pointer makeInheritable(Pointer handle) {
        if (handle == null || Pointer.nativeValue(handle) == 0 || Pointer.nativeValue(handle) == -1L) {
            return handle;
        }
        k32().SetHandleInformation(handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
        return handle;
    }

    /**
     * 复制当前环境变量并把给定目录插到 PATH 最前面，好让子 JVM 找到只存在于
     * versions/&lt;v&gt;/natives/runtime 的网易运行时 DLL（api-ms-win-crt-utility-l1-1-1.dll 以及
     * libenvsdk.dll / msvcp140.dll 等）。Windows 解析 DLL 自己的依赖只查加载进程目录和 PATH，
     * 不查 DLL 所在目录，所以少了这一步就会报 "Can't find dependent libraries"。
     * 不存在的目录与重复项会被跳过；真正插进去的目录通过返回的 PrependedEnvironment.prepended 给出。
     */
    public static PrependedEnvironment buildChildEnvironment(Collection<Path> prependPaths) {
        Map<String, String> env = currentEnvironment();
        String pathValue = getIgnoreCase(env, "PATH");
        List<String> existing = new ArrayList<>();
        for (String part : (pathValue == null ? "" : pathValue).split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                existing.add(trimmed);
            }
        }
        List<String> seen = new ArrayList<>(existing);
        List<String> prepended = new ArrayList<>();
        for (Path dir : prependPaths) {
            if (dir == null) {
                continue;
            }
            String raw = dir.toString();
            if (raw.isBlank()) {
                continue;
            }
            Path full = dir.toAbsolutePath().normalize();
            if (!Files.isDirectory(full)) {
                continue;
            }
            String fullText = full.toString();
            if (containsIgnoreCase(seen, fullText)) {
                continue;
            }
            seen.add(fullText);
            prepended.add(fullText);
        }

        List<String> merged = new ArrayList<>(prepended);
        merged.addAll(existing);
        putIgnoreCase(env, "PATH", String.join(";", merged));
        return new PrependedEnvironment(env, prepended);
    }

    /** buildChildEnvironment 的返回值：完整环境变量块 + 实际插到 PATH 前的目录。 */
    public record PrependedEnvironment(Map<String, String> environment, List<String> prepended) {
    }

    public static String buildCommandLine(String exe, List<String> args) {
        StringBuilder sb = new StringBuilder();
        sb.append(quote(exe));
        for (String a : args) {
            sb.append(' ').append(quote(a));
        }
        return sb.toString();
    }

    /** MSVCRT 参数转义：反斜杠只在引号前和结尾翻倍。 */
    static String quote(String s) {
        if (!s.isEmpty() && s.indexOf(' ') < 0 && s.indexOf('\t') < 0 && s.indexOf('"') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            int backslashes = 0;
            while (i < s.length() && s.charAt(i) == '\\') {
                backslashes++;
                i++;
            }
            if (i == s.length()) {
                sb.append("\\".repeat(backslashes * 2));
                break;
            }
            if (s.charAt(i) == '"') {
                sb.append("\\".repeat(backslashes * 2 + 1)).append('"');
            } else {
                sb.append("\\".repeat(backslashes)).append(s.charAt(i));
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static Memory buildEnvironmentBlock(Map<String, String> env) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> kv : env.entrySet()) {
            sb.append(kv.getKey()).append('=').append(kv.getValue()).append('\0');
        }
        sb.append('\0');
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_16LE);
        Memory block = new Memory(Math.max(bytes.length, 2));
        block.write(0, bytes, 0, bytes.length);
        return block;
    }

    /** 取子进程退出码，用来诊断它为什么刚起来就消失。 */
    public static Integer tryGetExitCode(int pid) {
        Kernel32 k32 = k32();
        Pointer handle = k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (handle == null) {
            return null;
        }
        try {
            IntByReference code = new IntByReference();
            if (!k32.GetExitCodeProcess(handle, code)) {
                return null;
            }
            return code.getValue();
        } finally {
            k32.CloseHandle(handle);
        }
    }

    /** 把当前进程环境变量复制成大小写不敏感的字典，便于单独覆盖某个键。 */
    public static Map<String, String> currentEnvironment() {
        Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.putAll(System.getenv());
        return map;
    }

    /**
     * 解析父进程：镜像路径等于 launcherPath 的实例正在运行就复用，否则启动 launcherPath。
     * 只按进程名匹配会撞上真正的 WPFLauncher（与替身同名），所以必须比镜像路径。
     * launcherPath 可以相对应用目录。
     */
    public static int ensureLauncher(String launcherPath, String processName, long waitMs) {
        Path base = appBaseDirectory();
        Path full = base.resolve(launcherPath).toAbsolutePath().normalize();
        if (!Files.exists(full)) {
            String name = (processName == null || processName.isBlank()) ? "WPFLauncher" : processName;
            Path sideBySide = base.resolve(name + ".exe");
            if (Files.exists(sideBySide)) {
                full = sideBySide.toAbsolutePath().normalize();
            } else {
                Path fromJar = NativeAssets.installLauncher(base);
                if (fromJar != null) {
                    full = fromJar.toAbsolutePath().normalize();
                } else {
                    throw new IllegalStateException("启动器/替身不存在: " + full);
                }
            }
        }
        String exeName = (processName == null || processName.isBlank())
                ? stripExtension(full.getFileName().toString())
                : processName;

        File target = full.toFile();
        Optional<ProcessHandle> running = findRunning(exeName, target.getAbsolutePath());
        if (running.isPresent()) {
            Log.info("[parent] 复用 " + full + " pid=" + running.get().pid());
            return (int) running.get().pid();
        }

        Log.info("[parent] 启动 " + full);
        Process started;
        try {
            started = new ProcessBuilder(full.toString())
                    .directory(base.toFile())
                    .redirectErrorStream(false)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception e) {
            throw new IllegalStateException("启动父进程失败: " + full + " - " + e.getMessage(), e);
        }

        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            if (started.isAlive()) {
                return (int) started.pid();
            }
            Optional<ProcessHandle> pid = findRunning(exeName, target.getAbsolutePath());
            if (pid.isPresent()) {
                return (int) pid.get().pid();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("等待启动器进程超时: " + full);
    }

    private static Optional<ProcessHandle> findRunning(String exeName, String absolutePath) {
        String expected = exeName.toLowerCase() + ".exe";
        return ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(p -> {
                    Optional<String> cmd = p.info().command();
                    if (cmd.isEmpty()) {
                        return false;
                    }
                    Path fileName = Path.of(cmd.get()).getFileName();
                    return fileName != null && fileName.toString().toLowerCase().equals(expected);
                })
                .filter(p -> {
                    Optional<String> cmd = p.info().command();
                    return cmd.isPresent() && cmd.get().equalsIgnoreCase(absolutePath);
                })
                .findFirst();
    }

    /** 进程完整镜像路径，走 QueryFullProcessImageName；提权或外部进程拿不到 command() 时兜底。 */
    public static String imagePath(int pid) {
        Kernel32 k32 = k32();
        Pointer handle = k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (handle == null) {
            return null;
        }
        try {
            char[] buffer = new char[1024];
            IntByReference size = new IntByReference(buffer.length);
            if (!k32.QueryFullProcessImageNameW(handle, 0, buffer, size)) {
                return null;
            }
            return new String(buffer, 0, size.getValue());
        } finally {
            k32.CloseHandle(handle);
        }
    }

    /** 与 C# 的 AppContext.BaseDirectory 对齐：启动器按程序所在目录解析。 */
    public static Path appBaseDirectory() {
        return AppPaths.baseDirectory();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String getIgnoreCase(Map<String, String> map, String key) {
        for (Map.Entry<String, String> kv : map.entrySet()) {
            if (kv.getKey().equalsIgnoreCase(key)) {
                return kv.getValue();
            }
        }
        return null;
    }

    private static void putIgnoreCase(Map<String, String> map, String key, String value) {
        String existing = null;
        for (String k : map.keySet()) {
            if (k.equalsIgnoreCase(key)) {
                existing = k;
                break;
            }
        }
        map.put(existing == null ? key : existing, value);
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) {
            if (s.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
