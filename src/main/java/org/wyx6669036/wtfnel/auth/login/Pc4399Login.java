package org.wyx6669036.wtfnel.auth.login;

import org.wyx6669036.wtfnel.util.Json;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 4399 PC login (ptlogin.4399.com username + password), ported from FandNEL's Pc4399.
 *
 * <p>Port of WtfNel.Core/Auth/Login/Pc4399Login.cs.</p>
 */
public final class Pc4399Login implements AutoCloseable {

    /** 4399 PC channel credentials returned by the 4399 SDK, used to build the sauth_json. */
    public record Credentials(String username, String uid, String token, String time) {
    }

    private static final String APP_ID = "kid_wdsj";
    private static final String GAME_URL = "https://cdn.h5wan.4399sj.com/microterminal-h5-frame?game_id=500352";
    private static final String DEFAULT_LOGIN_HOST = "https://ptlogin.4399.com";
    private static final String DEFAULT_SERVICE_HOST = "https://microgame.5054399.net";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern CAPTCHA_ID = Pattern.compile("captcha\\.do\\?captchaId=([A-Za-z0-9_\\-]+)");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** ptlogin and the SDK host; default point at 4399, tests point them at a local server. */
    record Endpoints(String loginHost, String serviceHost) {

        static Endpoints defaults() {
            return new Endpoints(DEFAULT_LOGIN_HOST, DEFAULT_SERVICE_HOST);
        }
    }

    private final ICaptchaPrompt captcha;
    private final HttpClient http;
    private final Endpoints endpoints;

    public Pc4399Login(ICaptchaPrompt captcha) {
        this(captcha, Endpoints.defaults());
    }

    Pc4399Login(ICaptchaPrompt captcha, Endpoints endpoints) {
        this.captcha = captcha;
        this.endpoints = endpoints == null ? Endpoints.defaults() : endpoints;
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public Credentials login(String username, String password) throws IOException {
        Map<String, String> form = loginParams();
        form.put("username", username);
        form.put("password", password);

        HttpResponse<String> frame = postForm(endpoints.loginHost() + "/ptlogin/loginFrame.do?v=1", form);
        String text = frame.body();
        if (text.contains("账号异常，请输入验证码")) {
            Matcher id = CAPTCHA_ID.matcher(text);
            if (captcha == null || !id.find()) {
                throw new IllegalStateException("4399 账号需要验证码，请先在 4399 网页登录一次后再试");
            }
            byte[] image = getBytes(endpoints.loginHost() + "/ptlogin/captcha.do?captchaId=" + id.group(1));
            form.put("sessionId", id.group(1));
            form.put("inputCaptcha", captcha.image(image).trim());
        }

        HttpResponse<String> login = postForm(endpoints.loginHost() + "/ptlogin/login.do?v=1", form);
        if (login.statusCode() / 100 != 2) {
            throw new IllegalStateException("4399 登录失败 http=" + login.statusCode());
        }

        String query = checkKidLogin();
        Map<String, String> data = Forms.parseQuery(sdkLoginData(query));
        String uid = data.getOrDefault("uid", "");
        String token = data.getOrDefault("token", "");
        if (uid.isEmpty() || token.isEmpty()) {
            throw new IllegalStateException("4399 登录失败：未拿到 uid/token，请检查账号密码");
        }
        return new Credentials(data.getOrDefault("username", ""), uid, token, data.getOrDefault("time", ""));
    }

    private String checkKidLogin() throws IOException {
        long timestamp = Instant.now().getEpochSecond();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("appId", APP_ID);
        values.put("gameUrl", GAME_URL);
        values.put("isCrossDomain", "1");
        values.put("nick", "null");
        values.put("onLineStart", "false");
        values.put("ptLogin", "true");
        values.put("rand_time", "$randTime");
        values.put("retUrl", "https://ptlogin.4399.com/resource/ucenter.html?action=login&appId=kid_wdsj"
                + "&loginLevel=8&regLevel=8&bizId=2201001794&externalLogin=qq&qrLogin=true&layout=vertical"
                + "&level=101&css=https://microgame.5054399.net/v2/resource/cssSdk/default/login.css"
                + "&v=2018_11_26_16&postLoginHandler=redirect&checkLoginUserCookie=true"
                + "&redirectUrl=http%3A%2F%2Fcdn.h5wan.4399sj.com%2Fmicroterminal-h5-frame%3Fgame_id%3D500352"
                + "%26rand_time%3D" + timestamp);
        values.put("show", "1");

        HttpResponse<String> resp = get(endpoints.loginHost() + "/ptlogin/checkKidLoginUserCookie.do?" + Forms.encode(values));
        String uri = resp.uri() == null ? "" : resp.uri().toString();
        if (uri.isEmpty()) {
            throw new IllegalStateException("4399 登录失败：没有跳转地址");
        }
        int start = uri.lastIndexOf('?');
        if (start < 0) {
            throw new IllegalStateException("4399 登录失败：跳转地址没有参数 " + uri);
        }
        return uri.substring(start + 1);
    }

    private String sdkLoginData(String query) throws IOException {
        String callback = "jQuery1830" + randomDigits(16) + "_" + Instant.now().getEpochSecond();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("callback", callback);
        values.put("queryStr", query);
        String url = endpoints.serviceHost() + "/v2/service/sdk/info?" + Forms.encode(values);

        HttpResponse<String> resp = get(url);
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("4399 uni-auth 失败 http=" + resp.statusCode());
        }
        String text = resp.body();
        int open = text.indexOf(callback + "(");
        int close = text.lastIndexOf(')');
        if (open < 0 || close <= open) {
            throw new IllegalStateException("4399 uni-auth 响应格式异常: "
                    + text.substring(0, Math.min(text.length(), 200)));
        }
        String json = text.substring(open + callback.length() + 1, close);
        JsonNode root = Json.readTree(json);
        JsonNode data = root.path("data");
        if (data.isObject()) {
            String sdk = Json.str(data, "sdk_login_data");
            if (!sdk.isEmpty()) {
                return sdk;
            }
        }
        throw new IllegalStateException("4399 uni-auth 失败: " + Json.str(root, "msg"));
    }

    private HttpResponse<String> postForm(String url, Map<String, String> form) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(Forms.encode(form), StandardCharsets.UTF_8))
                .build();
        return send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        return send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private byte[] getBytes(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        return send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("4399 请求被中断", e);
        }
    }

    private static Map<String, String> loginParams() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("appId", APP_ID);
        form.put("autoLogin", "on");
        form.put("bizId", "2201001794");
        form.put("css", "https://microgame.5054399.net/v2/resource/cssSdk/default/login.css");
        form.put("displayMode", "popup");
        form.put("externalLogin", "qq");
        form.put("gameId", "wd");
        form.put("iframeId", "popup_login_frame");
        form.put("includeFcmInfo", "false");
        form.put("layout", "vertical");
        form.put("layoutSelfAdapting", "true");
        form.put("level", "8");
        form.put("loginFrom", "uframe");
        form.put("mainDivId", "popup_login_div");
        form.put("postLoginHandler", "default");
        form.put("redirectUrl", "");
        form.put("regLevel", "8");
        form.put("sec", "1");
        form.put("sessionId", "");
        form.put("userNameLabel", "4399用户名");
        form.put("userNameTip", "请输入4399用户名");
        form.put("welcomeTip", "欢迎回到4399");
        return form;
    }

    private static String randomDigits(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) ('0' + RANDOM.nextInt(10)));
        }
        return sb.toString();
    }

    @Override
    public void close() {
        // HttpClient needs no explicit shutdown in Java 21
    }
}
