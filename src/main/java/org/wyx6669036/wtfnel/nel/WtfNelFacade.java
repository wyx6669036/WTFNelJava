package org.wyx6669036.wtfnel.nel;

import org.wyx6669036.wtfnel.GameClient;
import org.wyx6669036.wtfnel.WtfNel;
import org.wyx6669036.wtfnel.auth.login.ICaptchaPrompt;
import org.wyx6669036.wtfnel.auth.login.NeteaseSession;
import org.wyx6669036.wtfnel.auth.yggdrasil.YggdrasilSetup;
import org.wyx6669036.wtfnel.config.GameDirLocator;
import org.wyx6669036.wtfnel.config.ShadowConfig;
import org.wyx6669036.wtfnel.injection.InjectionRunner;
import org.wyx6669036.wtfnel.launch.Elevation;
import org.wyx6669036.wtfnel.launch.GameRunner;
import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.util.AppPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 静态便利层：把内部包收成一组静态方法，不想持有对象的地方（脚本、单次调用、测试）用这个。
 *
 * <p>宿主程序一般更需要 {@link WtfNel}（实例门面，能同时持多个账号、按角色启动）；
 * 这个类只做一次性的「读配置 / 登录 / 跑一遍 / 挂日志」。</p>
 *
 * <p>线程约定：{@link #launch} 会阻塞到客户端退出并返回真实退出码，{@link #launchAsync}
 * 立刻返回一个 {@link GameClient} 句柄，{@link #login} 是同步 HTTP，
 * {@link #onProxyReady} 的回调发生在注入线程里。</p>
 *
 * @see WtfNel
 */
public final class WtfNelFacade {

    private WtfNelFacade() {
    }

    // ---------------------------------------------------------------- 配置

    /** 配置文件默认位置：程序目录下的 shadow.json。 */
    public static Path defaultConfigFile() {
        return AppPaths.baseDirectory().resolve("shadow.json");
    }

    /** 程序目录（shadow.json / logs / cache / LocalRuntime / payloads 都相对它）。 */
    public static Path home() {
        return AppPaths.baseDirectory();
    }

    /** 读配置；文件不存在时返回一份全默认值。 */
    public static ShadowConfig loadConfig(Path file) {
        return ShadowConfig.load(file);
    }

    /** 写配置（自动建父目录）。 */
    public static void saveConfig(ShadowConfig cfg, Path file) {
        cfg.save(file);
    }

    /** 按 {@link GameDirLocator} 的规则把 GameDir 补上，并写回配置对象。 */
    public static String ensureGameDir(ShadowConfig cfg) {
        if (cfg.getGameDir() != null && !cfg.getGameDir().isBlank()) {
            return cfg.getGameDir();
        }
        String resolved = GameDirLocator.resolve(cfg);
        cfg.setGameDir(resolved);
        return resolved;
    }

    // ---------------------------------------------------------------- 登录

    /**
     * 登录一次，拿到 userId + userToken。渠道为 netease / 4399pc / 4399com / cookie。
     * {@code captcha} 可以为 null（不处理验证码，遇到就抛异常）。
     */
    public static NeteaseSession login(ShadowConfig cfg, String channel, String account, String secret,
                                       ICaptchaPrompt captcha) throws IOException {
        return YggdrasilSetup.login(cfg, channel, account, secret, captcha);
    }

    /** 把宿主自己拿到的会话交给启动器，之后的启动不会再去登录。 */
    public static void useSession(NeteaseSession session) {
        YggdrasilSetup.useSession(session);
    }

    /** 丢掉进程内缓存的会话（换账号时用）。 */
    public static void clearSession() {
        YggdrasilSetup.clearSession();
    }

    // ---------------------------------------------------------------- 启动

    /** 跑一遍完整流程，不响应停止请求。 */
    public static int launch(ShadowConfig cfg) throws IOException {
        return GameRunner.run(cfg);
    }

    /**
     * 跑一遍完整流程。{@code stopRequested} 返回 true 时停止等待客户端（不会强杀客户端）。
     *
     * @return 客户端进程的真实退出码；中途被 {@code stopRequested} 叫停时返回 0
     */
    public static int launch(ShadowConfig cfg, BooleanSupplier stopRequested) throws IOException {
        return GameRunner.run(cfg, stopRequested);
    }

    /**
     * 与 {@link #launch(ShadowConfig)} 同一条流程，但在名为 {@code wtfnel-launch} 的后台线程里跑，
     * 调用当场返回 {@link GameClient}；要停就 {@code stop()}，要等就 {@code await()}。
     *
     * <p>同一进程同时只能有一个客户端，已经在跑时抛 {@link IllegalStateException}。</p>
     */
    public static GameClient launchAsync(ShadowConfig cfg) {
        GameClient.checkIdle();
        String role = cfg.getUserName();
        String label = role == null || role.isBlank() ? "配置 (未指定角色)" : "配置 " + role;
        return GameClient.start(new GameClient.Spec(role, cfg.getUid(), cfg.isEnableHwidSpoof(), cfg.isEnableProxy()),
                (cancelled, onClientStarted) -> GameRunner.run(cfg, cancelled, onClientStarted), label);
    }

    /** 当前进程是否提权（官方启动器是提权跑的，伪造父进程前最好先看一眼）。 */
    public static boolean isElevated() {
        return Elevation.isElevated();
    }

    // ---------------------------------------------------------------- 事件

    /** 代理 payload 起来后回调端口号，回调里抛异常只记一条警告，不影响注入流程。 */
    public static void onProxyReady(Consumer<Integer> listener) {
        InjectionRunner.addProxyReadyListener(listener);
    }

    public static void offProxyReady(Consumer<Integer> listener) {
        InjectionRunner.removeProxyReadyListener(listener);
    }

    /** payload 代理端口；还没探测到就是 0。 */
    public static int proxyPort() {
        return InjectionRunner.proxyPort();
    }

    /** 订阅日志（每个级别都会来，包括原样透传的 RAW 行）。 */
    public static void onLog(Consumer<Log.Entry> listener) {
        Log.onWritten(listener);
    }

    public static void offLog(Consumer<Log.Entry> listener) {
        Log.removeWrittenListener(listener);
    }

    /** 把日志同时写一份到文件；不调用就只有控制台 + 内存事件。 */
    public static void openLogFile(Path file) {
        Log.open(file);
    }

    public static void closeLogFile() {
        Log.close();
    }

    /** 详细日志（对应配置里的"详细日志"开关，不落盘）。 */
    public static void setVerbose(boolean value) {
        Log.setVerbose(value);
    }

    /** 宿主没有控制台时关掉 stdout 输出，日志仍然进文件与事件。 */
    public static void setConsoleEnabled(boolean value) {
        Log.setConsoleEnabled(value);
    }
}
