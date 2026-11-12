package org.wyx6669036.wtfnel.auth.yggdrasil;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.wyx6669036.wtfnel.install.Archives;
import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进服认证里用到的 modInfo，取自 X19 接口（与 AlaneNEL 的 InstallerService.InstallGameMods 同流程）：
 * <ol>
 *   <li>核心 mods：search-by-game 拿 iid 列表 → user-item-download-v2/get-list 拿 jar_md5；</li>
 *   <li>服务器 mods：user-item-download-v2 拿服务器资源包 → 包里 .minecraft/mods/*.jar 的 md5。</li>
 * </ol>
 * 资源包按 res_md5 缓存在 cache/mods 下，没变就不再下载。
 *
 * <p>移植自 WtfNel.Core/Auth/Yggdrasil/ModInfoProvider.cs。</p>
 */
public final class ModInfoProvider {

    public static final String GATEWAY_HOST = "https://x19apigatewayobt.nie.netease.com";
    public static final String MCL_HOST = "https://x19mclobt.nie.netease.com";

    private final String launcherVersion;
    private final int mcVersionId;
    private final int gameType;
    private final Path cacheDir;
    private final String gatewayHost;
    private final String mclHost;
    private final HttpClient http;
    /** gameId -> 计算中的 modInfo；失败的结果会被移除，下次重算。 */
    private final Map<String, CompletableFuture<String>> cache = new ConcurrentHashMap<>();

    public ModInfoProvider(String launcherVersion, int mcVersionId, int gameType, Path cacheDir) {
        this(launcherVersion, mcVersionId, gameType, cacheDir, GATEWAY_HOST, MCL_HOST);
    }

    /**
     * 换掉两个 X19 主机地址（只给测试用：把接口指到本地 HttpServer）。
     * 生产代码用 4 参构造器，地址就是 {@link #GATEWAY_HOST} / {@link #MCL_HOST}。
     */
    public ModInfoProvider(String launcherVersion, int mcVersionId, int gameType, Path cacheDir,
                           String gatewayHost, String mclHost) {
        this.launcherVersion = launcherVersion;
        this.mcVersionId = mcVersionId;
        this.gameType = gameType;
        this.cacheDir = cacheDir;
        this.gatewayHost = gatewayHost;
        this.mclHost = mclHost;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMinutes(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String getLauncherVersion() {
        return launcherVersion;
    }

    // ---------------------------------------------------------------- X19 queries

    /** 真实联机服地址（X19 /item-address/get，与 FandNEL 的 GetNetGameServerAddress 一致）。 */
    public ServerAddress getServerAddress(String gameId, int userId, String userToken) throws IOException {
        JsonNode doc = post(gatewayHost, "/item-address/get",
                Map.of("item_id", gameId), userId, userToken);
        JsonNode e = doc.path("entity");
        String host = e.hasNonNull("ip") ? e.get("ip").asText()
                : e.hasNonNull("host") ? e.get("host").asText() : "";
        int port = e.path("port").asInt(0);
        if (host.isBlank()) {
            throw new IllegalStateException("X19 未返回服务器地址: " + e);
        }
        String announcement = e.path("announcement").asText("");
        if (!announcement.isEmpty()) {
            Log.warn("[服务器公告] " + announcement);
        }
        return new ServerAddress(host, port > 0 ? port : 25565);
    }

    /** 账号在该服的已有角色名。 */
    public List<String> queryRoles(String gameId, int userId, String userToken) throws IOException {
        JsonNode doc = post(gatewayHost, "/game-character/query/user-game-characters",
                Map.of("offset", 0, "length", 20, "user_id", String.valueOf(userId),
                        "game_id", gameId, "game_type", "2"),
                userId, userToken);
        List<String> names = new ArrayList<>();
        JsonNode list = doc.path("entities");
        if (list.isArray()) {
            for (JsonNode e : list) {
                String name = e.path("name").asText("");
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** 创建角色；X19 用非 0 code 表示失败（例如重名），原因在异常消息里。 */
    public void createRole(String gameId, int userId, String userToken, String name) throws IOException {
        post(gatewayHost, "/game-character",
                Map.of("game_id", gameId, "game_type", 2, "user_id", String.valueOf(userId),
                        "name", name, "create_time", 555555, "expire_time", 0),
                userId, userToken);
    }

    /** 每个 gameId 只算一次；失败的结果不缓存，下次重试。 */
    public CompletableFuture<String> get(String gameId, int userId, String userToken) {
        CompletableFuture<String> future = cache.computeIfAbsent(gameId,
                key -> build(key, userId, userToken));
        if (future.isCompletedExceptionally()) {
            cache.remove(gameId, future);
            future = cache.computeIfAbsent(gameId, key -> build(key, userId, userToken));
        }
        return future;
    }

    /** 同步版，给 CLI 用。 */
    public String getBlocking(String gameId, int userId, String userToken) {
        try {
            return get(gameId, userId, userToken).join();
        } catch (RuntimeException e) {
            Throwable root = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("[mods] 获取 modInfo 失败: " + root.getMessage(), root);
        }
    }

    private CompletableFuture<String> build(String gameId, int userId, String userToken) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return buildNow(gameId, userId, userToken);
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
    }

    private String buildNow(String gameId, int userId, String userToken) throws IOException {
        List<ModEntry> mods = new ArrayList<>();

        JsonNode doc = post(gatewayHost, "/game-auth-item-list/query/search-by-game",
                Map.of("mc_version_id", mcVersionId, "game_type", gameType), userId, userToken);
        List<Long> iids = new ArrayList<>();
        for (JsonNode n : doc.path("entity").path("iid_list")) {
            iids.add(n.asLong());
        }
        JsonNode details = post(gatewayHost, "/user-item-download-v2/get-list",
                Map.of("item_id_list", iids), userId, userToken);
        for (JsonNode comp : details.path("entities")) {
            String itemId = comp.path("item_id").asText("");
            int mtype = comp.path("mtypeid").asInt();
            for (JsonNode sub : comp.path("sub_entities")) {
                String name = itemId + "@" + mtype + "@0.jar";
                ModEntry entry = new ModEntry();
                entry.setModPath(name);
                entry.setId(name);
                entry.setIid(itemId);
                entry.setMd5(sub.path("jar_md5").asText("").toUpperCase(java.util.Locale.ROOT));
                mods.add(entry);
            }
        }
        Log.info("[mods] 核心 mods " + mods.size() + " 个");

        JsonNode serverDoc = post(mclHost, "/user-item-download-v2",
                Map.of("item_id", gameId, "length", 0, "offset", 0), userId, userToken);
        JsonNode entity = serverDoc.path("entity");
        JsonNode subs = entity.path("sub_entities");
        if (entity.isObject() && subs.isArray() && !subs.isEmpty()) {
            JsonNode sub = subs.get(0);
            List<ModEntry> serverMods = getServerMods(gameId,
                    sub.path("res_url").asText(""), sub.path("res_md5").asText(""));
            mods.addAll(serverMods);
            Log.info("[mods] 服务器 mods " + serverMods.size() + " 个");
        } else {
            Log.warn("[mods] 服务器资源包为空，只带核心 mods: " + serverDoc);
        }

        ModList list = new ModList();
        list.setMods(mods);
        return Json.write(list);
    }

    private List<ModEntry> getServerMods(String gameId, String resUrl, String resMd5) throws IOException {
        Files.createDirectories(cacheDir);
        Path md5File = cacheDir.resolve(gameId + ".MD5");
        Path jsonFile = cacheDir.resolve(gameId + ".json");
        if (Files.exists(md5File) && Files.exists(jsonFile)) {
            String cached = Files.readString(md5File, StandardCharsets.UTF_8).trim();
            if (cached.equalsIgnoreCase(resMd5)) {
                ModList list = Json.readValue(Files.readString(jsonFile, StandardCharsets.UTF_8), ModList.class);
                return list == null || list.getMods() == null ? new ArrayList<>() : list.getMods();
            }
        }

        Path archive = cacheDir.resolve(gameId + ".7z");
        Log.info("[mods] 下载服务器资源包 " + resUrl);
        HttpRequest req = HttpRequest.newBuilder(URI.create(resUrl)).GET().build();
        try (InputStream in = send(req)) {
            Files.copy(in, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        List<ModEntry> mods = new ArrayList<>();
        Path extracted = cacheDir.resolve(gameId + ".unpack");
        try {
            Archives.extract(archive, extracted);
            List<Path> jars = new ArrayList<>();
            try (var stream = Files.walk(extracted)) {
                stream.filter(Files::isRegularFile).forEach(jars::add);
            }
            for (Path jar : jars) {
                String normalized = extracted.relativize(jar).toString().replace('\\', '/');
                if (!normalized.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                        || !normalized.toLowerCase(java.util.Locale.ROOT).contains(".minecraft/mods/")) {
                    continue;
                }
                String md5 = md5Hex(jar);
                String name = jar.getFileName().toString();
                ModEntry entry = new ModEntry();
                entry.setModPath(name);
                entry.setId(name);
                entry.setIid(name.split("@")[0]);
                entry.setMd5(md5);
                mods.add(entry);
            }
        } finally {
            deleteQuietly(archive);
            deleteTreeQuietly(extracted);
        }
        mods.sort(Comparator.comparing(ModEntry::getModPath));

        ModList list = new ModList();
        list.setMods(mods);
        Files.writeString(jsonFile, Json.write(list), StandardCharsets.UTF_8);
        Files.writeString(md5File, resMd5, StandardCharsets.UTF_8);
        return mods;
    }

    /** 带 user-token 头签名的 JSON POST；code != 0 或非 2xx 抛异常。 */
    public JsonNode post(String host, String path, Object body, int userId, String userToken) throws IOException {
        String json = Json.write(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(host + path))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "WPFLauncher/" + launcherVersion)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        for (Map.Entry<String, String> kv : HttpToken.compute(path, json, String.valueOf(userId), userToken).entrySet()) {
            builder.header(kv.getKey(), kv.getValue());
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断: " + path, e);
        }
        String text = resp.body();
        JsonNode doc = Json.readTree(text);
        int code = doc.path("code").asInt(-1);
        if (resp.statusCode() / 100 != 2 || code != 0) {
            String message = doc.path("message").isTextual() ? doc.get("message").asText() : null;
            Log.debug("X19 " + path + " http=" + resp.statusCode() + " body=" + text);
            throw new IllegalStateException("X19 " + path + " 失败（code=" + code + "）"
                    + (message == null || message.isEmpty() ? text : message));
        }
        return doc;
    }

    private InputStream send(HttpRequest req) throws IOException {
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("下载失败 HTTP " + resp.statusCode() + ": " + req.uri());
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        }
    }

    private static String md5Hex(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("MD5 不可用", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 尽力清理
        }
    }

    private static void deleteTreeQuietly(Path path) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            try (var stream = Files.walk(path)) {
                stream.sorted(Comparator.reverseOrder()).forEach(ModInfoProvider::deleteQuietly);
            }
        } catch (IOException ignored) {
            // 尽力清理
        }
    }

    // ---------------------------------------------------------------- data classes

    /** 服务器地址。 */
    public record ServerAddress(String host, int port) {
    }

    /** modInfo 里的一条；字段名保持与 C# 的 JsonPropertyName 一致。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ModEntry {
        @JsonProperty("modPath")
        private String modPath = "";
        @JsonProperty("name")
        private String name = "";
        @JsonProperty("id")
        private String id = "";
        @JsonProperty("iid")
        private String iid = "";
        @JsonProperty("md5")
        private String md5 = "";
        @JsonProperty("version")
        private String version = "";

        public String getModPath() {
            return modPath;
        }

        public void setModPath(String value) {
            this.modPath = value;
        }

        public String getName() {
            return name;
        }

        public void setName(String value) {
            this.name = value;
        }

        public String getId() {
            return id;
        }

        public void setId(String value) {
            this.id = value;
        }

        public String getIid() {
            return iid;
        }

        public void setIid(String value) {
            this.iid = value;
        }

        public String getMd5() {
            return md5;
        }

        public void setMd5(String value) {
            this.md5 = value;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String value) {
            this.version = value;
        }
    }

    /** {"mods":[...]} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ModList {
        @JsonProperty("mods")
        private List<ModEntry> mods = new ArrayList<>();

        public List<ModEntry> getMods() {
            return mods;
        }

        public void setMods(List<ModEntry> value) {
            this.mods = value == null ? new ArrayList<>() : value;
        }
    }
}
