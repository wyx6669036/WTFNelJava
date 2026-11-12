package org.wyx6669036.wtfnel.auth.login;

import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.util.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 4399com login (4399 mobile SDK OAuth), ported from FandNEL's Com4399; captchas are handed to
 * {@link ICaptchaPrompt}.
 *
 * <p>Port of WtfNel.Core/Auth/Login/Com4399Login.cs.</p>
 */
public final class Com4399Login implements AutoCloseable {

    /** 4399 mobile SDK credentials (uid + OAuth state), used to build the 4399com sauth_json. */
    public record Credentials(String uid, String state) {
    }

    private static final String DEFAULT_API_HOST = "https://m.4399api.com";
    private static final String DEFAULT_LOGIN_HOST = "https://ptlogin.4399.com";
    private static final String CALLBACK_PATH = "/openapi/oauth-callback.html?gamekey=44770&game_key=115716";
    private static final int MAX_RETRY = 5;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; PFJM10 Build/UKQ1.230924.001; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36";
    private static final Pattern CAPTCHA_ID = Pattern.compile(
            "name\\s*=\\s*[\"']captcha_id[\"']\\s+value\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** The API host and the ptlogin host; defaults point at 4399, tests point them at a local server. */
    record Endpoints(String apiHost, String loginHost) {

        static Endpoints defaults() {
            return new Endpoints(DEFAULT_API_HOST, DEFAULT_LOGIN_HOST);
        }
    }

    private final Path deviceFile;
    private final ICaptchaPrompt captcha;
    private final HttpClient http;
    private final HttpClient noRedirect;
    private final Endpoints endpoints;

    private String deviceId = "";
    private String deviceIdSm = "";
    private String udid = "";

    public Com4399Login(Path stateDir, ICaptchaPrompt captcha) throws IOException {
        this(stateDir, captcha, Endpoints.defaults());
    }

    Com4399Login(Path stateDir, ICaptchaPrompt captcha, Endpoints endpoints) throws IOException {
        Files.createDirectories(stateDir);
        this.deviceFile = stateDir.resolve("4399com.cds");
        this.captcha = captcha;
        this.endpoints = endpoints == null ? Endpoints.defaults() : endpoints;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.noRedirect = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public Credentials login(String username, String password) throws IOException {
        loadOrCreateDevice();
        return loginAndAuthorize(username, password, null, null, 0);
    }

    private Credentials loginAndAuthorize(String username, String password, String captchaText,
                                          String captchaId, int retry) throws IOException {
        if (retry > MAX_RETRY) {
            throw new IllegalStateException("4399 登录重试次数过多");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("isInputRealname", "false");
        form.put("isValidRealname", "false");
        form.put("sec", "1");
        form.put("password", password);
        form.put("username", username.toLowerCase(java.util.Locale.ROOT));
        form.put("css", "");
        form.put("show_close_button", "");
        form.put("response_type", "TOKEN");
        form.put("client_id", "40f9e9b95d6c71ba5c6e0bd14c0abeff");
        form.put("show_4399", "");
        form.put("username_history", "");
        form.put("uid", "");
        form.put("expand_ext_login_list", "");
        form.put("ref", "{\"game\":\"115716\",\"channel\":\"\"}");
        form.put("autoCreateAccount", "");
        form.put("scope", "basic");
        form.put("bizId", "2100001792");
        form.put("state", requestState());
        form.put("show_ext_login", "");
        form.put("reg_mode", "reg_phone");
        form.put("_d", deviceId);
        form.put("show_back_button", "");
        form.put("auto_scroll", "");
        form.put("access_token", "");
        form.put("show_forget_password", "");
        form.put("auth_action", "ORILOGIN");
        form.put("redirect_uri", endpoints.apiHost() + CALLBACK_PATH);
        form.put("show_topbar", "false");
        form.put("aid", "");
        form.put("cid", "");
        if (captchaText != null && captchaId != null) {
            form.put("captcha", captchaText);
            form.put("captcha_id", captchaId);
        }

        HttpResponse<String> login = postForm(noRedirect,
                endpoints.loginHost() + "/oauth2/loginAndAuthorize.do?channel=&sdk=op&sdk_version=3.12.2.503", form);
        String loginText = login.body();
        if (loginText.contains("请稍后再试")) {
            throw new IllegalStateException("4399 限制了登录频率（http=" + login.statusCode()
                    + " 请稍后再试），等几分钟再登录，别连续重试");
        }

        if (loginText.contains("验证码")) {
            Matcher id = CAPTCHA_ID.matcher(loginText);
            if (!id.find()) {
                throw new IllegalStateException("4399 需要验证码，但页面里找不到 captcha_id");
            }
            byte[] image = getBytes(noRedirect,
                    endpoints.loginHost() + "/ptlogin/captcha.do?captchaId=" + id.group(1) + "&xx=1");
            String text = requirePrompt().image(image).trim();
            return loginAndAuthorize(username, password, text, id.group(1), retry + 1);
        }

        String location = login.headers().firstValue("location").orElse("");
        if (location.isEmpty()) {
            if (loginText.contains("用户名或密码错误") || loginText.contains("密码错误")) {
                throw new IllegalStateException("4399 用户名或密码错误");
            }
            throw new IllegalStateException("4399 登录失败：没有跳转地址 "
                    + loginText.substring(0, Math.min(loginText.length(), 200)));
        }

        String redirect = location.startsWith("http") ? location
                : URI.create(endpoints.loginHost()).resolve(location).toString();
        if (captchaText != null && captchaId == null) {
            redirect += (redirect.indexOf('?') >= 0 ? "&" : "?") + "captcha=" + Forms.escape(captchaText);
        }

        HttpResponse<String> auth = get(http, redirect);
        if (auth.statusCode() / 100 != 2) {
            throw new IllegalStateException("4399 授权页失败 http=" + auth.statusCode());
        }
        String authText = auth.body();

        if (authText.contains("登录状态已失效，请重新登录")) {
            refreshDeviceState();
            return loginAndAuthorize(username, password, null, null, retry + 1);
        }
        if (authText.contains("登录成功，但账号存在异常，需要验证")) {
            String token = solveVerifyCaptcha(authText);
            return loginAndAuthorize(username, password, token, null, retry + 1);
        }

        JsonNode root = Json.readTree(authText);
        String code = Json.str(root, "code");
        JsonNode result = root.path("result");
        if (!"100".equals(code) || !result.isObject()) {
            throw new IllegalStateException("4399 授权失败: " + Json.str(root, "message"));
        }
        String uid = Json.str(result, "uid");
        return new Credentials(uid, Json.str(result, "state"));
    }

    private String solveVerifyCaptcha(String resultJson) throws IOException {
        JsonNode root = Json.readTree(resultJson);
        String url = Json.str(root.path("result"), "url");
        if (url.isEmpty()) {
            throw new IllegalStateException("4399 账号需要验证，但没有验证码地址");
        }

        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            JsonNode res = Json.readTree(get(http, url).body()).path("result");
            String captchaId = Json.str(res, "captchaId");
            byte[] image = Base64.getDecoder().decode(stripDataUrl(Json.str(res, "img")));
            ImageSize.Size size = ImageSize.read(image);
            int width = size == null ? 544 : size.width();
            int height = size == null ? 306 : size.height();

            String checkUrl;
            if (url.contains("jigsaw")) {
                byte[] piece = Base64.getDecoder().decode(stripDataUrl(Json.str(res, "img2")));
                int x = (int) Math.round(requirePrompt().jigsaw(image, piece) * 544.0 / width);
                checkUrl = endpoints.apiHost() + "/captcha/jigsaw-check.html?refer=sdk&v="
                        + Forms.escape(encryptJigsaw(Math.max(0, Math.min(x, 494))))
                        + "&captchaId=" + Forms.escape(captchaId);
            } else if (url.contains("click")) {
                String text = Json.str(res, "text");
                List<ICaptchaPrompt.Point> points = requirePrompt().click(image, text);
                ObjectNode payload = Json.mapper().createObjectNode();
                payload.put("w", 544);
                payload.put("h", 306);
                ArrayNode array = payload.putArray("c");
                for (ICaptchaPrompt.Point point : points) {
                    ObjectNode node = array.addObject();
                    node.put("x", round2(point.x() * 544.0 / width));
                    node.put("y", round2(point.y() * 306.0 / height));
                }
                checkUrl = endpoints.apiHost() + "/captcha/click-check.html?refer=sdk&v="
                        + Forms.escape(Json.write(payload))
                        + "&captchaId=" + Forms.escape(captchaId);
            } else {
                throw new IllegalStateException("未知的 4399 验证码类型: " + url);
            }

            JsonNode check = Json.readTree(get(http, checkUrl).body());
            if ("100".equals(Json.str(check, "code"))) {
                String token = Json.str(check.path("result"), "token");
                Map<String, String> payload = new LinkedHashMap<>();
                payload.put("v_token", token);
                payload.put("captcha_id", captchaId);
                payload.put("type", "0");
                return Json.write(payload);
            }
            Log.warn("[login] 4399 验证码校验失败，重新获取: " + check);
        }
        throw new IllegalStateException("4399 验证码多次校验失败");
    }

    private String requestState() throws IOException {
        JsonNode root = Json.readTree(get(http, endpoints.apiHost() + CALLBACK_PATH).body());
        String state = Forms.queryValue(Json.str(root, "result"), "state");
        if (state.isEmpty()) {
            throw new IllegalStateException("4399 OAuth 回调里没有 state: " + root);
        }
        return state;
    }

    private void loadOrCreateDevice() throws IOException {
        if (Files.isRegularFile(deviceFile)) {
            String text = Files.readString(deviceFile, StandardCharsets.UTF_8);
            try {
                JsonNode root = Json.readTree(text);
                deviceId = Json.str(root, "device-id");
                deviceIdSm = Json.str(root, "device-id-sm");
                udid = Json.str(root, "device-udid");
                if (root.path("device-state").isTextual() && !deviceId.isEmpty()) {
                    return;
                }
            } catch (RuntimeException e) {
                Log.debug("[login] 4399com.cds 解析失败，重新生成设备信息: " + e.getMessage());
            }
        }
        deviceId = generateIdentifier();
        deviceIdSm = generateIdentifier();
        udid = UUID.randomUUID().toString();
        refreshDeviceState();
    }

    private void refreshDeviceState() throws IOException {
        ObjectNode device = Json.mapper().createObjectNode();
        device.put("DEVICE_IDENTIFIER", deviceId);
        device.put("SCREEN_RESOLUTION", "3200*1384");
        device.put("DEVICE_MODEL", "Oppo A5 2022");
        device.put("DEVICE_MODEL_VERSION", "14");
        device.put("SYSTEM_VERSION", "14");
        device.put("PLATFORM_TYPE", "Android");
        device.put("SDK_VERSION", "3.12.2.503");
        device.put("GAME_KEY", "115716");
        device.put("GAME_VERSION", "3.1.5.260925");
        device.put("BID", "com.netease.mc.m4399");
        device.put("RUNTIME", "Origin");
        device.put("CANAL_IDENTIFIER", "");
        device.put("UDID", udid);
        device.put("DEBUG", "false");
        device.put("NETWORK_TYPE", "WIFI");
        device.put("GAME_BOX_VERSION", "");
        device.put("VIP_INFO", "");
        device.put("TEAM", 2);
        device.put("DEVICE_IDENTIFIER_SM", deviceIdSm);
        device.put("UID", "");

        Map<String, String> form = new LinkedHashMap<>();
        form.put("usernames", "");
        form.put("top_bar", "1");
        form.put("state", "");
        form.put("device", Json.write(device));

        HttpResponse<String> resp = postForm(http, endpoints.apiHost() + "/openapiv2/oauth.html", form);
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("4399 设备信息提交失败 http=" + resp.statusCode());
        }
        JsonNode root = Json.readTree(resp.body());
        String loginUrl = Json.str(root.path("result"), "login_url");
        ObjectNode saved = Json.mapper().createObjectNode();
        saved.put("device-id", deviceId);
        saved.put("device-id-sm", deviceIdSm);
        saved.put("device-udid", udid);
        saved.put("device-state", Forms.queryValue(loginUrl, "state"));
        Files.writeString(deviceFile, Json.write(saved), StandardCharsets.UTF_8);
    }

    private ICaptchaPrompt requirePrompt() {
        if (captcha == null) {
            throw new IllegalStateException("4399 需要验证码，当前环境无法输入");
        }
        return captcha;
    }

    private static String encryptJigsaw(int x) {
        try {
            byte[] key = MessageDigest.getInstance("MD5")
                    .digest("df0a5f98c337de97".getBytes(StandardCharsets.UTF_8));
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec("79c83220d9974edf".getBytes(StandardCharsets.UTF_8)));
            byte[] enc = cipher.doFinal(("{\"x\":" + x + "}").getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(enc);
        } catch (Exception e) {
            throw new IllegalStateException("4399 验证码加密失败: " + e.getMessage(), e);
        }
    }

    private static String stripDataUrl(String s) {
        int comma = s.indexOf(',');
        return s.startsWith("data:") && comma >= 0 ? s.substring(comma + 1) : s;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String generateIdentifier() {
        String seed = UUID.randomUUID() + Long.toString(System.nanoTime());
        String hex;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            hex = sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        return LocalDateTime.now().format(STAMP) + hex.substring(0, 50);
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "com.netease.mc.m4399")
                .GET()
                .build();
        return send(client, request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static byte[] getBytes(HttpClient client, String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "com.netease.mc.m4399")
                .GET()
                .build();
        return send(client, request, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    private HttpResponse<String> postForm(HttpClient client, String url, Map<String, String> form) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "com.netease.mc.m4399")
                .header("Referer", endpoints.loginHost() + "/oauth2/authorize.do")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(Forms.encode(form), StandardCharsets.UTF_8))
                .build();
        return send(client, request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        try {
            return client.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("4399 请求被中断", e);
        }
    }

    @Override
    public void close() {
        // HttpClient needs no explicit shutdown in Java 21
    }
}
