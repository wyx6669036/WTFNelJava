package org.wyx6669036.wtfnel.injection;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.ptr.ShortByReference;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * 目标进程句柄 + 一整套远程内存/线程操作。移植自 WtfNel.Core/Injection/RemoteProcess.cs。
 *
 * <p>tryInvoke 是整条注入链的底座：往目标进程里铺一小段 x64 机器码，CreateRemoteThread 让它
 * 调任意函数，返回值写回同一块内存再读出来。因此一次调用最多传 4 个 64 位参数（rcx/rdx/r8/r9）。</p>
 */
public final class RemoteProcess implements AutoCloseable {

    private static final int INVOKE_BLOCK_SIZE = 0x200;
    private static final int INVOKE_SLOT_OFFSET = 0;
    private static final int INVOKE_CODE_OFFSET = 0x100;

    private final Pointer handle;
    private final int processId;

    private RemoteProcess(Pointer handle, int processId) {
        this.handle = handle;
        this.processId = processId;
    }

    public int processId() {
        return processId;
    }

    public Pointer handle() {
        return handle;
    }

    /**
     * 打开目标进程。成功返回实例，失败返回 null 并写入 error。
     * 打开前先提权 SeDebugPrivilege，否则跨用户/高完整性进程会被拒。
     */
    public static RemoteProcess open(int pid, String[] error) {
        enableDebugPrivilege();

        int access = NativeApi.PROCESS_CREATE_THREAD
                | NativeApi.PROCESS_QUERY_INFORMATION
                | NativeApi.PROCESS_VM_OPERATION
                | NativeApi.PROCESS_VM_WRITE
                | NativeApi.PROCESS_VM_READ;

        Pointer handle = NativeApi.kernel32().OpenProcess(access, false, pid);
        if (NativeApi.isNull(handle)) {
            error[0] = "OpenProcess(" + pid + ") 失败，Win32 错误 " + NativeApi.lastError();
            return null;
        }
        return new RemoteProcess(handle, pid);
    }

    /** 尽力提权；失败只当没有额外权限，不报错。 */
    private static void enableDebugPrivilege() {
        try {
            PointerByReference token = new PointerByReference();
            if (!NativeApi.advapi32().OpenProcessToken(NativeApi.kernel32().GetCurrentProcess(),
                    NativeApi.TOKEN_ADJUST_PRIVILEGES | NativeApi.TOKEN_QUERY, token)) {
                return;
            }

            Pointer handle = token.getValue();
            try {
                LongByReference luid = new LongByReference();
                if (!NativeApi.advapi32().LookupPrivilegeValueW(null, "SeDebugPrivilege", luid)) {
                    return;
                }

                long value = luid.getValue();
                NativeApi.TokenPrivileges privileges = new NativeApi.TokenPrivileges();
                privileges.privilegeCount = 1;
                privileges.luid.low = (int) (value & 0xFFFF_FFFFL);
                privileges.luid.high = (int) ((value >>> 32) & 0xFFFF_FFFFL);
                privileges.attributes = NativeApi.SE_PRIVILEGE_ENABLED;
                privileges.write();

                NativeApi.advapi32().AdjustTokenPrivileges(handle, false, privileges, 0, null, null);
            } finally {
                NativeApi.kernel32().CloseHandle(handle);
            }
        } catch (RuntimeException ignored) {
            // 提权失败不影响后续流程，真正的失败会在 OpenProcess/tryInvoke 上报出来
        }
    }

    // ---------------------------------------------------------------- memory

    public long allocate(long size) {
        return allocate(size, NativeApi.PAGE_EXECUTE_READWRITE);
    }

    public long allocate(long size, int protect) {
        Pointer address = NativeApi.kernel32().VirtualAllocEx(handle, null, size,
                NativeApi.MEM_COMMIT | NativeApi.MEM_RESERVE, protect);
        return NativeApi.isNull(address) ? 0 : Pointer.nativeValue(address);
    }

    public boolean free(long address) {
        return NativeApi.kernel32().VirtualFreeEx(handle, new Pointer(address), 0, NativeApi.MEM_RELEASE);
    }

    public boolean write(long address, byte[] buffer) {
        return write(address, buffer, buffer.length);
    }

    public boolean write(long address, byte[] buffer, int count) {
        LongByReference written = new LongByReference();
        boolean ok = NativeApi.kernel32().WriteProcessMemory(handle, new Pointer(address), buffer, count, written);
        return ok && written.getValue() == count;
    }

    /** 读失败返回 null；读到了但被截断时返回裁剪后的数组。 */
    public byte[] read(long address, int size) {
        if (size <= 0) {
            return null;
        }
        byte[] buffer = new byte[size];
        LongByReference read = new LongByReference();
        if (!NativeApi.kernel32().ReadProcessMemory(handle, new Pointer(address), buffer, size, read)) {
            return null;
        }
        long got = read.getValue();
        if (got == size) {
            return buffer;
        }
        if (got <= 0) {
            return null;
        }
        return Arrays.copyOf(buffer, (int) got);
    }

    public long readUInt64(long address) {
        byte[] buffer = read(address, 8);
        return buffer == null || buffer.length < 8 ? 0 : PeImage.readU64(buffer, 0);
    }

    public boolean protect(long address, long size, int newProtect, IntByReference oldProtect) {
        return NativeApi.kernel32().VirtualProtectEx(handle, new Pointer(address), size, newProtect, oldProtect);
    }

    // ---------------------------------------------------------------- threads

    public Pointer createThread(long start, long parameter, IntByReference threadId) {
        return NativeApi.kernel32().CreateRemoteThread(handle, null, 0, new Pointer(start),
                new Pointer(parameter), 0, threadId);
    }

    /** 等待线程结束。超时返回 false；否则把线程退出码写进 exitCode 并返回 true。 */
    public boolean waitThread(Pointer thread, int milliseconds, IntByReference exitCode) {
        if (NativeApi.kernel32().WaitForSingleObject(thread, milliseconds) != 0) {
            return false;
        }
        return NativeApi.kernel32().GetExitCodeThread(thread, exitCode);
    }

    // ---------------------------------------------------------------- modules

    public long getModuleBase(String moduleName) {
        Pointer snapshot = NativeApi.kernel32().CreateToolhelp32Snapshot(
                NativeApi.TH32CS_SNAPMODULE | NativeApi.TH32CS_SNAPMODULE32, processId);
        if (NativeApi.isInvalidHandle(snapshot)) {
            return 0;
        }

        try {
            NativeApi.ModuleEntry32W entry = new NativeApi.ModuleEntry32W();
            if (!NativeApi.kernel32().Module32FirstW(snapshot, entry)) {
                return 0;
            }
            while (true) {
                if (entry.moduleName().equalsIgnoreCase(moduleName)) {
                    return NativeApi.isNull(entry.modBaseAddr) ? 0 : Pointer.nativeValue(entry.modBaseAddr);
                }
                if (!NativeApi.kernel32().Module32NextW(snapshot, entry)) {
                    return 0;
                }
            }
        } finally {
            NativeApi.kernel32().CloseHandle(snapshot);
        }
    }

    /** 目标进程的位宽：2 = 64 位，1 = 32 位，0 = 判断不出来。 */
    public int targetArch() {
        try {
            ShortByReference processMachine = new ShortByReference();
            ShortByReference nativeMachine = new ShortByReference();
            if (NativeApi.kernel32().IsWow64Process2(handle, processMachine, nativeMachine)) {
                int machine = processMachine.getValue() == NativeApi.IMAGE_FILE_MACHINE_UNKNOWN
                        ? nativeMachine.getValue()
                        : processMachine.getValue();
                return machine == NativeApi.IMAGE_FILE_MACHINE_AMD64
                        || machine == NativeApi.IMAGE_FILE_MACHINE_ARM64 ? 2 : 1;
            }
        } catch (RuntimeException ignored) {
            // 老系统没有 IsWow64Process2，退回 IsWow64Process
        }

        try {
            IntByReference wow64 = new IntByReference();
            if (NativeApi.kernel32().IsWow64Process(handle, wow64)) {
                return wow64.getValue() != 0 ? 1 : 2;
            }
        } catch (RuntimeException ignored) {
            // 两种情况都问不出来，交给调用方当作"未知"处理
        }
        return 0;
    }

    public String imagePath() {
        char[] buffer = new char[1024];
        IntByReference size = new IntByReference(buffer.length);
        if (!NativeApi.kernel32().QueryFullProcessImageNameW(handle, 0, buffer, size)) {
            return "";
        }
        int length = size.getValue();
        if (length <= 0) {
            return "";
        }
        return new String(buffer, 0, Math.min(length, buffer.length));
    }

    // ---------------------------------------------------------------- tryInvoke

    /**
     * 在目标进程里调用一个函数并取回返回值。
     *
     * @param function  目标地址（目标进程地址空间内的绝对地址）
     * @param args      最多 4 个参数，按 x64 调用约定走 rcx/rdx/r8/r9
     * @param timeoutMs 等待上限
     * @param result    单元素数组，成功时写入返回值
     * @param error     单元素数组，失败时写入原因
     */
    public boolean tryInvoke(long function, long[] args, int timeoutMs, long[] result, String[] error) {
        if (function == 0) {
            error[0] = "目标函数地址为 0";
            return false;
        }

        long block = allocate(INVOKE_BLOCK_SIZE);
        if (block == 0) {
            error[0] = "远程分配调用桩失败";
            return false;
        }

        long slot = block + INVOKE_SLOT_OFFSET;
        long code = block + INVOKE_CODE_OFFSET;

        try {
            byte[] stub = buildCallStub(slot, function, args);
            if (!write(code, stub)) {
                error[0] = "写调用桩失败";
                return false;
            }

            // 结果槽先清零，否则函数没写返回值时会读到脏数据。原实现不检查这次写的结果。
            write(slot, new byte[8]);

            IntByReference threadId = new IntByReference();
            Pointer thread = createThread(code, 0, threadId);
            if (NativeApi.isNull(thread)) {
                error[0] = "CreateRemoteThread 失败，Win32 错误 " + NativeApi.lastError();
                return false;
            }

            try {
                if (NativeApi.kernel32().WaitForSingleObject(thread, timeoutMs) != 0) {
                    error[0] = "远程调用超时（" + timeoutMs + " ms，tid " + threadId.getValue() + "）";
                    return false;
                }
                if (result != null && result.length > 0) {
                    result[0] = readUInt64(slot);
                }
                return true;
            } finally {
                NativeApi.kernel32().CloseHandle(thread);
            }
        } finally {
            free(block);
        }
    }

    /** 拼调用桩并刷指令缓存（真实注入用这条）。 */
    private byte[] buildCallStub(long slot, long function, long[] args) {
        byte[] stub = callStub(slot, function, args);

        // 原实现这里是 FlushInstructionCache(_handle, IntPtr.Zero, 0)——参数顺序写反，
        // 地址和长度都传了 0，等于什么都没刷。照抄不改，保持行为一致。
        NativeApi.kernel32().FlushInstructionCache(handle, Pointer.NULL, 0);

        return stub;
    }

    /**
     * 拼 x64 调用桩：保存 rbx，把结果槽地址放 rbx，按序装参数，call 目标，把 rax 写回 [rbx]。
     * 与 C# 的 RemoteProcess.BuildCallStub 逐字节一致。拆出来是为了不碰任何进程就能验字节。
     */
    static byte[] callStub(long slot, long function, long[] args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);

        out.write(0x53);                              // push rbx
        out.write(0x48);                              // mov rbx, slot
        out.write(0xBB);
        writeU64(out, slot);
        out.write(0x48);                              // sub rsp, 0x20（影子空间）
        out.write(0x83);
        out.write(0xEC);
        out.write(0x20);

        int count = args == null ? 0 : Math.min(args.length, 4);
        for (int i = 0; i < count; i++) {
            switch (i) {
                case 0 -> {
                    out.write(0x48);                  // mov rcx, arg0
                    out.write(0xB9);
                }
                case 1 -> {
                    out.write(0x48);                  // mov rdx, arg1
                    out.write(0xBA);
                }
                case 2 -> {
                    out.write(0x49);                  // mov r8, arg2
                    out.write(0xB8);
                }
                default -> {
                    out.write(0x49);                  // mov r9, arg3
                    out.write(0xB9);
                }
            }
            writeU64(out, args[i]);
        }

        out.write(0x48);                              // mov rax, function
        out.write(0xB8);
        writeU64(out, function);
        out.write(0xFF);                              // call rax
        out.write(0xD0);
        out.write(0x48);                              // mov [rbx], rax
        out.write(0x89);
        out.write(0x03);
        out.write(0x48);                              // add rsp, 0x20
        out.write(0x83);
        out.write(0xC4);
        out.write(0x20);
        out.write(0x5B);                              // pop rbx
        out.write(0xC3);                              // ret

        return out.toByteArray();
    }

    private static void writeU64(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
        out.write((int) ((value >>> 32) & 0xFF));
        out.write((int) ((value >>> 40) & 0xFF));
        out.write((int) ((value >>> 48) & 0xFF));
        out.write((int) ((value >>> 56) & 0xFF));
    }

    @Override
    public void close() {
        if (!NativeApi.isNull(handle)) {
            NativeApi.kernel32().CloseHandle(handle);
        }
    }
}
