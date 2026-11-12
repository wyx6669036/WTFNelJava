package org.wyx6669036.wtfnel.launch;

import org.wyx6669036.wtfnel.auth.yggdrasil.AuthLibServer;
import org.wyx6669036.wtfnel.auth.yggdrasil.Skip32;
import org.wyx6669036.wtfnel.auth.yggdrasil.ModInfoProvider;
import org.wyx6669036.wtfnel.auth.yggdrasil.YggdrasilSetup;
import org.wyx6669036.wtfnel.bridge.LauncherBridge;
import org.wyx6669036.wtfnel.bridge.ServerList;
import org.wyx6669036.wtfnel.config.GameDirLocator;
import org.wyx6669036.wtfnel.config.ShadowConfig;
import org.wyx6669036.wtfnel.injection.InjectionRunner;
import org.wyx6669036.wtfnel.install.GameInstaller;
import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.proxy.GameRelay;
import org.wyx6669036.wtfnel.util.AppPaths;
import org.wyx6669036.wtfnel.util.NativeAssets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * 一条命令跑完整个启动流程：装文件 → 改身份 → 拼命令行 → 起桥接/认证 → 拉客户端 → 注入。
 *
 * <p>顺序上有几处不能调换，见各步注释。移植自 WtfNel.Core/Launch/GameRunner.cs，
 * C# 的 CancellationToken 换成 {@link BooleanSupplier}（返回 true 表示已请求停止）。</p>
 */
public final class GameRunner {

    /** 不关心取消的调用方用这个。 */
    public static final BooleanSupplier NEVER_CANCELLED = () -> false;

    private GameRunner() {
    }

    public static int run(ShadowConfig cfg) throws IOException {
        return run(cfg, NEVER_CANCELLED, null);
    }

    public static int run(ShadowConfig cfg, BooleanSupplier cancelled) throws IOException {
        return run(cfg, cancelled, null);
    }

    /**
     * @param cancelled        返回 true 表示宿主请求停止（{@link org.wyx6669036.wtfnel.GameClient#stop()} 用的就是它）
     * @param onClientStarted  客户端进程真正起来之后回调它的 pid；不需要就传 null。
     *                         回调在启动线程里执行，抛异常只记一条 warn，不影响启动。
     * @return 客户端进程的真实退出码；宿主中途 cancelled 时返回 0（客户端是被收尾逻辑强杀的，没有可信退出码）
     */
    public static int run(ShadowConfig cfg, BooleanSupplier cancelled, LongConsumer onClientStarted) throws IOException {
        BooleanSupplier ct = cancelled == null ? NEVER_CANCELLED : cancelled;

        // 先把 jar 里带的替身与 payload 同步到程序目录（内容一致就不动），
        // 这样不管是实例门面、静态门面还是 CLI 起客户端，用的都是这次打包的那份。
        NativeAssets.install(AppPaths.baseDirectory());

        // GameDir 必须在动别的之前落地：GameInstaller 的缓存与 Java 路径、ServerList、
        // 启动命令行的 workingDirectory 全由它推出来。留空时 Path.of("") 会变成
        // 「当前目录的父级」，一路算到盘根（例如 G:\ext\jre-v64-220420\jdk17\bin\javaw.exe），
        // 最后 CreateProcessW 的 lpCurrentDirectory 收到空串返回 err=123。
        // CLI 的 run 命令在里面之前也解析过一次，这里兜住所有直接调 GameRunner 的宿主。
        if (cfg.getGameDir() == null || cfg.getGameDir().isBlank()) {
            cfg.setGameDir(GameDirLocator.resolve(cfg));
        }

        String gameId = cfg.getGameIdOrDefault();
        cfg.setLauncherGameId(gameId);

        String userToken = YggdrasilSetup.resolveUserToken(cfg, null);

        int port = cfg.getBridgePort();

        // 装文件要在改身份之前：GameInstaller 的路径全由 GameDir 推出来，
        // 而 GameDir 可能是这一步才探测出来的。
        GameInstaller installer = new GameInstaller(cfg, YggdrasilSetup.createModProvider(cfg));
        if (cfg.isAutoInstall()) {
            installer.installAll(gameId, userToken);
        }

        throwIfCancelled(ct);

        Path java = installer.launchJava();
        if (java == null || !Files.isRegularFile(java)) {
            throw new IOException("找不到 java（打开 AutoInstall 可自动下载）: " + java);
        }

        Path mclRoot = installer.mclRoot();
        LauncherIdentity.ensure(cfg, mclRoot == null ? null : mclRoot.toString());

        if (cfg.isAutoConnect()) {
            resolveServer(cfg);
            Log.info("[tpl] 自动进服 -> " + cfg.getConnectHost() + ":" + cfg.getConnectPort());

            // 多人列表里要写真实地址：UseRelay 时 --quickPlayMultiplayer 走 127.0.0.1，
            // 但用户在列表里手动点的那个服务器得是能被解析的真地址。
            String listIp = cfg.getRemotePort() == 25565
                    ? cfg.getRemoteHost()
                    : cfg.getRemoteHost() + ":" + cfg.getRemotePort();
            ServerList.ensure(cfg.getGameDir(), listIp, "布吉岛服务器");
        }

        resolveIdentity(cfg);

        String raw = LaunchCommand.build(cfg, java.toString(), gameId, userToken);
        Path logs = AppPaths.baseDirectory().resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("launch-cmdline.txt"), raw, StandardCharsets.UTF_8);

        Log.info("[tpl] 角色 = " + cfg.getUserName() + "  uuid = " + cfg.getUuid() + "  gameId = " + gameId);
        Log.info("[tpl] java = " + java);
        Log.info("[tpl] 桥接端口 = " + port + "  认证端口 = " + cfg.getAuthPort()
                + "  模式 = " + (cfg.isEnableProxy() ? "代理" : "白端"));

        GameRelay relay = null;
        if (cfg.isUseRelay()) {
            relay = new GameRelay(cfg.getRemoteHost(), cfg.getRemotePort(), cfg.getLocalPort(), Log.isVerbose());
            relay.start();
            Log.info("[tpl] 转发层 127.0.0.1:" + cfg.getLocalPort() + " -> "
                    + cfg.getRemoteHost() + ":" + cfg.getRemotePort());
        }

        // 桥接要先于客户端起来：客户端一连上就会问状态，晚起会被当成超时。
        LauncherBridge bridge = new LauncherBridge(port,
                cfg.isAutoConnect() ? new LauncherBridge.Target(cfg.getRemoteHost(), cfg.getRemotePort(), "BedJsland")
                        : null);
        bridge.start();

        ModInfoProvider modProvider = YggdrasilSetup.createModProvider(cfg);
        AuthLibServer authLib = YggdrasilSetup.createServer(cfg, userToken, modProvider);
        authLib.start();

        // 进服时才需要 modInfo，这里先在后台热一遍，省掉客户端加载时的等待
        CompletableFuture<String> prefetch = modProvider.get(gameId, cfg.getUid(), userToken);
        prefetch.whenComplete((value, error) -> {
            if (error != null) {
                Throwable root = error;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                Log.warn("[tpl] 预取 modInfo 失败: " + root.getMessage());
            }
        });

        ProcessGuard guard = new ProcessGuard();
        try {
            int parent = ParentedLauncher.ensureLauncher(cfg.getLauncherPath(), cfg.getLauncherProcessName(), 30000);
            guard.track(parent, "WPFLauncher(父进程)");
            Log.info("[tpl] 父进程 pid=" + parent);

            // 子进程的 DLL 搜索路径只认自己目录和 PATH，所以必须把 natives/runtime/java
            // 三个目录顶到 PATH 前面，否则客户端会在 System.load 上炸。
            List<Path> prepend = new ArrayList<>();
            prepend.add(cfg.getRuntimeDir());
            prepend.add(cfg.getNativeDir());
            Path javaDir = java.getParent();
            if (javaDir != null) {
                prepend.add(javaDir);
            }
            ParentedLauncher.PrependedEnvironment childEnv = ParentedLauncher.buildChildEnvironment(prepend);
            childEnv.environment().put("WTFNEL_LOG_DIR", AppPaths.baseDirectory().resolve("logs").toString());
            Log.info("[tpl] 子进程 PATH 追加: " + (childEnv.prepended().isEmpty()
                    ? "(无，目录不存在)"
                    : String.join(" | ", childEnv.prepended())));

            // 用 *Tracked 版本拿回进程句柄：ProcessHandle 没有 exitValue()，
            // 只有 CreateProcessW 给的那个 hProcess 配上 WaitForSingleObject/GetExitCodeProcess
            // 才能问到真实退出码。
            ParentedLauncher.StartedProcess started = ParentedLauncher.startRawTracked(java.toString(), raw,
                    cfg.getGameDir(), parent, false, false, childEnv.environment());
            int exitCode;
            try {
                long pid = started.pid();
                guard.track(pid, "客户端");
                Log.info("[tpl] 客户端 pid=" + pid);
                notifyClientStarted(onClientStarted, pid);

                InjectionRunner.startBackground(cfg);

                exitCode = waitForExit(started, ct);
                Log.info("[tpl] 客户端已退出，退出码=" + exitCode);
            } finally {
                started.close();
            }
            return exitCode;
        } finally {
            authLib.close();
            if (relay != null) {
                relay.close();
            }
            guard.killAll("客户端退出收尾");
        }
    }

    /**
     * 角色名与 uuid 必须都有值：网易客户端会拿它们填 {@code --username} / {@code --uuid}，
     * 空值会让 {@code com.netease.mc.mod.network.common.Library.init()} 里的 native 代码
     * 访问空指针（0xc0000005，崩在 api-ms-win-crt-utility-l1-1-1.dll + ntdll），
     * 客户端一声不响就没了、连 hs_err 都不写。所以宁可在启动前就报一条能看懂的错误。
     *
     * <p>uuid 缺失但 uid 有效时按官方规则用角色名推出来
     * （{@link org.wyx6669036.wtfnel.auth.yggdrasil.Skip32#roleUuid}，与官方启动器给出的 --uuid 逐字节一致）。</p>
     */
    public static void resolveIdentity(ShadowConfig cfg) {
        String role = cfg.getUserName();
        if (role == null || role.isBlank()) {
            throw new IllegalStateException(
                    "没有角色名（UserName）：用空的 --username/--uuid 启动会让网易客户端的 native 层"
                            + "直接崩溃且不留日志。请先 login，或在 shadow.json 里填 UserName/Uuid");
        }
        cfg.setUserName(role.trim());

        String uuid = cfg.getUuid();
        if (uuid != null && uuid.trim().length() == 32) {
            cfg.setUuid(uuid.trim());
            return;
        }
        if (cfg.getUid() <= 0) {
            throw new IllegalStateException("uuid 缺失，且 uid=" + cfg.getUid()
                    + " 无法由角色名推导 uuid；请先 login 或在 shadow.json 里填 Uuid");
        }
        cfg.setUuid(Skip32.roleUuid(cfg.getUserName(), cfg.getUid()));
        Log.info("[tpl] 由角色名推导 uuid = " + cfg.getUuid());
    }

    /**
     * RemoteHost 填 auto 时问 X19 要真实地址，否则直接用配置值。
     */
    private static void resolveServer(ShadowConfig cfg) throws IOException {
        String host = cfg.getRemoteHost();
        if (host != null && !host.isBlank() && !"auto".equalsIgnoreCase(host.trim())) {
            Log.info("服务器(配置) = " + cfg.getRemoteHost() + ":" + cfg.getRemotePort());
            return;
        }

        if (cfg.getLauncherGameId() == null || cfg.getLauncherGameId().isBlank()) {
            throw new IllegalStateException(
                    "RemoteHost=auto 需要 LauncherGameId（或在 shadow.json 直接填 RemoteHost/RemotePort）");
        }

        String token = cfg.getUserToken();
        ModInfoProvider x19 = YggdrasilSetup.createModProvider(cfg);
        ModInfoProvider.ServerAddress resolved = x19.getServerAddress(cfg.getLauncherGameId(), cfg.getUid(), token);
        cfg.setRemoteHost(resolved.host());
        cfg.setRemotePort(resolved.port());
        Log.info("服务器(X19) = " + resolved.host() + ":" + resolved.port());
    }

    /**
     * 等客户端退出并返回真实退出码。分片等待（每次 500ms）是为了能及时看到宿主传来的取消标记：
     * 取消时这次启动就到此为止，客户端交给下面的 {@code guard.killAll} 收尾，所以没有可信退出码，返回 0。
     */
    private static int waitForExit(ParentedLauncher.StartedProcess started, BooleanSupplier cancelled) {
        while (true) {
            Integer code = started.waitForExit(500);
            if (code != null) {
                return code;
            }
            if (cancelled.getAsBoolean()) {
                Log.info("[tpl] 已请求停止客户端");
                return 0;
            }
            if (Thread.currentThread().isInterrupted()) {
                Log.info("[tpl] 等待客户端的线程被中断");
                return 0;
            }
        }
    }

    private static void notifyClientStarted(LongConsumer onClientStarted, long pid) {
        if (onClientStarted == null) {
            return;
        }
        try {
            onClientStarted.accept(pid);
        } catch (RuntimeException e) {
            Log.warn("[tpl] 客户端 pid 回调抛异常: " + e);
        }
    }

    private static void throwIfCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) {
            throw new java.io.InterruptedIOException("已取消");
        }
    }
}
