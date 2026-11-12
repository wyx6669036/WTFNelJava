package org.wyx6669036.wtfnel.cli;

import org.wyx6669036.wtfnel.auth.AccountStore;
import org.wyx6669036.wtfnel.auth.login.NeteaseSession;
import org.wyx6669036.wtfnel.auth.yggdrasil.Skip32;
import org.wyx6669036.wtfnel.auth.yggdrasil.YggdrasilSetup;
import org.wyx6669036.wtfnel.config.GameDirLocator;
import org.wyx6669036.wtfnel.config.ShadowConfig;
import org.wyx6669036.wtfnel.injection.InjectionRunner;
import org.wyx6669036.wtfnel.injection.InjectionService;
import org.wyx6669036.wtfnel.launch.GameRunner;
import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.util.AppPaths;
import org.wyx6669036.wtfnel.util.NativeAssets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 命令行入口。原来 WtfNel 是 WPF 界面 + 控制台替身两个程序，Java 侧合成一个：
 * 不带参数就是跑一遍完整启动流程（对应 C# 的 GameRunner）。
 *
 * <pre>
 *   java -jar WtfNelJava.jar                     跑启动流程
 *   java -jar WtfNelJava.jar login &lt;渠道&gt; &lt;账号&gt; &lt;密码&gt;   只登录，验证账号可用
 *   java -jar WtfNelJava.jar inject &lt;pid&gt; &lt;dll&gt;  只注入一个 payload
 *   java -jar WtfNelJava.jar config              打印当前生效的配置
 *   java -jar WtfNelJava.jar stub [--pid-file x] 常驻替身（对应 C# 的 WtfNel.Stub）
 * </pre>
 */
public final class Main {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Main() {
    }

    public static void main(String[] argv) throws Exception {
        List<String> args = new ArrayList<>(List.of(argv));
        String command = args.isEmpty() ? "run" : args.remove(0).toLowerCase(Locale.ROOT);

        ShadowConfig cfg = ShadowConfig.load(configPath());
        Log.setConsoleEnabled(true);
        Log.open(AppPaths.baseDirectory().resolve("logs")
                .resolve("shadow-" + LocalDateTime.now().format(STAMP) + ".log"));

        // 启动时先对齐一次 jar 里带的原生产物（内容一致就不动，锁住的运行中文件只 warn）
        NativeAssets.install(AppPaths.baseDirectory());

        try {
            switch (command) {
                case "run" -> run(cfg);
                case "login" -> login(cfg, args);
                case "inject" -> inject(args);
                case "config" -> printConfig(cfg);
                case "stub" -> stub(args);
                case "--help", "-h", "help" -> usage();
                default -> {
                    System.err.println("未知命令: " + command);
                    usage();
                    System.exit(2);
                }
            }
        } finally {
            Log.close();
        }
    }

    private static void run(ShadowConfig cfg) throws IOException {
        // 没配游戏目录就现场探测一遍，和 UI 启动时的行为一致
        if (cfg.getGameDir() == null || cfg.getGameDir().isBlank()) {
            cfg.setGameDir(GameDirLocator.resolve(cfg));
        }

        // accounts.json 里的凭据要无条件补上：shadow.json 只存 LoginChannel 时，
        // 光有渠道名没有密码会让 resolveUserToken 直接抛「需要用户名和密码」。
        applySavedAccount(cfg);
        applySavedRole(cfg);
        reuseSavedToken(cfg);

        // 先自检身份：空 --username/--uuid 会让客户端在 native 层静默崩溃，
        // 那种场面比一条配置错误难查一百倍，所以在这里就拦下来。
        try {
            GameRunner.resolveIdentity(cfg);
        } catch (IllegalStateException e) {
            System.err.println("[cli] " + e.getMessage());
            Log.error("[cli] " + e.getMessage());
            System.exit(1);
        }

        int code = GameRunner.run(cfg, () -> false);
        Log.info("[cli] 启动流程结束，返回码 " + code);
        System.exit(code);
    }

    private static void login(ShadowConfig cfg, List<String> args) throws IOException {
        if (args.size() < 3) {
            System.err.println("用法: login <netease|4399pc|4399com|cookie> <账号> <密码/sauth_json>");
            System.exit(2);
        }

        String channel = args.get(0);
        String account = args.get(1);
        String secret = args.get(2);
        Log.info("[cli] 登录渠道 " + channel + " 账号 " + account);

        NeteaseSession session = YggdrasilSetup.login(cfg, channel, account, secret, new ConsoleCaptchaPrompt());
        System.out.println("userId    = " + session.userId());
        System.out.println("userToken = " + session.userToken());
        System.out.println("account   = " + session.account());

        cfg.setLoginChannel(channel);
        cfg.setLoginAccount(account);
        cfg.setUid(session.userId());
        cfg.setUserToken(session.userToken());
        cfg.save(configPath());
        saveAccount(channel, account, secret, session.userId());
        Log.info("[cli] 已把 userId/userToken 写回 " + configPath()
                + "，账号写回 " + AccountStore.filePath());
    }

    private static void inject(List<String> args) {
        if (args.size() < 2) {
            System.err.println("用法: inject <pid> <payload.dll>");
            System.exit(2);
        }

        int pid = Integer.parseInt(args.get(0));
        String payload = args.get(1);
        var result = InjectionService.inject(pid, payload);
        System.out.println(result);
        System.exit(result.success() ? 0 : 1);
    }

    private static void printConfig(ShadowConfig cfg) {
        System.out.println("配置文件   = " + configPath());
        System.out.println("GameDir    = " + cfg.getGameDir());
        System.out.println("VersionId  = " + cfg.getVersionId() + "  McVersionId = " + cfg.getMcVersionId());
        System.out.println("LaunchJava = " + cfg.getJavaExe());
        System.out.println("Remote     = " + cfg.getRemoteHost() + ":" + cfg.getRemotePort()
                + (cfg.isUseRelay() ? "（本地转发 " + cfg.getLocalPort() + "）" : ""));
        System.out.println("桥接/认证  = " + cfg.getBridgePort() + " / " + cfg.getAuthPort());
        System.out.println("注入目标   = " + cfg.getInjectTarget() + "  策略 " + cfg.getInjectStrategy()
                + "  代理 " + cfg.isEnableProxy() + "  改机 " + cfg.isEnableHwidSpoof());
        System.out.println("账号       = " + cfg.getUserName() + " (" + cfg.getUid() + ")");
    }

    /**
     * 常驻替身进程：只为了给客户端一个看起来像 WPFLauncher.exe 的父进程。
     * 对应 C# 的 WtfNel.Stub/Program.cs，参数保持一致。
     */
    private static void stub(List<String> args) throws Exception {
        String spawnExe = null;
        String spawnArgs = null;
        String cwd = null;
        String pidFile = null;

        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--spawn" -> spawnExe = i + 1 < args.size() ? args.get(++i) : null;
                case "--args" -> spawnArgs = i + 1 < args.size() ? args.get(++i) : null;
                case "--cwd" -> cwd = i + 1 < args.size() ? args.get(++i) : null;
                case "--pid-file" -> pidFile = i + 1 < args.size() ? args.get(++i) : null;
                default -> {
                    // 未知参数忽略，保持与替身一致：多传了也不该让客户端起不来
                }
            }
        }

        if (pidFile != null) {
            try {
                Files.writeString(Path.of(pidFile), Long.toString(ProcessHandle.current().pid()),
                        StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // 写不了就不写，调用方会靠进程枚举自己找
            }
        }

        Process child = null;
        if (spawnExe != null && Files.isRegularFile(Path.of(spawnExe))) {
            ProcessBuilder builder = new ProcessBuilder(spawnExe);
            if (spawnArgs != null && !spawnArgs.isBlank()) {
                for (String piece : spawnArgs.split("\\s+")) {
                    if (!piece.isBlank()) {
                        builder.command().add(piece);
                    }
                }
            }
            builder.directory(Path.of(cwd == null ? System.getProperty("user.dir") : cwd).toFile());
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            child = builder.start();
        }

        // 主线程常驻，等 Ctrl+C 或父进程消失
        Object monitor = new Object();
        synchronized (monitor) {
            while (true) {
                monitor.wait(1000);
                if (child != null && !child.isAlive()) {
                    break;
                }
            }
        }

        if (child != null && child.isAlive()) {
            child.destroy();
        }
    }

    /** 用 accounts.json 里最后一次用的账号补上角色/uuid，让 CLI 也能直接跑起来。 */
    private static void applySavedAccount(ShadowConfig cfg) {
        AccountStore store = AccountStore.load();
        AccountStore.SavedAccount saved = store.getAccounts().stream()
                .filter(a -> a.getLastUsed() != null)
                .max((a, b) -> a.getLastUsed().compareTo(b.getLastUsed()))
                .orElse(store.getAccounts().isEmpty() ? null : store.getAccounts().get(0));
        if (saved == null) {
            Log.warn("[cli] accounts.json 里没有账号，继续用 shadow.json 的 UserName");
            return;
        }

        if (saved.getUserId() > 0) {
            cfg.setUid(saved.getUserId());
        }
        if (saved.getLastRole() != null && !saved.getLastRole().isBlank()) {
            cfg.setUserName(saved.getLastRole());
            if (cfg.getUid() > 0) {
                cfg.setUuid(Skip32.roleUuid(saved.getLastRole(), cfg.getUid()));
            }
        }

        if (saved.getChannel() != null && !saved.getChannel().isBlank()) {
            cfg.setLoginChannel(saved.getChannel());
            cfg.setLoginAccount(saved.getEmail() != null ? saved.getEmail() : saved.getName());
            cfg.setLoginPassword(saved.getPassword());
            cfg.setLoginCookie(saved.getCookie());
        }
        Log.info("[cli] 使用账号 " + saved.getName() + " 角色 " + saved.getLastRole());
    }

    private static void applySavedRole(ShadowConfig cfg) {
        if (cfg.getUserName() == null || cfg.getUserName().isBlank()) {
            // 没有角色名就没有 uuid 可推导：硬算只会得到 md5("") 派生的垃圾值
            return;
        }
        if (cfg.getUuid() != null && cfg.getUuid().length() == 32) {
            return;
        }
        if (cfg.getUid() > 0) {
            cfg.setUuid(Skip32.roleUuid(cfg.getUserName(), cfg.getUid()));
            Log.info("[cli] 由角色名推导 uuid = " + cfg.getUuid());
        }
    }

    /**
     * 上一次 {@code login} 已经把 userToken 写进 shadow.json，这里直接拿它当会话，
     * 免得每次 run 都重新登一次（没网或者没存密码时那一步必然失败）。
     * 对应 C# 界面里 AppState 调 {@code YggdrasilSetup.UseSession} 的那一手。
     */
    private static void reuseSavedToken(ShadowConfig cfg) {
        if (YggdrasilSetup.loggedIn() || cfg.getUserToken() == null || cfg.getUserToken().isBlank()) {
            return;
        }
        YggdrasilSetup.useSession(new NeteaseSession(cfg.getUid(), cfg.getUserToken().trim(),
                cfg.getLoginAccount(), cfg.getLoginChannel()));
        Log.info("[cli] 复用 shadow.json 里的 userToken（uid=" + cfg.getUid() + "）");
    }

    /** 登录成功后把账号存进 accounts.json：channel/账号/密码/userId，供下次 run 使用。 */
    private static void saveAccount(String channel, String account, String secret, int userId) {
        AccountStore store = AccountStore.load();
        AccountStore.SavedAccount saved = store.getAccounts().stream()
                .filter(a -> channel.equals(a.getChannel())
                        && account.equalsIgnoreCase(channel.equals("cookie") ? a.getName() : a.getEmail()))
                .findFirst()
                .orElseGet(() -> {
                    AccountStore.SavedAccount fresh = new AccountStore.SavedAccount();
                    store.getAccounts().add(fresh);
                    return fresh;
                });

        saved.setChannel(channel);
        saved.setName(account);
        saved.setEmail(account);
        saved.setUserId(userId);
        saved.setLastUsed(LocalDateTime.now());
        if ("cookie".equals(channel)) {
            saved.setCookie(secret);
        } else {
            saved.setPassword(secret);
        }
        store.save();
    }

    private static Path configPath() {
        return AppPaths.baseDirectory().resolve("shadow.json");
    }

    private static void usage() {
        System.out.println("""
                WtfNel（Java 移植版）

                  run                              跑完整启动流程（默认）
                  login <渠道> <账号> <密码>       只登录，成功后把 token 写回 shadow.json
                  inject <pid> <payload.dll>       把 payload 注进指定进程
                  config                           打印当前生效配置
                  stub [--pid-file x]              常驻替身进程（当客户端的父进程）

                渠道: netease | 4399pc | 4399com | cookie
                配置文件: 与本程序同目录的 shadow.json
                """);
    }
}
