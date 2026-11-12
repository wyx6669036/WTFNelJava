package org.wyx6669036.wtfnel.auth.login;

import org.wyx6669036.wtfnel.auth.yggdrasil.HttpToken;
import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.util.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live login, ported from FandNEL (WPFLauncher.LoginWithCookieAsync / MPay.LoginWithEmailAsync):
 * NetEase email → MPay device registration → email + md5(password) login → build sauth_json;
 * cookie → use an existing sauth_json directly (non-NetEase channels first go through mgbsdk uni_sauth)
 * → x19 /login-otp → /authentication-otp (encrypted body) → interconn login-start.
 *
 * <p>Port of WtfNel.Core/Auth/Login/NeteaseLogin.cs.</p>
 */
public final class NeteaseLogin implements AutoCloseable {

    private static final String MPAY_GAME_ID = "aecfrxodyqaaaajp-g-x19";
    private static final String DEFAULT_CORE_HOST = "https://x19obtcore.nie.netease.com:8443";
    private static final String DEFAULT_MPAY_HOST = "https://service.mkey.163.com";
    private static final String DEFAULT_MGBSDK_HOST = "https://mgbsdk.matrix.netease.com";
    private static final String DEFAULT_WHOAMI_HOST = "https://whoami.nie.netease.com";
    private static final String DEFAULT_NSTOOL_HOST = "http://nstool.netease.com";
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Where the login talks to. All defaults point at the live NetEase hosts; a test (or a mirror)
     * can swap them for a local server.
     */
    record Endpoints(String coreHost, String mpayHost, String mgbsdkHost, String whoamiHost, String nstoolHost) {

        static Endpoints defaults() {
            return new Endpoints(DEFAULT_CORE_HOST, DEFAULT_MPAY_HOST, DEFAULT_MGBSDK_HOST,
                    DEFAULT_WHOAMI_HOST, DEFAULT_NSTOOL_HOST);
        }
    }

    private final String launcherVersion;
    private final Path stateDir;
    private final HttpClient http;
    private final Endpoints endpoints;

    public NeteaseLogin(String launcherVersion, Path stateDir) throws IOException {
        this(launcherVersion, stateDir, Endpoints.defaults());
    }

    NeteaseLogin(String launcherVersion, Path stateDir, Endpoints endpoints) throws IOException {
        this.launcherVersion = launcherVersion;
        this.stateDir = stateDir;
        this.endpoints = endpoints == null ? Endpoints.defaults() : endpoints;
        Files.createDirectories(stateDir);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ------------------------------------------------------------------ channels

    public NeteaseSession loginWithEmail(String email, String password) throws IOException {
        Device device = loadOrCreateDevice();
        String unique = loadOrCreateUnique();
        JsonNode user = mpayLogin(email, password, unique, device);

        ObjectNode sauth = Json.mapper().createObjectNode();
        sauth.put("gameid", "x19");
        sauth.put("login_channel", "netease");
        sauth.put("app_channel", "netease");
        sauth.put("platform", "pc");
        sauth.put("sdkuid", Json.str(user, "id"));
        sauth.put("sessionid", Json.str(user, "token"));
        sauth.put("sdk_version", "4.2.0");
        sauth.put("udid", UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT));
        sauth.put("deviceid", device.id());
        sauth.put("aim_info", aimInfo());
        return loginWithCookie(Json.write(sauth));
    }

    /** 4399 PC channel: ptlogin username + password → 4399 SDK credentials → sauth_json → cookie login. */
    public NeteaseSession loginWith4399(String username, String password, ICaptchaPrompt captcha) throws IOException {
        Pc4399Login.Credentials cred;
        try (Pc4399Login pc = new Pc4399Login(captcha)) {
            cred = pc.login(username, password);
        }

        String deviceId = loadOrCreateUnique();
        ObjectNode sauth = Json.mapper().createObjectNode();
        sauth.put("timestamp", cred.time());
        sauth.put("userid", cred.username());
        sauth.put("realname", "{\"realname_type\":\"0\"}");
        sauth.put("gameid", "x19");
        sauth.put("login_channel", "4399pc");
        sauth.put("app_channel", "4399pc");
        sauth.put("platform", "pc");
        sauth.put("sdkuid", cred.uid());
        sauth.put("sessionid", cred.token());
        sauth.put("sdk_version", "1.0.0");
        sauth.put("udid", randomHex(8).toLowerCase(java.util.Locale.ROOT));
        sauth.put("deviceid", deviceId);
        sauth.put("aim_info", aimInfo());
        sauth.put("client_login_sn", deviceId);
        sauth.put("gas_token", "");
        sauth.put("source_platform", "pc");
        sauth.put("ip", gatewayIp());
        return loginWithCookie(Json.write(sauth));
    }

    /** 4399com channel: 4399 mobile SDK OAuth (captchas go to {@code captcha}) → sauth_json → cookie login. */
    public NeteaseSession loginWith4399Com(String username, String password, ICaptchaPrompt captcha) throws IOException {
        Com4399Login.Credentials cred;
        try (Com4399Login com = new Com4399Login(stateDir, captcha)) {
            cred = com.login(username, password);
        }

        String deviceId = UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        ObjectNode sauth = Json.mapper().createObjectNode();
        sauth.put("timestamp", "");
        sauth.put("userid", "");
        sauth.put("realname", "{\"realname_type\":\"0\"}");
        sauth.put("gameid", "x19");
        sauth.put("login_channel", "4399com");
        sauth.put("app_channel", "4399com");
        sauth.put("platform", "ad");
        sauth.put("sdkuid", cred.uid());
        sauth.put("sessionid", cred.state());
        sauth.put("sdk_version", "1.0.0");
        sauth.put("udid", randomHex(8).toLowerCase(java.util.Locale.ROOT));
        sauth.put("deviceid", deviceId);
        sauth.put("aim_info", aimInfo());
        sauth.put("client_login_sn", deviceId);
        sauth.put("gas_token", "");
        sauth.put("source_platform", "ad");
        sauth.put("ip", gatewayIp());
        return loginWithCookie(Json.write(sauth));
    }

    /** The cookie may be the sauth_json itself or a {"sauth_json":"..."} wrapper. */
    public NeteaseSession loginWithCookie(String cookie) throws IOException {
        String sauthJson = unwrapCookie(cookie);
        JsonNode sauthDoc = Json.readTree(sauthJson);
        String channel = sauthDoc.path("login_channel").isTextual() ? sauthDoc.path("login_channel").asText() : "netease";
        String appChannel = sauthDoc.path("app_channel").isTextual() ? sauthDoc.path("app_channel").asText() : channel;

        if (!"netease".equals(channel)) {
            uniSauth(sauthJson);
        }

        ObjectNode request = Json.mapper().createObjectNode();
        request.put("sauth_json", sauthJson);

        JsonNode otp = entityOf(Json.readTree(postJson(endpoints.coreHost() + "/login-otp", Json.write(request))), "login-otp");
        String aid = Json.str(otp, "aid").replace("\"", "");
        String otpToken = Json.str(otp, "otp_token");

        String suffix = randomHex(2);
        ObjectNode saData = Json.mapper().createObjectNode();
        saData.put("os_name", "windows");
        saData.put("os_ver", "Microsoft Windows 11 专业版");
        saData.put("mac_addr", randomMac(""));
        saData.put("udid", "0000000000000000" + suffix);
        saData.put("app_ver", launcherVersion);
        saData.put("sdk_ver", "");
        saData.put("network", "");
        saData.put("disk", suffix);
        saData.put("is64bit", "1");
        saData.put("video_card1", "Microsoft Hyper-V 视频");
        saData.put("video_card2", "Microsoft Remote Display Adapter");
        saData.put("video_card3", "");
        saData.put("video_card4", "");
        saData.put("launcher_type", "PC_java");
        saData.put("pay_channel", appChannel);
        saData.put("dotnet_ver", "4.8.0");
        saData.put("cpu_type", "Intel(R) Core(TM) i9-14900KF");
        saData.put("ram_size", "8589934592");
        saData.put("device_width", "1920");
        saData.put("device_height", "1080");
        saData.put("os_detail", "10.0.26100");

        ObjectNode version = Json.mapper().createObjectNode();
        version.put("version", launcherVersion);
        version.put("launcher_md5", "");
        version.put("updater_md5", "");

        ObjectNode authBody = Json.mapper().createObjectNode();
        authBody.put("sa_data", Json.write(saData));
        authBody.put("sauth_json", sauthJson);
        authBody.set("version", version);
        authBody.putNull("sdkuid");
        authBody.put("aid", aid);
        authBody.put("hasMessage", false);
        authBody.put("hasGmail", false);
        authBody.put("otp_token", otpToken);
        authBody.putNull("otp_pwd");
        authBody.put("lock_time", 0);
        authBody.putNull("env");
        authBody.putNull("min_engine_version");
        authBody.putNull("min_patch_version");
        authBody.put("verify_status", 0);
        authBody.putNull("unisdk_login_json");
        authBody.putNull("token");
        authBody.put("is_register", true);
        authBody.putNull("entity_id");

        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoints.coreHost() + "/authentication-otp"))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "WPFLauncher/" + launcherVersion)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        X19HttpCipher.encrypt(Json.write(authBody).getBytes(StandardCharsets.UTF_8))))
                .build();
        HttpResponse<byte[]> resp = send(req, HttpResponse.BodyHandlers.ofByteArray());
        byte[] plain = X19HttpCipher.decrypt(resp.body());
        if (plain == null) {
            throw new IOException("authentication-otp 响应无法解密 http=" + resp.statusCode());
        }
        JsonNode auth = entityOf(Json.readTree(new String(plain, StandardCharsets.UTF_8)), "authentication-otp");
        String entityId = Json.str(auth, "entity_id");
        String token = Json.str(auth, "token");
        String account = Json.str(auth, "account");

        try {
            postSigned(endpoints.coreHost() + "/interconn/web/game-play-v2/login-start", "{\"strict_mode\":true}", entityId, token);
        } catch (IOException | RuntimeException e) {
            Log.warn("[login] login-start 失败（忽略）: " + e.getMessage());
        }

        Log.info("[login] 登录成功 userId=" + entityId + " channel=" + channel + " token="
                + token.substring(0, Math.min(4, token.length())) + "…");
        return new NeteaseSession(Integer.parseInt(entityId), token, account, channel);
    }

    // ------------------------------------------------------------------ device state

    private record Device(String id, String key) {
    }

    private String loadOrCreateUnique() throws IOException {
        Path uniqueFile = stateDir.resolve(MPAY_GAME_ID + "-guid.cds");
        if (!Files.isRegularFile(uniqueFile)) {
            Files.writeString(uniqueFile, UUID.randomUUID().toString().replace("-", ""), StandardCharsets.UTF_8);
        }
        return Files.readString(uniqueFile, StandardCharsets.UTF_8).trim();
    }

    private Device loadOrCreateDevice() throws IOException {
        Path deviceFile = stateDir.resolve(MPAY_GAME_ID + "-device.cds");
        String unique = loadOrCreateUnique();

        if (!Files.isRegularFile(deviceFile)) {
            Map<String, String> form = baseParams();
            form.put("brand", "Microsoft");
            form.put("device_model", "pc_mode");
            form.put("device_name", "PC-" + randomAlnum(12));
            form.put("device_type", "Computer");
            form.put("init_urs_device", "0");
            form.put("mac", randomMac(":"));
            form.put("resolution", "1920x1080");
            form.put("system_name", "windows");
            form.put("system_version", "10.0.22621");
            form.put("unique_id", unique);
            HttpResponse<String> resp = postForm(endpoints.mpayHost() + "/mpay/games/" + MPAY_GAME_ID + "/devices", form);
            String text = resp.body();
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("MPay 设备注册失败: " + text);
            }
            Files.writeString(deviceFile, text, StandardCharsets.UTF_8);
        }

        JsonNode root = Json.readTree(Files.readString(deviceFile, StandardCharsets.UTF_8));
        JsonNode d = root.path("device");
        return new Device(Json.str(d, "id"), Json.str(d, "key"));
    }

    private JsonNode mpayLogin(String email, String password, String unique, Device device) throws IOException {
        ObjectNode parameters = Json.mapper().createObjectNode();
        parameters.put("password", md5Hex(password.getBytes(StandardCharsets.UTF_8)).toLowerCase(java.util.Locale.ROOT));
        parameters.put("unique_id", unique);
        parameters.put("username", email);
        String encrypted = aesEcbHex(Json.write(parameters).getBytes(StandardCharsets.UTF_8), device.key());

        Map<String, String> form = baseParams();
        form.put("opt_fields", "nickname,avatar,realname_status,mobile_bind_status,mask_related_mobile,related_login_status");
        form.put("params", encrypted);
        form.put("un", Base64.getEncoder().encodeToString(email.getBytes(StandardCharsets.UTF_8)));

        HttpResponse<String> resp = postForm(endpoints.mpayHost() + "/mpay/games/" + MPAY_GAME_ID + "/devices/" + device.id() + "/users", form);
        String text = resp.body();
        if (resp.statusCode() / 100 != 2) {
            if (text.contains("\"code\":1351") || text.contains("\"code\": 1351")) {
                throw new IllegalStateException(
                        "网易账号需要安全验证（code 1351），请先在官方启动器完成验证，或改用 cookie 登录: " + text);
            }
            throw new IllegalStateException("网易邮箱登录失败: " + text);
        }
        return Json.readTree(text).path("user");
    }

    private Map<String, String> baseParams() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("app_channel", "netease");
        form.put("app_mode", "2");
        form.put("app_type", "games");
        form.put("arch", "win_x64");
        form.put("cv", "c4.2.0");
        form.put("mcount_app_key", "EEkEEXLymcNjM42yLY3Bn6AO15aGy4yq");
        form.put("mcount_transaction_id", "0");
        form.put("process_id", Long.toString(ProcessHandle.current().pid()));
        form.put("sv", "10.0.22621");
        form.put("updater_cv", "c1.0.0");
        form.put("game_id", MPAY_GAME_ID);
        form.put("gv", launcherVersion);
        return form;
    }

    // ------------------------------------------------------------------ helpers

    private void uniSauth(String sauthJson) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.mgbsdkHost() + "/x19/sdk/uni_sauth"))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "WPFLauncher/" + launcherVersion)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(sauthJson, StandardCharsets.UTF_8))
                .build();
        String text = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        JsonNode doc = Json.readTree(text);
        String code = doc.has("code") && !doc.path("code").isNull() ? doc.path("code").asText() : "";
        if ("200".equals(code)) {
            return;
        }
        Log.debug("[login] uni_sauth 被拒 code=" + code + " " + text);
        String reason = uniSauthReason(doc);
        if (reason.contains("10204")) {
            throw new IllegalStateException("渠道凭证已失效（10204 验证失败）：这个账号在别处重新登录过，旧的 sauth_json 已作废。"
                    + "Cookie 账号请重新获取 sauth_json，4399 账号请重新登录。");
        }
        throw new IllegalStateException("渠道校验未通过（code=" + code + "）：" + reason);
    }

    /** A readable reason from a rejected uni_sauth response: msg without ntsdk markup, else the base64 debug_message. */
    private static String uniSauthReason(JsonNode root) {
        String msg = root.path("msg").isTextual() ? root.path("msg").asText() : "";
        msg = msg.replaceAll("</?ntsdk[^>]*>", "").trim();
        if (!msg.isEmpty()) {
            return msg;
        }
        String raw = root.path("debug_message").isTextual() ? root.path("debug_message").asText() : "";
        if (!raw.isEmpty()) {
            try {
                return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8).trim();
            } catch (IllegalArgumentException e) {
                return raw;
            }
        }
        return root.toString();
    }

    /** aim_info: whoami geo data (same as FandNEL's InternalQuery.ToAimInfo); falls back to defaults on failure. */
    private String aimInfo() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.whoamiHost() + "/v6"))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "WPFLauncher/" + launcherVersion)
                    .header("X-AUTH-PRODUCT", "g0")
                    .header("X-AUTH-TOKEN", "token.efa8zUW6sxjR")
                    .header("X-IPDB-LOCALE", "en")
                    .header("X-PROJECT_CODE", "x19")
                    .GET()
                    .build();
            String payload = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
            JsonNode who = Json.readTree(payload);
            String encoded = Json.str(who, "payload");
            JsonNode geo = Json.readTree(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            ObjectNode aim = Json.mapper().createObjectNode();
            for (String key : new String[]{"code_1", "code_2", "code_3", "code_4", "isp"}) {
                aim.set(key, geo.has(key) ? geo.get(key) : Json.mapper().getNodeFactory().nullNode());
            }
            aim.set("aim", geo.has("ip") ? geo.get("ip") : Json.mapper().getNodeFactory().nullNode());
            aim.put("country", "CN");
            aim.put("tz", "+0800");
            aim.put("tzid", "");
            return Json.write(aim);
        } catch (IOException | RuntimeException e) {
            Log.warn("[login] whoami 失败，aim_info 用默认值: " + e.getMessage());
            return "{\"aim\":\"127.0.0.1\",\"country\":\"CN\",\"tz\":\"+0800\",\"tzid\":\"\"}";
        }
    }

    private String gatewayIp() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.nstoolHost() + "/internalquery"))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .GET()
                    .build();
            String text = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
            for (String line : text.split("\n")) {
                String[] parts = line.split("=");
                if (parts.length == 2 && "gw".equals(parts[0].trim()) && !parts[1].trim().isEmpty()) {
                    return parts[1].trim();
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("[login] internalquery 失败，ip 用默认值: " + e.getMessage());
        }
        return "127.0.0.1";
    }

    private String postJson(String url, String body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "WPFLauncher/" + launcherVersion)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    private void postSigned(String url, String body, String userId, String token) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "WPFLauncher/" + launcherVersion)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        for (Map.Entry<String, String> header : HttpToken.compute(URI.create(url).getRawPath(), body, userId, token)
                .entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        HttpResponse<String> resp = send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("login-start http=" + resp.statusCode());
        }
    }

    private static JsonNode entityOf(JsonNode root, String api) {
        int code = root.path("code").asInt(-1);
        JsonNode entity = root.path("entity");
        if (code != 0 || !entity.isObject()) {
            String message = root.path("message").isTextual() ? root.path("message").asText() : root.toString();
            throw new IllegalStateException(api + " 失败 code=" + code + ": " + message);
        }
        return entity;
    }

    private static String unwrapCookie(String cookie) {
        String trimmed = cookie == null ? "" : cookie.trim();
        JsonNode doc;
        try {
            doc = Json.readTree(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cookie 不是合法 JSON（应为 sauth_json）", e);
        }
        if (!doc.isObject()) {
            throw new IllegalArgumentException("cookie 应为 sauth_json 对象（{...}），当前是 " + doc.getNodeType());
        }
        if (doc.path("sauth_json").isTextual()) {
            return doc.path("sauth_json").asText();
        }
        return trimmed;
    }

    private static String randomMac(String sep) {
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        b[0] = (byte) ((b[0] & 0xFE) | 0x02);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String randomAlnum(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte v : b) {
            sb.append(String.format("%02X", v & 0xFF));
        }
        return sb.toString();
    }

    private static String aesEcbHex(byte[] data, String keyHex) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hexBytes(keyHex), "AES"));
            byte[] enc = cipher.doFinal(data);
            StringBuilder sb = new StringBuilder(enc.length * 2);
            for (byte b : enc) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MPay 参数加密失败: " + e.getMessage(), e);
        }
    }

    private static byte[] hexBytes(String hex) {
        String s = hex.trim();
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String md5Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02X", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private HttpResponse<String> postForm(String url, Map<String, String> form) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "WPFLauncher/" + launcherVersion)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(Forms.encode(form), StandardCharsets.UTF_8))
                .build();
        return send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("登录请求被中断", e);
        }
    }

    @Override
    public void close() {
        // HttpClient needs no explicit shutdown in Java 21
    }
}
