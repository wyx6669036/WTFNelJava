package org.wyx6669036.wtfnel;

import org.wyx6669036.wtfnel.auth.login.ICaptchaPrompt;
import org.wyx6669036.wtfnel.auth.login.NeteaseSession;
import org.wyx6669036.wtfnel.auth.yggdrasil.Skip32;
import org.wyx6669036.wtfnel.auth.yggdrasil.YggdrasilSetup;
import org.wyx6669036.wtfnel.config.ShadowConfig;
import org.wyx6669036.wtfnel.launch.GameRunner;
import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.nel.WtfNelFacade;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个登录成功的账号句柄。角色（子账号）都在这个对象上操作，用完 {@link #logout} 收走。
 *
 * <pre>{@code
 * NelAccount account = nel.login("4399com", "用户名", "密码");
 * for (NelAccount.Role role : account.getRoles()) { ... }
 * GameClient client = account.launchGame(account.userId(), "角色名", true, true);
 * }</pre>
 *
 * <p>角色列表是真去 X19 查的（{@code /game-character/query/user-game-characters}），
 * 建角色是 {@code POST /game-character}；两者都要网。</p>
 *
 * <p>{@link #launchGame} <b>不阻塞</b>：它把整段启动丢到后台线程，立刻返回一个
 * {@link GameClient} 句柄（状态、pid、退出码、两个开关都在上面），要关客户端就
 * {@link GameClient#stop()}。真要等客户端退出才往下走的场景（命令行/脚本）用
 * {@link #launchGameAndWait}。</p>
 *
 * <p>调用 {@code launchGame} 时会同步做完「不碰网络、不阻塞」的那部分准备（写配置、自检身份），
 * 所以没角色名、账号已登出、已经有一个客户端在跑这三类错误是<b>调用当场</b>抛出的；
 * 之后网络失败、找不到 java、注入失败之类只体现在返回对象的 {@link GameClient#failure()} 上。</p>
 */
public final class NelAccount {

    /** 一个游戏角色。uuid 是官方规则推出来的（{@link Skip32#roleUuid}），与 --uuid 逐字节一致。 */
    public record Role(String name, String uuid, int userId) {
    }

    private final WtfNel owner;
    private final NeteaseSession session;
    private final String secret;
    private final ICaptchaPrompt captcha;

    private volatile boolean loggedOut;

    NelAccount(WtfNel owner, NeteaseSession session, String secret, ICaptchaPrompt captcha) {
        this.owner = owner;
        this.session = session;
        this.secret = secret;
        this.captcha = captcha;
    }

    // ---------------------------------------------------------------- 身份

    public int userId() {
        return session.userId();
    }

    public String userToken() {
        return session.userToken();
    }

    /** 登录用的账号（邮箱 / 4399 用户名 / cookie 账号名）。 */
    public String account() {
        return session.account();
    }

    /** netease / 4399pc / 4399com / cookie。 */
    public String channel() {
        return session.channel();
    }

    public boolean isLoggedOut() {
        return loggedOut;
    }

    /** 底层会话。 */
    public NeteaseSession session() {
        return session;
    }

    /** 这个账号对应的配置对象（改它就是改所属 {@link WtfNel} 的那一份）。 */
    public ShadowConfig config() {
        return owner.config();
    }

    // ---------------------------------------------------------------- 角色

    /** 刷新角色列表（每次都真去问 X19）。 */
    public List<Role> getRoles() throws IOException {
        requireLive();
        String gameId = config().getGameIdOrDefault();
        List<String> names = owner.mods().queryRoles(gameId, userId(), userToken());
        List<Role> roles = new ArrayList<>(names.size());
        for (String name : names) {
            roles.add(new Role(name, Skip32.roleUuid(name, userId()), userId()));
        }
        Log.info("[api] 账号 " + account() + " 有 " + roles.size() + " 个角色");
        return roles;
    }

    /** 刷新角色列表的别名，读起来更顺。 */
    public List<Role> refreshRoles() throws IOException {
        return getRoles();
    }

    /** 排在最前面的那个角色；一个都没有就返回 null。 */
    public Role getRole() throws IOException {
        List<Role> roles = getRoles();
        return roles.isEmpty() ? null : roles.get(0);
    }

    /** 按名字取角色；找不到返回 null。 */
    public Role getRole(String name) throws IOException {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Role role : getRoles()) {
            if (role.name().equals(name.trim())) {
                return role;
            }
        }
        return null;
    }

    /** 建一个新角色（重名等失败会以 IllegalStateException 抛出，原因在消息里）。 */
    public Role addRole(String name) throws IOException {
        return addRole(userId(), name);
    }

    /** 建角色，显式指定 uid（与 {@link #userId()} 不同时以传进来的为准）。 */
    public Role addRole(int uid, String name) throws IOException {
        requireLive();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("角色名不能为空");
        }
        String role = name.trim();
        Log.info("[api] 创建角色 " + role + " (uid=" + uid + ")");
        owner.mods().createRole(config().getGameIdOrDefault(), uid, userToken(), role);
        return new Role(role, Skip32.roleUuid(role, uid), uid);
    }

    // ---------------------------------------------------------------- 启动

    /** 用配置里的代理 / 改机开关，以某个角色启动客户端（不阻塞）。 */
    public GameClient launchGame(String role) throws IOException {
        ShadowConfig cfg = config();
        return launchGame(userId(), role, cfg.isEnableHwidSpoof(), cfg.isEnableProxy());
    }

    /** 用某个角色启动客户端，代理 / 改机按配置走（不阻塞）。 */
    public GameClient launchGame(int uid, String role) throws IOException {
        ShadowConfig cfg = config();
        return launchGame(uid, role, cfg.isEnableHwidSpoof(), cfg.isEnableProxy());
    }

    /**
     * 以指定角色启动客户端，<b>立刻返回</b>一个 {@link GameClient} 句柄：整段启动
     * （装文件 → 起桥接/认证 → 拉客户端 → 注入 → 等退出）都在名为 {@code wtfnel-launch}
     * 的后台线程里跑，调用方该干什么干什么。
     *
     * <pre>{@code
     * GameClient client = account.launchGame(account.userId(), "角色名", true, false);
     * System.out.println(client.state() + " pid=" + client.pid());
     * // ... 宿主继续跑 ...
     * client.stop();   // 要关客户端的时候
     * }</pre>
     *
     * <p>客户端自己退出后后台线程走到头，句柄自动变成 {@link GameClient.State#EXITED}
     * （失败则是 {@code FAILED}），宿主不需要任何收尾调用。</p>
     *
     * <p>本方法只做「不碰网络、不阻塞」的准备（写配置、自检身份），所以下面这些异常是
     * 调用当场抛的；后面网络/装文件/注入的失败只体现在 {@link GameClient#failure()} 上。</p>
     *
     * @param uid        账号 id（一般就是 {@link #userId()}）
     * @param role       角色名，必须有值：空的 --username 会让客户端在 native 层静默崩溃
     * @param hwidSpoof  是否注入改机 payload
     * @param proxy      是否注入代理 payload
     * @return 客户端句柄（同一个 JVM 里同时只可能有一个还活着的）
     * @throws IllegalStateException    账号已登出，或已经有一个客户端在跑
     * @throws IllegalArgumentException 角色名为空
     */
    public GameClient launchGame(int uid, String role, boolean hwidSpoof, boolean proxy) throws IOException {
        GameClient.checkIdle();
        ShadowConfig cfg = prepareLaunch(uid, role, hwidSpoof, proxy);
        return GameClient.start(new GameClient.Spec(cfg.getUserName(), uid, hwidSpoof, proxy),
                (cancelled, onClientStarted) -> GameRunner.run(cfg, cancelled, onClientStarted));
    }

    /**
     * 与 {@link #launchGame(int, String, boolean, boolean)} 同一条启动路径，但
     * <b>阻塞到客户端退出</b>并返回它的真实退出码。命令行、脚本这类「跑完再往下走」的宿主用这个。
     */
    public int launchGameAndWait(int uid, String role, boolean hwidSpoof, boolean proxy) throws IOException {
        return launchGame(uid, role, hwidSpoof, proxy).await();
    }

    /** 等同 {@code launchGameAndWait(userId(), role, 配置里的改机, 配置里的代理)}。 */
    public int launchGameAndWait(String role) throws IOException {
        ShadowConfig cfg = config();
        return launchGameAndWait(userId(), role, cfg.isEnableHwidSpoof(), cfg.isEnableProxy());
    }

    /** 等同 {@code launchGameAndWait(uid, role, 配置里的改机, 配置里的代理)}。 */
    public int launchGameAndWait(int uid, String role) throws IOException {
        ShadowConfig cfg = config();
        return launchGameAndWait(uid, role, cfg.isEnableHwidSpoof(), cfg.isEnableProxy());
    }

    /**
     * @deprecated 现在 {@link #launchGame} 本身就是不阻塞的并返回 {@link GameClient}，
     *             这个方法只是它的别名，留着是为了不破坏已经写好的调用方。
     */
    @Deprecated
    public GameClient launchGameAsync(String role) {
        try {
            return launchGame(role);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * @deprecated 用 {@link #launchGame(int, String)}，它已经不阻塞了。
     */
    @Deprecated
    public GameClient launchGameAsync(int uid, String role) {
        try {
            return launchGame(uid, role);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * @deprecated 用 {@link #launchGame(int, String, boolean, boolean)}，它已经不阻塞了。
     */
    @Deprecated
    public GameClient launchGameAsync(int uid, String role, boolean hwidSpoof, boolean proxy) {
        try {
            return launchGame(uid, role, hwidSpoof, proxy);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * 同步与异步共用的准备：写配置、自检身份、记住角色。不碰网络、不阻塞。
     */
    private ShadowConfig prepareLaunch(int uid, String role, boolean hwidSpoof, boolean proxy) {
        requireLive();
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("角色名不能为空：空的 --username/--uuid 会让客户端 native 层直接崩溃");
        }

        ShadowConfig cfg = config();
        // 同一进程只有一份会话：启动前把本账号的 token 装回去（多账号来回切是安全的）
        YggdrasilSetup.useSession(session);

        cfg.setUid(uid);
        cfg.setUserName(role.trim());
        cfg.setUuid(Skip32.roleUuid(role.trim(), uid));
        cfg.setEnableHwidSpoof(hwidSpoof);
        cfg.setEnableProxy(proxy);
        cfg.setLoginChannel(channel());
        cfg.setLoginAccount(account());
        cfg.setUserToken(userToken());

        // GameDir 留空会让 GameInstaller 的路径一路算到盘根，最后 CreateProcessW 拿到空的
        // lpCurrentDirectory 而报 err=123。GameRunner 里也兜了一层，这里先解析是为了让
        // 落盘的 shadow.json 里带上真正的目录，而不是留一个空串。
        WtfNelFacade.ensureGameDir(cfg);

        Log.info("[api] 以角色 " + role + " 启动（uid=" + uid + "，代理=" + proxy + "，改机=" + hwidSpoof + "）");
        owner.rememberRole(this, role.trim());

        if (owner.isPersist()) {
            try {
                cfg.save(owner.configFile());
            } catch (RuntimeException e) {
                Log.warn("[api] 写回配置失败: " + e.getMessage());
            }
        }

        // 身份自检放在最前面：缺角色/缺 uid 时给一条能看懂的错误，而不是让客户端静默消失
        GameRunner.resolveIdentity(cfg);
        return cfg;
    }

    // ---------------------------------------------------------------- 登出

    /** 登出这个账号（只影响本进程里的会话与门面里的登记）。 */
    public void logout(int uid) {
        loggedOut = true;
        owner.forget(this);
        Log.info("[api] 已登出 uid=" + uid + "（账号 " + account() + "）");
    }

    /** 等同 {@code logout(userId())}。 */
    public void logout() {
        logout(userId());
    }

    void markLoggedOut() {
        this.loggedOut = true;
    }

    @Override
    public String toString() {
        return "NelAccount[" + channel() + " " + account() + " uid=" + userId() + "]";
    }

    private void requireLive() {
        if (loggedOut) {
            throw new IllegalStateException("账号已登出，请重新 login");
        }
    }
}
