package org.wyx6669036.wtfnel.auth.yggdrasil;

import org.wyx6669036.wtfnel.auth.login.ICaptchaPrompt;
import org.wyx6669036.wtfnel.auth.login.NeteaseLogin;
import org.wyx6669036.wtfnel.auth.login.NeteaseSession;
import org.wyx6669036.wtfnel.config.ShadowConfig;
import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.util.AppPaths;
import org.wyx6669036.wtfnel.util.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assembles the scattered config/template values into the objects Yggdrasil authentication needs.
 *
 * <p>Port of WtfNel.Core/Auth/Yggdrasil/YggdrasilSetup.cs. The C# static class with a mutable
 * session becomes a singleton; {@code session} is the process-wide live login.</p>
 */
public final class YggdrasilSetup {

    /** bootstrap / dat file md5 by game version (from AlaneNEL's Md5Mapping). */
    private static final Map<String, String[]> MD5_MAP = Map.of(
            "1.7.10", new String[]{"A895FE657915D58F55919CEACD30209D", "538D33D5F35EF01736EDA30F94C61DF6"},
            "1.8.9", new String[]{"A895FE657915D58F55919CEACD30209D", "0CF2074AA7D4B543E35A3D6BB57AF861"},
            "1.12.2", new String[]{"A895FE657915D58F55919CEACD30209D", "51581ADD89B8AC5A0D8CCDD0E33EE1DE"},
            "1.16", new String[]{"7B101583C3965371B89A3C9115B27526", "B0712F34B0A584D05D9D29FA68759E29"},
            "1.18", new String[]{"C3BD2115F23F6FE4B2ADCC7FC4DEFFEA", "56677A2BB31E18246FA241FB02E16D0E"},
            "1.20", new String[]{"2A7A476411A1687A56DC6848829C1AE4", "D285CBF97D9BA30D3C445DBF1C342634"},
            "1.21", new String[]{"684528BF492A84489F825F5599B3E1C6", "574033E7E4841D8AC4C14D7FA5E05337"});

    private static final Pattern TOKEN_PATTERN = Pattern.compile("-DToken=([0-9A-Fa-f]+)");
    private static final Pattern GAME_ID_PATTERN = Pattern.compile("-DlauncherGameId=(\\d+)");

    private static volatile NeteaseSession session;

    private YggdrasilSetup() {
    }

    public static String launcherVersion(ShadowConfig cfg) {
        String value = cfg.getYggdrasilLauncherVersion();
        return isBlank(value) ? cfg.getLauncherVersion() : value;
    }

    /**
     * userToken source priority: live login (when LoginChannel is set) → config UserToken → command-line -DToken.
     * A successful login also overwrites cfg.Uid / cfg.UserToken; a process logs in at most once.
     */
    public static String resolveUserToken(ShadowConfig cfg, String commandLine) throws IOException {
        NeteaseSession current = session;
        if (current != null || !isBlank(cfg.getLoginChannel())) {
            if (current == null) {
                current = login(cfg);
                session = current;
            }
            cfg.setUid(current.userId());
            cfg.setUserToken(current.userToken());
            return current.userToken();
        }
        if (!isBlank(cfg.getUserToken())) {
            return cfg.getUserToken().trim();
        }
        Matcher m = commandLine == null ? null : TOKEN_PATTERN.matcher(commandLine);
        if (m != null && m.find()) {
            return LaunchToken.decrypt(m.group(1));
        }
        throw new IllegalStateException("没有 userToken：在 shadow.json 填 UserToken，或保证模板里带 -DToken");
    }

    /** Whether a live login was used (the template then rewrites userId/uid accordingly). */
    public static boolean loggedIn() {
        return session != null;
    }

    /** A session already logged in from the host program; later resolveUserToken reuses it. */
    public static void useSession(NeteaseSession value) {
        session = value;
    }

    /** Forgets the cached session (tests / switching accounts). */
    public static void clearSession() {
        session = null;
    }

    private static NeteaseSession login(ShadowConfig cfg) throws IOException {
        String channel = cfg.getLoginChannel() == null ? "" : cfg.getLoginChannel().trim().toLowerCase(java.util.Locale.ROOT);
        String secret = "cookie".equals(channel) ? cfg.getLoginCookie() : cfg.getLoginPassword();
        if ("cookie".equals(channel) && isBlank(secret)) {
            Path cookieFile = AppPaths.baseDirectory().resolve("cookie.txt");
            if (Files.isRegularFile(cookieFile)) {
                secret = Text.readUtf8(cookieFile);
            }
        }
        return login(cfg, channel, cfg.getLoginAccount(), secret, null);
    }

    /** Logs in by channel: netease = email + password, 4399pc / 4399com = 4399 username + password, cookie = sauth_json. */
    public static NeteaseSession login(ShadowConfig cfg, String channel, String account, String secret,
                                       ICaptchaPrompt captcha) throws IOException {
        String effective = channel == null ? "" : channel.trim().toLowerCase(java.util.Locale.ROOT);
        Log.info("[login] 实时登录 channel=" + effective);
        try (NeteaseLogin login = new NeteaseLogin(launcherVersion(cfg),
                AppPaths.baseDirectory().resolve("cache").resolve("login"))) {
            switch (effective) {
                case "netease" -> {
                    requireCredentials(account, secret, "网易邮箱登录需要邮箱和密码");
                    return login.loginWithEmail(account.trim(), secret);
                }
                case "4399pc" -> {
                    requireCredentials(account, secret, "4399 登录需要用户名和密码");
                    return login.loginWith4399(account.trim(), secret, captcha);
                }
                case "4399com" -> {
                    requireCredentials(account, secret, "4399 登录需要用户名和密码");
                    return login.loginWith4399Com(account.trim(), secret, captcha);
                }
                case "cookie" -> {
                    if (isBlank(secret)) {
                        throw new IllegalArgumentException("Cookie 登录需要 sauth_json");
                    }
                    return login.loginWithCookie(secret);
                }
                default -> throw new IllegalArgumentException("不支持的登录渠道: " + effective
                        + "（可用 netease / 4399pc / 4399com / cookie）");
            }
        }
    }

    public static String templateGameId(String commandLine) {
        Matcher m = commandLine == null ? null : GAME_ID_PATTERN.matcher(commandLine);
        return m != null && m.find() ? m.group(1) : null;
    }

    public static AuthLibServer createServer(ShadowConfig cfg, String userToken, ModInfoProvider mods) throws IOException {
        String launcherVersion = launcherVersion(cfg);
        String[] md5 = resolveMd5(cfg);
        YggdrasilClient client = new YggdrasilClient(
                new YggdrasilData(launcherVersion, cfg.getYggdrasilChannel(), cfg.getCrcSalt()),
                cfg.getYggdrasilServer());
        ModInfoProvider provider = mods == null ? createModProvider(cfg) : mods;

        Log.info("[ygg] launcherVersion=" + launcherVersion + " gameVersion=" + cfg.getYggGameVersion()
                + " crcSalt=" + cfg.getCrcSalt().substring(0, Math.min(6, cfg.getCrcSalt().length())) + "…");

        return new AuthLibServer(cfg.getAuthPort(), client, (gameId, userId) -> {
            GameProfile profile = new GameProfile();
            profile.gameId = gameId;
            profile.gameVersion = cfg.getYggGameVersion();
            profile.bootstrapMd5 = md5[0];
            profile.datFileMd5 = md5[1];
            profile.modInfo = provider.getBlocking(gameId, userId, userToken);
            profile.userId = userId;
            profile.userToken = userToken;
            return profile;
        });
    }

    public static ModInfoProvider createModProvider(ShadowConfig cfg) {
        return new ModInfoProvider(launcherVersion(cfg), cfg.getMcVersionId(), cfg.getGameType(),
                AppPaths.baseDirectory().resolve("cache").resolve("mods"));
    }

    private static String[] resolveMd5(ShadowConfig cfg) {
        if (!isBlank(cfg.getBootstrapMd5()) && !isBlank(cfg.getDatFileMd5())) {
            return new String[]{cfg.getBootstrapMd5(), cfg.getDatFileMd5()};
        }
        String[] pair = MD5_MAP.get(cfg.getYggGameVersion());
        if (pair != null) {
            return pair;
        }
        throw new IllegalStateException("没有 " + cfg.getYggGameVersion()
                + " 的 bootstrap/dat md5，请在 shadow.json 里填 BootstrapMd5/DatFileMd5");
    }

    private static void requireCredentials(String account, String secret, String message) {
        if (isBlank(account) || secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
