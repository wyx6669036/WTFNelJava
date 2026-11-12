package org.wyx6669036.wtfnel.injection;

/**
 * 经典反射注入：把整个文件写进目标进程，再从 ReflectiveLoader 导出处起一个远程线程。
 * 移植自 WtfNel.Core/Injection/ReflectivePeLoader.cs。
 */
final class ReflectivePeLoader {

    private static final String REFLECTIVE_LOADER_EXPORT = "ReflectiveLoader";

    private ReflectivePeLoader() {
    }

    static boolean hasReflectiveLoader(PeImage pe) {
        return pe.findExport(REFLECTIVE_LOADER_EXPORT) != null;
    }

    static InjectionResult inject(RemoteProcess process, PeImage pe, InjectionOptions options, long elapsed) {
        PeImage.Export export = pe.findExport(REFLECTIVE_LOADER_EXPORT);
        if (export == null) {
            return InjectionResult.fail(InjectionStrategy.REFLECTIVE,
                    "payload 没有导出 ReflectiveLoader，无法走反射注入", elapsed);
        }

        long loaderOffset = pe.rvaToOffset(export.rva());
        if (loaderOffset == 0) {
            return InjectionResult.fail(InjectionStrategy.REFLECTIVE,
                    "ReflectiveLoader 导出的 RVA 无法映射回文件偏移", elapsed);
        }

        int targetArch = process.targetArch();
        int payloadArch = pe.is64Bit() ? 2 : 1;
        if (targetArch != 0 && targetArch != payloadArch) {
            return InjectionResult.fail(InjectionStrategy.REFLECTIVE,
                    "位宽不匹配：目标 pid " + process.processId() + " 是 " + archName(targetArch)
                            + "，payload 是 " + archName(payloadArch), elapsed);
        }

        long remote = process.allocate(pe.sizeOfImage() == 0 ? pe.bytes().length : pe.sizeOfImage());
        if (remote == 0) {
            return InjectionResult.fail(InjectionStrategy.REFLECTIVE, "VirtualAllocEx 失败", elapsed);
        }

        boolean keepBuffer = false;
        try {
            if (!process.write(remote, pe.bytes(), pe.bytes().length)) {
                return InjectionResult.fail(InjectionStrategy.REFLECTIVE, "WriteProcessMemory 失败", elapsed);
            }

            long start = remote + loaderOffset;
            com.sun.jna.ptr.IntByReference threadId = new com.sun.jna.ptr.IntByReference();
            com.sun.jna.Pointer thread = process.createThread(start, options.parameter(), threadId);
            if (NativeApi.isNull(thread)) {
                return InjectionResult.fail(InjectionStrategy.REFLECTIVE,
                        "CreateRemoteThread 失败，Win32 错误 " + NativeApi.lastError(), elapsed);
            }

            try {
                com.sun.jna.ptr.IntByReference exitCode = new com.sun.jna.ptr.IntByReference();
                boolean finished = process.waitThread(thread, options.waitMs(), exitCode);

                if (!finished) {
                    // 超时按成功算：loader 可能还在做导入/TLS/初始化，硬判失败会把已经成功的注入报成失败
                    return InjectionResult.ok(InjectionStrategy.REFLECTIVE,
                            "已注入 pid " + process.processId() + "，loader 仍在运行（tid " + threadId.getValue()
                                    + "，等满 " + options.waitMs() + " ms）", elapsed, remote);
                }

                if (exitCode.getValue() == 0) {
                    return InjectionResult.fail(InjectionStrategy.REFLECTIVE,
                            "远程线程已结束但 ReflectiveLoader 返回 0（目标内部映射失败：导入/TLS/重定位），tid "
                                    + threadId.getValue(), elapsed, remote);
                }

                keepBuffer = true;
                return InjectionResult.ok(InjectionStrategy.REFLECTIVE,
                        "已注入 pid " + process.processId() + "，loader 退出码 " + exitCode.getValue()
                                + String.format("，镜像基址 0x%x", remote), elapsed, remote);
            } finally {
                NativeApi.kernel32().CloseHandle(thread);
            }
        } finally {
            // 只有 loader 确实接管了这块内存才留着，否则目标进程里会一直漏着
            if (!keepBuffer) {
                process.free(remote);
            }
        }
    }

    private static String archName(int arch) {
        return switch (arch) {
            case 2 -> "x64";
            case 1 -> "x86";
            default -> "未知";
        };
    }
}
