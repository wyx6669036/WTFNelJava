package org.wyx6669036.wtfnel.injection;

import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.util.AppPaths;
import org.wyx6669036.wtfnel.util.NativeAssets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把一个 payload DLL 注进目标进程。移植自 WtfNel.Core/Injection/InjectionService.cs。
 *
 * <p>策略选择：Auto 时有 ReflectiveLoader 导出就反射注入，否则落盘 LoadLibrary。
 * 任何策略失败后如果允许兜底，都会再试一次 LoadLibrary —— 因为反射注入失败的原因
 * 大多是 payload 自己的 ReflectiveLoader 内部问题，落盘加载往往还能成功。</p>
 */
public final class InjectionService {

    public static final String HWID_PAYLOAD = "WtfNel.Payload.HwidSpoof.dll";
    public static final String PROXY_PAYLOAD = "WtfNel.Payload.Proxy.dll";

    private InjectionService() {
    }

    /** 要被注入的目标程序。 */
    public enum GameTarget {
        MINECRAFT("Minecraft"),
        SCP_SL("ScpSl");

        private final String label;

        GameTarget(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * 一种目标的进程识别方式：要么按窗口标题找，要么按进程名找。
     *
     * @param windowKeywords 窗口标题里出现任一个就算命中
     * @param processNames   进程名（不含 .exe 也行）；非空时优先于窗口匹配
     * @param requireJava    窗口匹配时是否要求镜像是 java.exe/javaw.exe
     */
    public record InjectionProfile(GameTarget target, List<String> windowKeywords, List<String> processNames,
                                   boolean requireJava) {

        /** 布吉岛客户端：窗口标题带「布吉岛」，且必须是 Java 进程，避免把浏览器标题误判。 */
        public static final InjectionProfile MINECRAFT =
                new InjectionProfile(GameTarget.MINECRAFT, List.of("布吉岛"), List.of(), true);

        /** SCP:SL 是 Unity 游戏，进程名固定，没有可靠的中文窗口标题。 */
        public static final InjectionProfile SCP_SL =
                new InjectionProfile(GameTarget.SCP_SL, List.of(), List.of("SCPSL"), false);

        public static InjectionProfile forTarget(GameTarget target) {
            return target == GameTarget.SCP_SL ? SCP_SL : MINECRAFT;
        }

        public List<ProcessTarget.ProcessCandidate> find() {
            return processNames != null && !processNames.isEmpty()
                    ? ProcessTarget.findByProcessName(processNames)
                    : ProcessTarget.findByWindow(windowKeywords, requireJava);
        }
    }

    public static Path payloadDirectory() {
        return AppPaths.baseDirectory().resolve("payloads");
    }

    /**
     * payload 查找顺序：绝对路径 → exe/payloads/名字 → exe/名字。
     */
    public static Path resolvePayload(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        Path direct = Path.of(name);
        if (direct.isAbsolute()) {
            return Files.isRegularFile(direct) ? direct : null;
        }

        Path inPayloads = payloadDirectory().resolve(name);

        // 先按内容跟 jar 里带的对齐（一致就不动），再决定用哪个文件。
        Path fromJar = NativeAssets.installPayload(payloadDirectory(), name);
        if (fromJar != null) {
            return fromJar;
        }
        if (Files.isRegularFile(inPayloads)) {
            return inPayloads;
        }

        Path besideExe = AppPaths.baseDirectory().resolve(name);
        return Files.isRegularFile(besideExe) ? besideExe : null;
    }

    /** 读并解析 payload；失败时把原因写进 error[0]。 */
    static PeImage tryLoadPayload(Path path, String[] error) {
        if (path == null || !Files.isRegularFile(path)) {
            error[0] = "payload 不存在: " + path;
            return null;
        }

        try {
            byte[] bytes = Files.readAllBytes(path);
            PeImage.ParseResult parsed = PeImage.parse(bytes);
            if (!parsed.ok()) {
                error[0] = parsed.error();
                return null;
            }
            return parsed.image();
        } catch (IOException | RuntimeException e) {
            error[0] = "读取 payload 失败: " + e.getMessage();
            return null;
        }
    }

    static InjectionStrategy chooseStrategy(PeImage pe, InjectionStrategy requested) {
        if (requested == InjectionStrategy.AUTO) {
            return ReflectivePeLoader.hasReflectiveLoader(pe) ? InjectionStrategy.REFLECTIVE
                    : InjectionStrategy.LOAD_LIBRARY;
        }
        return requested;
    }

    public static InjectionResult inject(int processId, String payloadPath) {
        return inject(processId, payloadPath, null);
    }

    public static InjectionResult inject(int processId, String payloadPath, InjectionOptions options) {
        InjectionOptions opts = options == null ? new InjectionOptions() : options;
        long started = System.nanoTime();
        Path path = Path.of(payloadPath);
        String fileName = path.getFileName() == null ? payloadPath : path.getFileName().toString();

        String[] loadError = new String[1];
        PeImage pe = tryLoadPayload(path, loadError);
        if (pe == null) {
            return InjectionResult.fail(opts.strategy(), loadError[0], elapsed(started));
        }

        InjectionStrategy strategy = chooseStrategy(pe, opts.strategy());
        opts.emit(String.format("注入：pid %d，payload %s（%s，%d KB），策略 %s",
                processId, fileName, pe.archName(), pe.sizeOfImage() / 1024, strategy.label()));

        String[] openError = new String[1];
        try (RemoteProcess process = RemoteProcess.open(processId, openError)) {
            if (process == null) {
                return InjectionResult.fail(strategy, openError[0], elapsed(started));
            }

            InjectionResult result = switch (strategy) {
                case REFLECTIVE -> ReflectivePeLoader.inject(process, pe, opts, elapsed(started));
                case MANUAL_MAP -> ManualMapper.inject(process, pe, opts, opts::emit, elapsed(started));
                case AUTO, LOAD_LIBRARY -> loadFromDisk(process, pe, path, fileName, opts, started);
            };

            if (!result.success() && opts.fallbackToLoadLibrary() && strategy != InjectionStrategy.LOAD_LIBRARY) {
                opts.emit("策略 " + strategy.label() + " 失败（" + result.detail() + "），改用 LoadLibrary");
                InjectionResult fallback = loadFromDisk(process, pe, path, fileName, opts, started);
                if (fallback.success()) {
                    return fallback;
                }
                return result.withDetail(result.detail() + "；兜底 LoadLibrary 也失败：" + fallback.detail());
            }

            return result;
        }
    }

    private static InjectionResult loadFromDisk(RemoteProcess process, PeImage pe, Path payloadPath, String fileName,
                                                InjectionOptions options, long started) {
        try {
            long[] module = new long[1];
            String error = tryRemoteLoadLibrary(process, payloadPath.toString(), options, module);
            if (error == null) {
                return finishLoad(process, pe, module[0], payloadPath.toString(), options, started);
            }

            // 目标进程可能没有权限读我们的目录（比如 exe 在 UAC 保护目录里），复制到临时目录再试
            Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"), "WtfNel", "payloads");
            Files.createDirectories(tempDirectory);
            Path copy = tempDirectory.resolve(fileName);
            Files.copy(payloadPath, copy, StandardCopyOption.REPLACE_EXISTING);

            long[] copyModule = new long[1];
            String copyError = tryRemoteLoadLibrary(process, copy.toString(), options, copyModule);
            if (copyError == null) {
                return finishLoad(process, pe, copyModule[0], copy.toString(), options, started);
            }

            return InjectionResult.fail(InjectionStrategy.LOAD_LIBRARY,
                    "远程 LoadLibraryW 失败：" + error + "；临时副本也失败：" + copyError, elapsed(started));
        } catch (IOException | RuntimeException e) {
            return InjectionResult.fail(InjectionStrategy.LOAD_LIBRARY,
                    "LoadLibrary 异常：" + e.getMessage(), elapsed(started), 0, e);
        }
    }

    /**
     * 在目标进程里调 LoadLibraryW。返回 null 表示成功，module[0] 是模块基址的十六进制字符串。
     *
     * <p>关键点：LoadLibraryW 的地址不能直接用本进程的地址 —— ASLR 下同 DLL 在不同进程基址不同，
     * 只有模块内 RVA 稳定，所以要用 remoteKernel32 + (本地地址 - 本地基址) 换算。</p>
     */
    private static String tryRemoteLoadLibrary(RemoteProcess process, String path, InjectionOptions options,
                                              long[] module) {
        byte[] pathBytes = (path + "\0").getBytes(StandardCharsets.UTF_16LE);
        long buffer = process.allocate(pathBytes.length, NativeApi.PAGE_READWRITE);
        if (buffer == 0) {
            return "远程分配路径缓冲失败";
        }

        try {
            if (!process.write(buffer, pathBytes, pathBytes.length)) {
                return "写路径缓冲失败";
            }

            long remoteKernel32 = process.getModuleBase("kernel32.dll");
            com.sun.jna.Pointer localKernel32 = NativeApi.kernel32().GetModuleHandleW("kernel32.dll");
            if (remoteKernel32 == 0 || NativeApi.isNull(localKernel32)) {
                return "定位 kernel32 失败";
            }

            com.sun.jna.Pointer localLoadLibrary = NativeApi.kernel32().GetProcAddress(localKernel32,
                    NativeApi.ansi("LoadLibraryW"));
            if (NativeApi.isNull(localLoadLibrary)) {
                return "找不到 LoadLibraryW";
            }

            long remoteLoadLibrary = remoteKernel32
                    + (com.sun.jna.Pointer.nativeValue(localLoadLibrary)
                    - com.sun.jna.Pointer.nativeValue(localKernel32));

            long[] value = new long[1];
            String[] invokeError = new String[1];
            if (!process.tryInvoke(remoteLoadLibrary, new long[]{buffer}, options.waitMs(), value, invokeError)) {
                return invokeError[0];
            }

            if (value[0] == 0) {
                return "LoadLibraryW 返回 0（模块被拒或依赖缺失）";
            }

            module[0] = value[0];
            return null;
        } finally {
            process.free(buffer);
        }
    }

    private static InjectionResult finishLoad(RemoteProcess process, PeImage pe, long module, String path,
                                              InjectionOptions options, long started) {
        String initExport = options.initExport();
        if (initExport != null && !initExport.isBlank()) {
            PeImage.Export export = pe.findExport(initExport);
            if (export == null) {
                return InjectionResult.fail(InjectionStrategy.LOAD_LIBRARY, "模块里没有导出 " + initExport,
                        elapsed(started), module);
            }

            long init = module + export.rva();
            long[] initResult = new long[1];
            String[] initError = new String[1];
            if (!process.tryInvoke(init, new long[0], options.waitMs(), initResult, initError)) {
                return InjectionResult.fail(InjectionStrategy.LOAD_LIBRARY,
                        "调用 " + initExport + " 失败：" + initError[0], elapsed(started), module);
            }

            options.emit(String.format("%s 返回 0x%x", initExport, initResult[0]));
            if (initResult[0] == 0) {
                return InjectionResult.fail(InjectionStrategy.LOAD_LIBRARY,
                        initExport + " 返回 0（payload 内部初始化失败）", elapsed(started), module);
            }
        }

        return InjectionResult.ok(InjectionStrategy.LOAD_LIBRARY,
                String.format("已加载：%s（模块 0x%x）", path, module), elapsed(started), module);
    }

    static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
