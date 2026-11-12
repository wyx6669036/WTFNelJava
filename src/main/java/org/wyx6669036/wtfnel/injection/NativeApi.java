package org.wyx6669036.wtfnel.injection;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.ptr.ShortByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.charset.StandardCharsets;

/**
 * 注入器用到的 Win32 声明。移植自 WtfNel.Core/Injection/NativeApi.cs，P/Invoke 换成 JNA。
 *
 * <p>kernel32/advapi32/user32 都以 UNICODE 类型映射加载：Java 的 String 映射成 LPCWSTR，
 * boolean 映射成 4 字节 BOOL，char[] 映射成 WCHAR 数组。GetProcAddress 要 ANSI 名字，
 * 所以它的参数类型用 byte[]（带结尾 0）。</p>
 */
public final class NativeApi {

    public static final int PAGE_NOACCESS = 0x01;
    public static final int PAGE_READONLY = 0x02;
    public static final int PAGE_READWRITE = 0x04;
    public static final int PAGE_EXECUTE_READ = 0x20;
    public static final int PAGE_EXECUTE_READWRITE = 0x40;
    public static final int PAGE_GUARD = 0x100;

    public static final int MEM_COMMIT = 0x1000;
    public static final int MEM_RESERVE = 0x2000;
    public static final int MEM_RELEASE = 0x8000;

    public static final int PROCESS_CREATE_THREAD = 0x0002;
    public static final int PROCESS_QUERY_INFORMATION = 0x0400;
    public static final int PROCESS_VM_OPERATION = 0x0008;
    public static final int PROCESS_VM_WRITE = 0x0020;
    public static final int PROCESS_VM_READ = 0x0010;

    public static final int TOKEN_ADJUST_PRIVILEGES = 0x0020;
    public static final int TOKEN_QUERY = 0x0008;
    public static final int SE_PRIVILEGE_ENABLED = 0x0002;

    public static final int TH32CS_SNAPPROCESS = 0x00000002;
    public static final int TH32CS_SNAPMODULE = 0x00000008;
    public static final int TH32CS_SNAPMODULE32 = 0x00000010;

    public static final int DLL_PROCESS_ATTACH = 1;
    public static final int DLL_PROCESS_DETACH = 0;

    public static final int IMAGE_FILE_MACHINE_AMD64 = 0x8664;
    public static final int IMAGE_FILE_MACHINE_ARM64 = 0xAA64;
    public static final int IMAGE_FILE_MACHINE_UNKNOWN = 0x0000;

    public static final int LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR = 0x00000100;
    public static final int LOAD_LIBRARY_SEARCH_DEFAULT_DIRS = 0x00001000;

    private NativeApi() {
    }

    // ---------------------------------------------------------------- bindings

    public interface Kernel32 extends StdCallLibrary {
        Pointer OpenProcess(int access, boolean inheritHandle, int processId);

        boolean CloseHandle(Pointer handle);

        Pointer GetCurrentProcess();

        Pointer VirtualAllocEx(Pointer process, Pointer address, long size, int type, int protect);

        boolean VirtualFreeEx(Pointer process, Pointer address, long size, int type);

        boolean VirtualProtectEx(Pointer process, Pointer address, long size, int newProtect,
                                 IntByReference oldProtect);

        boolean ReadProcessMemory(Pointer process, Pointer address, byte[] buffer, long size,
                                  LongByReference read);

        boolean WriteProcessMemory(Pointer process, Pointer address, byte[] buffer, long size,
                                   LongByReference written);

        Pointer CreateRemoteThread(Pointer process, Pointer attributes, long stackSize, Pointer start,
                                   Pointer parameter, int flags, IntByReference threadId);

        int WaitForSingleObject(Pointer handle, int milliseconds);

        boolean GetExitCodeThread(Pointer thread, IntByReference exitCode);

        boolean GetExitCodeProcess(Pointer process, IntByReference exitCode);

        Pointer GetModuleHandleW(String moduleName);

        Pointer GetProcAddress(Pointer module, byte[] procName);

        boolean FlushInstructionCache(Pointer process, Pointer address, long size);

        Pointer LoadLibraryW(String path);

        Pointer LoadLibraryExW(String path, Pointer file, int flags);

        boolean FreeLibrary(Pointer module);

        boolean QueryFullProcessImageNameW(Pointer process, int flags, char[] buffer, IntByReference size);

        Pointer CreateToolhelp32Snapshot(int flags, int processId);

        boolean Process32FirstW(Pointer snapshot, ProcessEntry32W entry);

        boolean Process32NextW(Pointer snapshot, ProcessEntry32W entry);

        boolean Module32FirstW(Pointer snapshot, ModuleEntry32W entry);

        boolean Module32NextW(Pointer snapshot, ModuleEntry32W entry);

        boolean IsWow64Process2(Pointer process, ShortByReference processMachine, ShortByReference nativeMachine);

        boolean IsWow64Process(Pointer process, IntByReference wow64);

        boolean OpenProcessToken(Pointer process, int access, PointerByReference token);
    }

    public interface Advapi32 extends StdCallLibrary {
        /** advapi32 也导出 OpenProcessToken；这里再声明一次，调用处就不用混用两个接口。 */
        boolean OpenProcessToken(Pointer process, int access, PointerByReference token);

        boolean LookupPrivilegeValueW(String system, String name, LongByReference luid);

        boolean AdjustTokenPrivileges(Pointer token, boolean disableAll, TokenPrivileges newState,
                                      int bufferLength, Pointer previous, Pointer returnLength);
    }

    public interface EnumWindowsProc extends StdCallLibrary.StdCallCallback {
        boolean callback(Pointer window, Pointer param);
    }

    public interface User32 extends StdCallLibrary {
        boolean EnumWindows(EnumWindowsProc callback, Pointer param);

        int GetWindowTextW(Pointer window, char[] text, int max);

        int GetWindowTextLengthW(Pointer window);

        boolean IsWindowVisible(Pointer window);

        int GetWindowThreadProcessId(Pointer window, IntByReference processId);
    }

    @Structure.FieldOrder({"dwSize", "cntUsage", "th32ProcessID", "th32DefaultHeapID", "th32ModuleID",
            "cntThreads", "th32ParentProcessID", "pcPriClassBase", "dwFlags", "szExeFile"})
    public static class ProcessEntry32W extends Structure {
        public int dwSize;
        public int cntUsage;
        public int th32ProcessID;
        public Pointer th32DefaultHeapID;
        public int th32ModuleID;
        public int cntThreads;
        public int th32ParentProcessID;
        public int pcPriClassBase;
        public int dwFlags;
        public char[] szExeFile = new char[260];

        public ProcessEntry32W() {
            super();
            dwSize = size();
        }

        public String exeFile() {
            return new String(szExeFile).split(String.valueOf((char) 0), 2)[0];
        }
    }

    @Structure.FieldOrder({"dwSize", "th32ModuleID", "th32ProcessID", "GlblcntUsage", "ProccntUsage",
            "modBaseAddr", "modBaseSize", "hModule", "szModule", "szExePath"})
    public static class ModuleEntry32W extends Structure {
        public int dwSize;
        public int th32ModuleID;
        public int th32ProcessID;
        public int GlblcntUsage;
        public int ProccntUsage;
        public Pointer modBaseAddr;
        public int modBaseSize;
        public Pointer hModule;
        public char[] szModule = new char[256];
        public char[] szExePath = new char[260];

        public ModuleEntry32W() {
            super();
            dwSize = size();
        }

        public String moduleName() {
            return new String(szModule).split(String.valueOf((char) 0), 2)[0];
        }

        public String modulePath() {
            return new String(szExePath).split(String.valueOf((char) 0), 2)[0];
        }
    }

    @Structure.FieldOrder({"privilegeCount", "luid", "attributes"})
    public static class TokenPrivileges extends Structure {
        public int privilegeCount;
        public Luid luid = new Luid();
        public int attributes;

        public TokenPrivileges() {
            super();
        }
    }

    @Structure.FieldOrder({"low", "high"})
    public static class Luid extends Structure {
        public int low;
        public int high;

        public Luid() {
            super();
        }
    }

    // ---------------------------------------------------------------- lazy libraries

    private static volatile Kernel32 kernel32;
    private static volatile Advapi32 advapi32;
    private static volatile User32 user32;

    public static Kernel32 kernel32() {
        Kernel32 local = kernel32;
        if (local == null) {
            synchronized (NativeApi.class) {
                local = kernel32;
                if (local == null) {
                    requireWindows();
                    local = Native.load("kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);
                    kernel32 = local;
                }
            }
        }
        return local;
    }

    public static Advapi32 advapi32() {
        Advapi32 local = advapi32;
        if (local == null) {
            synchronized (NativeApi.class) {
                local = advapi32;
                if (local == null) {
                    requireWindows();
                    local = Native.load("advapi32", Advapi32.class, W32APIOptions.DEFAULT_OPTIONS);
                    advapi32 = local;
                }
            }
        }
        return local;
    }

    public static User32 user32() {
        User32 local = user32;
        if (local == null) {
            synchronized (NativeApi.class) {
                local = user32;
                if (local == null) {
                    requireWindows();
                    local = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);
                    user32 = local;
                }
            }
        }
        return local;
    }

    private static void requireWindows() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            throw new UnsupportedOperationException("注入器只支持 Windows");
        }
    }

    /** 以 0 结尾的 ASCII 字节，喂给只接受 ANSI 的导出（GetProcAddress 等）。 */
    public static byte[] ansi(String text) {
        byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        byte[] padded = new byte[raw.length + 1];
        System.arraycopy(raw, 0, padded, 0, raw.length);
        return padded;
    }

    /**
     * 按序号取导出地址（C 里是 GetProcAddress(h, MAKEINTRESOURCEA(ordinal))）。
     * 这个名字的函数没法用 byte[] 参数声明，所以直接拿到 GetProcAddress 的 Function 手投。
     */
    public static Pointer getProcAddressOrdinal(Pointer module, int ordinal) {
        requireWindows();
        com.sun.jna.NativeLibrary library = com.sun.jna.NativeLibrary.getInstance("kernel32");
        com.sun.jna.Function function = library.getFunction("GetProcAddress");
        return function.invokePointer(new Object[]{module, Pointer.createConstant(ordinal)});
    }

    public static long lastError() {
        return Native.getLastError();
    }

    public static boolean isNull(Pointer p) {
        return p == null || Pointer.nativeValue(p) == 0;
    }

    /** -1 是 INVALID_HANDLE_VALUE，JNA 会把它读成全 F 的指针。 */
    public static boolean isInvalidHandle(Pointer p) {
        return isNull(p) || Pointer.nativeValue(p) == -1L;
    }
}
