package org.wyx6669036.wtfnel;

import org.wyx6669036.wtfnel.auth.AccountStore;
import org.wyx6669036.wtfnel.auth.login.ICaptchaPrompt;
import org.wyx6669036.wtfnel.auth.login.NeteaseSession;
import org.wyx6669036.wtfnel.auth.yggdrasil.ModInfoProvider;
import org.wyx6669036.wtfnel.auth.yggdrasil.YggdrasilSetup;
import org.wyx6669036.wtfnel.config.ShadowConfig;
import org.wyx6669036.wtfnel.injection.InjectionRunner;
import org.wyx6669036.wtfnel.launch.Elevation;
import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.nel.WtfNelFacade;
import org.wyx6669036.wtfnel.util.AppPaths;
import org.wyx6669036.wtfnel.util.NativeAssets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 给宿主程序用的实例门面：new 一个出来就能登录、拉角色、建角色、按角色启动。
 *
 * <pre>{@code
 * WtfNel nel = new WtfNel();                                   // 读程序目录下的 shadow.json
 * NelAccount account = nel.login("4399com", "用户名", "密码");   // 或 nel.loginWithCookie(sauthJson)
 *
 * List<NelAccount.Role> roles = account.getRoles();             // 刷新角色列表
 * account.addRole("新角色");                                     // 建角色
 *
 * GameClient client = account.launchGame(uid, "角色名", true, true);  // hwidspoof / 代理 都开，立刻返回
 * System.out.println(client.state() + " pid=" + client.pid());       // 运行中 / 已退出 / 启动失败
 * // ... 宿主继续做自己的事，客户端在后台跑 ...
 * client.stop();                                                     // 要关客户端的时候
 *
 * account.logout(uid);
 * nel.logout();
 * }</pre>
 *
 * <p>{@code launchGame} 本身不阻塞：整段启动都在名为 {@code wtfnel-launch} 的后台线程里，
 * 客户端自己退出后线程走到头、句柄自动变成「已退出」（退出码也在上面）。真要等它退出才
 * 往下走的脚本用 {@code launchGameAndWait(...)}。</p>
 *
 * <p>与静态便利层 {@link WtfNelFacade} 的分工：那个是「一次性跑一遍」，这个持有配置与账号，
 * 能同时留着多个 {@link NelAccount}。</p>
 *
 * <p>三个必须知道的约束：</p>
 * <ul>
 *   <li>同一进程里只有一份「当前会话」（{@link YggdrasilSetup#useSession}），
 *       启动哪个账号就把哪个账号的 token 装进去，多账号来回切是安全的，但同时启动不行；</li>
 *   <li>一次只能跑一个客户端（桥接端口 11655 / 认证端口 11656 是固定的），
 *       第二个启动请求会直接抛 {@link IllegalStateException}；{@link NelAccount#launchGame}
 *       立刻返回 {@link GameClient}，{@link NelAccount#launchGameAndWait} 才阻塞；</li>
 *   <li>登录默认会把 uid + userToken 写回 shadow.json、把凭据记进 accounts.json（与 CLI 行为一致），
 *       不想要这两处明文落盘就用 {@link #setPersist(boolean)} 关掉。</li>
 * </ul>
 */
public final class WtfNel {

    private final Path workDir;
    private final Path configFile;
    private final ShadowConfig cfg;
    private final Map<Integer, NelAccount> accounts = new LinkedHashMap<>();

    private boolean persist = true;
    private ModInfoProvider modProvider;

    /**
     * 用程序运行目录当工作目录，读那里的 shadow.json。
     *
     * <p>「运行目录」= {@code -Dwtfnel.home} → jar 所在目录（installDist 布局退到 {@code <dist>/bin}）
     * → 当前工作目录，与 CLI（{@code cli.Main}）完全一致。</p>
     */
    public WtfNel() {
        this(null, null);
    }

    /**
     * 指定工作目录：shadow.json / accounts.json / logs / cache / LocalRuntime / payloads 与
     * {@code WPFLauncher.exe} 全都落在这个目录下（目录不存在会自动创建）。
     *
     * <p>等价于 {@code new WtfNel(workDir, null)}；不传就用程序运行目录。</p>
     */
    public WtfNel(Path workDir) {
        this(workDir, null);
    }

    /**
     * 同时指定工作目录与配置文件。
     *
     * @param workDir    工作目录；{@code null} 表示用程序运行目录（{@link #workDir()} 可查实际值）
     * @param configFile 配置文件；{@code null} 表示 {@code <工作目录>/shadow.json}
     */
    public WtfNel(Path workDir, Path configFile) {
        Path base;
        if (workDir == null) {
            AppPaths.clearBaseDirectory();
            base = AppPaths.runDirectory();
        } else {
            base = workDir.toAbsolutePath().normalize();
            AppPaths.setBaseDirectory(base);
        }
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            Log.warn("[api] 创建工作目录失败: " + base + " - " + e.getMessage());
        }
        this.workDir = base;
        this.configFile = configFile == null
                ? base.resolve("shadow.json")
                : configFile.toAbsolutePath().normalize();
        this.cfg = ShadowConfig.load(this.configFile);
        // jar 里带的替身与 payload 缺失时释放到工作目录，一个 jar 丢进空目录就能跑
        NativeAssets.install(base);
    }

    /** 配置文件默认位置：{@link AppPaths#baseDirectory()} 下的 shadow.json。 */
    public static Path defaultConfigFile() {
        return AppPaths.baseDirectory().resolve("shadow.json");
    }

    /** 这个门面用的工作目录（shadow.json / accounts.json / logs / cache / payloads 的根）。 */
    public Path workDir() {
        return workDir;
    }

    // ---------------------------------------------------------------- 配置

    /** 当前生效的配置对象（直接改它就等于改这一份配置）。 */
    public ShadowConfig config() {
        return cfg;
    }

    /** 配置文件路径。 */
    public Path configFile() {
        return configFile;
    }

    /** 把当前配置写回 {@link #configFile()}。 */
    public WtfNel save() {
        cfg.save(configFile);
        return this;
    }

    /** 登录后要不要把 uid/userToken 写回 shadow.json、把凭据记进 accounts.json（默认要）。 */
    public void setPersist(boolean value) {
        this.persist = value;
    }

    public boolean isPersist() {
        return persist;
    }

    // ---------------------------------------------------------------- 开关

    /** 代理开关（= shadow.json 的 EnableProxy：注入代理 payload）。 */
    public boolean isProxy() {
        return cfg.isEnableProxy();
    }

    public WtfNel setProxy(boolean value) {
        cfg.setEnableProxy(value);
        return this;
    }

    /** 改机开关（= shadow.json 的 EnableHwidSpoof）。 */
    public boolean isHwidSpoof() {
        return cfg.isEnableHwidSpoof();
    }

    public WtfNel setHwidSpoof(boolean value) {
        cfg.setEnableHwidSpoof(value);
        return this;
    }

    /** payload 代理端口；还没探测到就是 0。 */
    public int proxyPort() {
        return InjectionRunner.proxyPort();
    }

    /** 代理 payload 起来后回调端口号；回调里抛异常只记一条警告。 */
    public WtfNel onProxyReady(Consumer<Integer> listener) {
        InjectionRunner.addProxyReadyListener(listener);
        return this;
    }

    public WtfNel offProxyReady(Consumer<Integer> listener) {
        InjectionRunner.removeProxyReadyListener(listener);
        return this;
    }

    /** 订阅日志（和 CLI 用的是同一套）。 */
    public WtfNel onLog(Consumer<Log.Entry> listener) {
        Log.onWritten(listener);
        return this;
    }

    public WtfNel offLog(Consumer<Log.Entry> listener) {
        Log.removeWrittenListener(listener);
        return this;
    }

    /** 当前进程是否提权。 */
    public boolean isElevated() {
        return Elevation.isElevated();
    }

    // ---------------------------------------------------------------- 登录

    /**
     * 登录。渠道：netease（邮箱+密码）/ 4399pc / 4399com（4399 用户名+密码）/ cookie（sauth_json）。
     *
     * @throws IOException              网络或接口失败
     * @throws IllegalArgumentException 渠道不认识、或者凭据为空
     */
    public NelAccount login(String channel, String account, String secret) throws IOException {
        return login(channel, account, secret, null);
    }

    /**
     * 只用一份凭据登录的便捷重载：cookie 渠道（sauth_json）用它就够了，
     * 账号名由 sauth_json 自己带出来，不用另外传。
     *
     * <p>netease / 4399pc / 4399com 需要账号 + 密码两样，请用三参数的重载。</p>
     */
    public NelAccount login(String channel, String secret) throws IOException {
        return login(channel, null, secret, null);
    }

    /** {@code login("cookie", sauthJson)} 的自解释写法。 */
    public NelAccount loginWithCookie(String sauthJson) throws IOException {
        return login("cookie", sauthJson);
    }

    /**
     * 同上，遇到 4399 验证码时用 {@code captcha} 应答；传 null 表示不处理，遇到验证码直接抛异常。
     */
    public NelAccount login(String channel, String account, String secret, ICaptchaPrompt captcha)
            throws IOException {
        String effective = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        Log.info("[api] 登录 channel=" + effective + " account=" + account);

        NeteaseSession session = YggdrasilSetup.login(cfg, effective, account, secret, captcha);
        return adopt(session, secret, captcha);
    }

    /**
     * 用 accounts.json 里最后一次用的那个账号登录（凭据就是文件里存的那份）。
     *
     * @throws IllegalStateException accounts.json 里没有账号
     */
    public NelAccount loginSaved() throws IOException {
        List<AccountStore.SavedAccount> saved = AccountStore.load().ordered();
        if (saved.isEmpty()) {
            throw new IllegalStateException("accounts.json 里没有账号，先用 login(...) 登一次");
        }
        AccountStore.SavedAccount entry = saved.get(0);
        NelAccount account = login(entry.getChannel(), entry.getEmail().isBlank() ? entry.getName()
                : entry.getEmail(), entry.secret());
        if (entry.getLastRole() != null && !entry.getLastRole().isBlank()) {
            Log.info("[api] accounts.json 里记着的角色 = " + entry.getLastRole());
        }
        return account;
    }

    /** 把宿主自己拿到的会话交给门面，之后的启动不会再去登录。 */
    public NelAccount useSession(NeteaseSession session) {
        if (session == null) {
            throw new IllegalArgumentException("session 不能为 null");
        }
        YggdrasilSetup.useSession(session);
        return adopt(session, "", null);
    }

    /** 本次进程里登录过的账号（按登录先后）。 */
    public List<NelAccount> accounts() {
        return new ArrayList<>(accounts.values());
    }

    // ---------------------------------------------------------------- 登出

    /** 登出全部账号：丢掉会话，代码不会再拿旧 token 去启动。 */
    public void logout() {
        for (NelAccount account : accounts.values()) {
            account.markLoggedOut();
        }
        accounts.clear();
        YggdrasilSetup.clearSession();
        Log.info("[api] 已登出全部账号");
    }

    /** 当前正在后台跑的那个客户端（没有就是 null），等价于 {@link GameClient#current()}。 */
    public GameClient currentLaunch() {
        return GameClient.current();
    }

    /** 某个 uid 是否还在登录态。 */
    public boolean isLoggedIn(int userId) {
        NelAccount account = accounts.get(userId);
        return account != null && !account.isLoggedOut();
    }

    // ---------------------------------------------------------------- 内部

    private NelAccount adopt(NeteaseSession session, String secret, ICaptchaPrompt captcha) {
        if (cfg.getLoginAccount() == null || cfg.getLoginAccount().isBlank()) {
            cfg.setLoginAccount(session.account());
        }
        cfg.setUid(session.userId());
        cfg.setUserToken(session.userToken());

        NelAccount account = new NelAccount(this, session, secret == null ? "" : secret, captcha);
        accounts.put(session.userId(), account);

        if (persist) {
            try {
                cfg.save(configFile);
            } catch (RuntimeException e) {
                Log.warn("[api] 写回 " + configFile + " 失败: " + e.getMessage());
            }
            if (!secret.isBlank()) {
                try {
                    AccountStore.remember(session.channel(), session.account(), secret, session.userId());
                } catch (RuntimeException e) {
                    Log.warn("[api] 写 accounts.json 失败: " + e.getMessage());
                }
            }
        }
        return account;
    }

    void forget(NelAccount account) {
        accounts.remove(account.userId(), account);
        if (!accounts.containsValue(account)) {
            YggdrasilSetup.clearSession();
        }
    }

    void rememberRole(NelAccount account, String role) {
        if (!persist) {
            return;
        }
        try {
            AccountStore.rememberRole(account.channel(), account.account(), role);
        } catch (RuntimeException e) {
            Log.warn("[api] 记角色到 accounts.json 失败: " + e.getMessage());
        }
    }

    /** 角色/建角色要用的 X19 客户端，整个门面共用一个（它自己带缓存）。 */
    ModInfoProvider mods() {
        if (modProvider == null) {
            modProvider = YggdrasilSetup.createModProvider(cfg);
        }
        return modProvider;
    }
}
