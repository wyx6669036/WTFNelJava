package org.wyx6669036.wtfnel.injection;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 手动映射：自己铺节、修重定位、解导入表、跑 TLS 回调，完全不依赖 payload 自带的加载器。
 * 移植自 WtfNel.Core/Injection/ManualMapper.cs，只实现 x64 路径（与原文一致）。
 */
final class ManualMapper {

    private ManualMapper() {
    }

    static InjectionResult inject(RemoteProcess process, PeImage pe, InjectionOptions options, Consumer<String> log,
                                  long elapsed) {
        if (!pe.is64Bit()) {
            return InjectionResult.fail(InjectionStrategy.MANUAL_MAP,
                    "手动映射只实现了 x64 路径（payload 是 32 位）", elapsed);
        }

        if (process.targetArch() == 1) {
            return InjectionResult.fail(InjectionStrategy.MANUAL_MAP, "目标进程是 32 位，x64 注入器无法映射", elapsed);
        }

        long remoteBase = process.allocate(pe.sizeOfImage(), NativeApi.PAGE_EXECUTE_READWRITE);
        if (remoteBase == 0) {
            return InjectionResult.fail(InjectionStrategy.MANUAL_MAP, "VirtualAllocEx(SizeOfImage) 失败", elapsed);
        }

        log.accept(String.format("手动映射：远程基址 0x%x，SizeOfImage 0x%x", remoteBase, pe.sizeOfImage()));

        try {
            byte[] image = buildImage(pe, remoteBase, log);
            ImportResolver resolver = new ImportResolver(process, log);
            if (!resolver.apply(pe, image)) {
                return InjectionResult.fail(InjectionStrategy.MANUAL_MAP, "导入表解析失败", elapsed, remoteBase);
            }

            if (!process.write(remoteBase, image, image.length)) {
                return InjectionResult.fail(InjectionStrategy.MANUAL_MAP, "WriteProcessMemory(镜像) 失败", elapsed,
                        remoteBase);
            }

            applyProtections(process, pe, remoteBase, log);
            registerExceptionTable(process, pe, remoteBase, log);
            runTlsCallbacks(process, pe, image, remoteBase, log);

            if (pe.entryPointRva() != 0) {
                long entry = remoteBase + pe.entryPointRva();
                long[] reason = new long[1];
                String[] entryError = new String[1];
                if (!process.tryInvoke(entry,
                        new long[]{remoteBase, NativeApi.DLL_PROCESS_ATTACH, 0},
                        options.waitMs(), reason, entryError)) {
                    log.accept("DllMain 调用失败：" + entryError[0]);
                    return InjectionResult.fail(InjectionStrategy.MANUAL_MAP,
                            "远程 DllMain 调用失败：" + entryError[0], elapsed, remoteBase);
                }
                log.accept(String.format("DllMain 返回 0x%x", reason[0]));
            }

            if (options.initExport() != null && !options.initExport().isBlank()) {
                String initExport = options.initExport();
                PeImage.Export export = pe.findExport(initExport);
                if (export == null) {
                    return InjectionResult.fail(InjectionStrategy.MANUAL_MAP,
                            "镜像里没有导出 " + initExport, elapsed, remoteBase);
                }

                long init = remoteBase + export.rva();
                long[] initResult = new long[1];
                String[] initError = new String[1];
                if (!process.tryInvoke(init, new long[0], options.waitMs(), initResult, initError)) {
                    return InjectionResult.fail(InjectionStrategy.MANUAL_MAP,
                            "调用 " + initExport + " 失败：" + initError[0], elapsed, remoteBase);
                }

                log.accept(String.format("%s 返回 0x%x", initExport, initResult[0]));
                if (initResult[0] == 0) {
                    return InjectionResult.fail(InjectionStrategy.MANUAL_MAP,
                            initExport + " 返回 0（payload 内部初始化失败）", elapsed, remoteBase);
                }
            }

            return InjectionResult.ok(InjectionStrategy.MANUAL_MAP,
                    String.format("已手动映射到 pid %d，基址 0x%x", process.processId(), remoteBase),
                    elapsed, remoteBase);
        } catch (RuntimeException e) {
            process.free(remoteBase);
            return InjectionResult.fail(InjectionStrategy.MANUAL_MAP, "手动映射异常：" + e.getMessage(), elapsed, 0, e);
        }
    }

    /** 把文件按节铺成内存镜像，并按 delta 修重定位。包级可见只为单测：不碰任何进程。 */
    static byte[] buildImage(PeImage pe, long remoteBase, Consumer<String> log) {
        int sizeOfImage = (int) pe.sizeOfImage();
        byte[] image = new byte[sizeOfImage];
        byte[] file = pe.bytes();

        int headerLength = (int) Math.min(pe.sizeOfHeaders(), file.length);
        System.arraycopy(file, 0, image, 0, headerLength);

        for (PeImage.PeSection section : pe.sections()) {
            if (section == null || section.rawSize() == 0) {
                continue;
            }
            long available = Math.min(section.rawSize(), (long) file.length - section.rawOffset());
            if (available <= 0) {
                continue;
            }
            long room = (long) image.length - section.virtualAddress();
            int copy = (int) Math.min(available, Math.max(0, room));
            if (copy <= 0) {
                continue;
            }
            System.arraycopy(file, (int) section.rawOffset(), image, (int) section.virtualAddress(), copy);
        }

        long delta = remoteBase - pe.imageBase();
        if (delta == 0) {
            log.accept("重定位：远程基址与首选基址相同，跳过");
            return image;
        }

        int applied = 0;
        for (PeImage.PeRelocationBlock block : pe.readRelocations()) {
            for (int raw : block.entries()) {
                int type = (raw >>> 12) & 0xF;
                int offset = raw & 0x0FFF;
                long at = block.pageRva() + offset;
                if (at < 0 || at + 8 > image.length) {
                    continue;
                }

                switch (type) {
                    case 0 -> {
                        // IMAGE_REL_BASED_ABSOLUTE 是填充项
                    }
                    case 10 -> {
                        long value = PeImage.readU64(image, (int) at);
                        if (value == 0) {
                            break;
                        }
                        PeImage.writeU64(image, (int) at, value + delta);
                        applied++;
                    }
                    case 3 -> {
                        long value = PeImage.readU32(image, (int) at);
                        if (value == 0) {
                            break;
                        }
                        // 32 位重定位只保留低 32 位，溢出就是溢出——原实现也是直接截断
                        PeImage.writeU32(image, (int) at, (value & 0xFFFF_FFFFL) + (delta & 0xFFFF_FFFFL));
                        applied++;
                    }
                    default -> log.accept(String.format("重定位：跳过不支持的类型 %d（page 0x%x）", type,
                            block.pageRva()));
                }
            }
        }
        log.accept(String.format("重定位：应用 %d 项，delta 0x%x", applied, delta));
        return image;
    }

    private static void applyProtections(RemoteProcess process, PeImage pe, long remoteBase, Consumer<String> log) {
        IntByReference old = new IntByReference();
        if (!process.protect(remoteBase, pe.sizeOfHeaders(), NativeApi.PAGE_READONLY, old)) {
            log.accept("保护：头部改只读失败（继续）");
        }

        for (PeImage.PeSection section : pe.sections()) {
            if (section == null || section.mappedSpan() == 0) {
                continue;
            }
            int protect = PeImage.protectionFor(section);
            if (!process.protect(remoteBase + section.virtualAddress(), section.mappedSpan(), protect, old)) {
                log.accept(String.format("保护：节 %s 设置 0x%x 失败（继续）", section.name(), protect));
            }
        }
    }

    /** 注册 .pdata 里描述的展开信息，否则镜像里抛异常会直接带走进程。 */
    private static void registerExceptionTable(RemoteProcess process, PeImage pe, long remoteBase,
                                               Consumer<String> log) {
        long tableRva = pe.directories()[3][0];
        long tableSize = pe.directories()[3][1];
        if (tableRva == 0 || tableSize < 12) {
            return;
        }

        long remoteNtdll = process.getModuleBase("ntdll.dll");
        Pointer localNtdll = NativeApi.kernel32().GetModuleHandleW("ntdll.dll");
        if (remoteNtdll == 0 || NativeApi.isNull(localNtdll)) {
            log.accept("异常表：定位 ntdll 失败");
            return;
        }

        Pointer localAdd = NativeApi.kernel32().GetProcAddress(localNtdll,
                NativeApi.ansi("RtlAddFunctionTable"));
        if (NativeApi.isNull(localAdd)) {
            return;
        }
        long remoteAdd = remoteNtdll + (Pointer.nativeValue(localAdd) - Pointer.nativeValue(localNtdll));

        long count = tableSize / 12;
        long table = remoteBase + tableRva;
        long[] accepted = new long[1];
        String[] error = new String[1];
        if (!process.tryInvoke(remoteAdd, new long[]{table, count, remoteBase}, 10000, accepted, error)) {
            log.accept("异常表：RtlAddFunctionTable 调用失败：" + error[0]);
            return;
        }

        log.accept("异常表：注册 " + count + " 项，返回 " + accepted[0]);
    }

    private static void runTlsCallbacks(RemoteProcess process, PeImage pe, byte[] image, long remoteBase,
                                        Consumer<String> log) {
        PeImage.PeTlsInfo tls = pe.readTls();
        if (tls == null || tls.addressOfCallbacksRva() == 0) {
            return;
        }

        long at = tls.addressOfCallbacksRva();
        int count = 0;
        while (at + 8 <= image.length) {
            long callback = PeImage.readU64(image, (int) at);
            if (callback == 0) {
                break;
            }

            long remoteCallback = callback - pe.imageBase() + remoteBase;
            String[] error = new String[1];
            if (!process.tryInvoke(remoteCallback,
                    new long[]{remoteBase, NativeApi.DLL_PROCESS_ATTACH, 0}, 10000, new long[1], error)) {
                log.accept(String.format("TLS 回调 0x%x 失败：%s", remoteCallback, error[0]));
                break;
            }
            count++;
            at += 8;
        }
        log.accept("TLS 回调：执行 " + count + " 个");
    }

    /**
     * 把导入表里每个符号解析成目标进程里的绝对地址，直接写回镜像的 IAT。
     * 关键点：本进程和目标进程的同名 DLL 基址不同（ASLR），只有模块内 RVA 稳定，
     * 所以远程地址一律用「远程基址 + (本地地址 - 本地基址)」换算。
     */
    private static final class ImportResolver {

        private final RemoteProcess process;
        private final Consumer<String> log;
        private final Map<String, Long> remoteModules = new HashMap<>();
        private final Map<String, Long> localModules = new HashMap<>();
        private final List<LocalModule> localList = listLocalModules();

        private record LocalModule(long base, String name, int size) {
        }

        ImportResolver(RemoteProcess process, Consumer<String> log) {
            this.process = process;
            this.log = log;
        }

        boolean apply(PeImage pe, byte[] image) {
            List<PeImage.PeImport> imports = pe.readImports();
            if (imports.isEmpty()) {
                log.accept("导入表为空");
                return true;
            }

            int resolved = 0;
            int failed = 0;
            for (PeImage.PeImport importEntry : imports) {
                for (PeImage.PeImportSymbol symbol : importEntry.symbols()) {
                    long target = resolve(importEntry.module(), symbol);
                    if (target == 0) {
                        failed++;
                        if (failed <= 8) {
                            String name = symbol.byOrdinal() ? "#" + symbol.ordinal() : symbol.name();
                            log.accept("导入未解析：" + importEntry.module() + "!" + name);
                        }
                        continue;
                    }

                    long at = symbol.iatRva();
                    if (at + 8 > image.length) {
                        continue;
                    }
                    PeImage.writeU64(image, (int) at, target);
                    resolved++;
                }
            }

            log.accept("导入：解析 " + resolved + " 项，失败 " + failed + " 项");
            return failed == 0;
        }

        private long resolve(String moduleName, PeImage.PeImportSymbol symbol) {
            try {
                long local = loadLocal(moduleName);
                if (local == 0) {
                    long remote = remoteLoadLibrary(moduleName);
                    return remote == 0 ? 0 : remoteGetProcAddress(remote, symbol);
                }

                Pointer localModule = new Pointer(local);
                Pointer address = symbol.byOrdinal()
                        ? NativeApi.getProcAddressOrdinal(localModule, symbol.ordinal())
                        : NativeApi.kernel32().GetProcAddress(localModule, NativeApi.ansi(symbol.name()));
                if (NativeApi.isNull(address)) {
                    return 0;
                }

                long addressValue = Pointer.nativeValue(address);
                LocalModule owner = localModuleOf(addressValue);
                if (owner == null) {
                    long rva = addressValue - local;
                    return remoteModule(moduleName) + rva;
                }

                long remoteOwner = remoteModule(owner.name());
                if (remoteOwner == 0) {
                    return 0;
                }
                return remoteOwner + (addressValue - owner.base());
            } catch (RuntimeException e) {
                log.accept("导入 " + moduleName + " 异常：" + e.getMessage());
                return 0;
            }
        }

        private long loadLocal(String moduleName) {
            Long cached = localModules.get(moduleName);
            if (cached != null) {
                return cached;
            }

            Pointer handle = NativeApi.kernel32().LoadLibraryW(moduleName);
            if (NativeApi.isNull(handle)) {
                handle = NativeApi.kernel32().LoadLibraryExW(moduleName, Pointer.NULL,
                        NativeApi.LOAD_LIBRARY_SEARCH_DEFAULT_DIRS | NativeApi.LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR);
            }

            if (NativeApi.isNull(handle)) {
                return 0;
            }
            long value = Pointer.nativeValue(handle);
            localModules.put(moduleName, value);
            return value;
        }

        private long remoteModule(String moduleName) {
            Long cached = remoteModules.get(moduleName);
            if (cached != null) {
                return cached;
            }

            long remote = process.getModuleBase(moduleName);
            if (remote == 0) {
                remote = remoteLoadLibrary(moduleName);
            }
            if (remote != 0) {
                remoteModules.put(moduleName, remote);
                log.accept(String.format("远程模块 %s = 0x%x", moduleName, remote));
            }
            return remote;
        }

        private long remoteLoadLibrary(String moduleName) {
            long remoteKernel32 = process.getModuleBase("kernel32.dll");
            Pointer localKernel32 = NativeApi.kernel32().GetModuleHandleW("kernel32.dll");
            if (remoteKernel32 == 0 || NativeApi.isNull(localKernel32)) {
                return 0;
            }

            Pointer localLoadLibraryA = NativeApi.kernel32().GetProcAddress(localKernel32,
                    NativeApi.ansi("LoadLibraryA"));
            if (NativeApi.isNull(localLoadLibraryA)) {
                return 0;
            }
            long remoteLoadLibraryA = remoteKernel32 + (Pointer.nativeValue(localLoadLibraryA)
                    - Pointer.nativeValue(localKernel32));

            byte[] name = NativeApi.ansi(moduleName);
            long buffer = process.allocate(name.length, NativeApi.PAGE_READWRITE);
            if (buffer == 0) {
                return 0;
            }

            long result = 0;
            try {
                if (!process.write(buffer, name, name.length)) {
                    return 0;
                }
                long[] value = new long[1];
                String[] error = new String[1];
                if (!process.tryInvoke(remoteLoadLibraryA, new long[]{buffer}, 10000, value, error)) {
                    log.accept("远程 LoadLibrary(" + moduleName + ") 失败：" + error[0]);
                    return 0;
                }
                result = value[0];
            } finally {
                process.free(buffer);
            }

            if (result != 0) {
                log.accept(String.format("远程 LoadLibrary(%s) = 0x%x", moduleName, result));
            }
            return result;
        }

        private long remoteGetProcAddress(long remoteModule, PeImage.PeImportSymbol symbol) {
            long remoteKernel32 = process.getModuleBase("kernel32.dll");
            Pointer localKernel32 = NativeApi.kernel32().GetModuleHandleW("kernel32.dll");
            if (remoteKernel32 == 0 || NativeApi.isNull(localKernel32)) {
                return 0;
            }

            Pointer localGetProcAddress = NativeApi.kernel32().GetProcAddress(localKernel32,
                    NativeApi.ansi("GetProcAddress"));
            if (NativeApi.isNull(localGetProcAddress)) {
                return 0;
            }
            long remoteGetProcAddress = remoteKernel32 + (Pointer.nativeValue(localGetProcAddress)
                    - Pointer.nativeValue(localKernel32));

            long[] value = new long[1];
            String[] error = new String[1];

            if (symbol.byOrdinal()) {
                return process.tryInvoke(remoteGetProcAddress,
                        new long[]{remoteModule, symbol.ordinal()}, 10000, value, error) ? value[0] : 0;
            }

            byte[] name = NativeApi.ansi(symbol.name());
            long buffer = process.allocate(name.length, NativeApi.PAGE_READWRITE);
            if (buffer == 0) {
                return 0;
            }
            try {
                if (!process.write(buffer, name, name.length)) {
                    return 0;
                }
                return process.tryInvoke(remoteGetProcAddress, new long[]{remoteModule, buffer}, 10000,
                        value, error) ? value[0] : 0;
            } finally {
                process.free(buffer);
            }
        }

        private LocalModule localModuleOf(long address) {
            for (LocalModule module : localList) {
                long start = module.base();
                if (address >= start && address < start + Math.max(module.size(), 0x1000)) {
                    return module;
                }
            }
            return null;
        }

        private static List<LocalModule> listLocalModules() {
            List<LocalModule> result = new ArrayList<>();
            Pointer snapshot = NativeApi.kernel32().CreateToolhelp32Snapshot(
                    NativeApi.TH32CS_SNAPMODULE | NativeApi.TH32CS_SNAPMODULE32,
                    (int) ProcessHandle.current().pid());
            if (NativeApi.isInvalidHandle(snapshot)) {
                return result;
            }

            try {
                NativeApi.ModuleEntry32W entry = new NativeApi.ModuleEntry32W();
                if (!NativeApi.kernel32().Module32FirstW(snapshot, entry)) {
                    return result;
                }
                do {
                    long base = NativeApi.isNull(entry.modBaseAddr) ? 0 : Pointer.nativeValue(entry.modBaseAddr);
                    result.add(new LocalModule(base, entry.moduleName(), entry.modBaseSize));
                } while (NativeApi.kernel32().Module32NextW(snapshot, entry));
            } finally {
                NativeApi.kernel32().CloseHandle(snapshot);
            }
            return result;
        }
    }
}
