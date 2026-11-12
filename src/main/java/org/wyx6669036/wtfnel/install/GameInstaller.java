package org.wyx6669036.wtfnel.install;

import com.fasterxml.jackson.databind.JsonNode;
import org.wyx6669036.wtfnel.auth.yggdrasil.ModInfoProvider;
import org.wyx6669036.wtfnel.config.ShadowConfig;
import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.util.AppPaths;
import org.wyx6669036.wtfnel.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 拉取客户端运行需要的文件（与 FandNEL 的 JavaRuntimeInstaller / MinecraftInstaller 同流程）：
 * <ol>
 *   <li>Java: x19.gdl.netease.com/jre-v64-220420.7z → &lt;MCL root&gt;\ext\jre-v64-220420（jre8 / jdk17）</li>
 *   <li>游戏本体: /game-patch-info 的 base + 版本包 → GameDir 的父目录；库包 → 版本 jar/json、forge、modlauncher 落进 .minecraft</li>
 *   <li>核心 mods: search-by-game → user-item-download-v2/get-list → 按 jar_md5 挑出的 jar 落进 mods</li>
 *   <li>服务器资源包: user-item-download-v2 → 包内 .minecraft 覆盖到 GameDir</li>
 * </ol>
 * 每个包解压后会写一个 &lt;package&gt;.installed 标记（内容为 md5），md5 没变就跳过。
 *
 * <p>移植自 WtfNel.Core/Install/GameInstaller.cs。</p>
 */
public final class GameInstaller {

    private static final String JRE_URL = "https://x19.gdl.netease.com/jre-v64-220420.7z";
    private static final String JRE_DIR_NAME = "jre-v64-220420";

    private final ShadowConfig cfg;
    private final ModInfoProvider x19;
    private final HttpClient http;

    public GameInstaller(ShadowConfig cfg, ModInfoProvider x19) {
        this.cfg = cfg;
        this.x19 = x19;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMinutes(2))
                .build();
    }

    // ---------------------------------------------------------------- derived paths

    /** .minecraft 的父目录（base/版本包的解压根，例如 D:\MCLDownload\Game）。 */
    public Path gameRoot() {
        return cfg.getGameDir() == null ? null : Path.of(cfg.getGameDir()).toAbsolutePath().normalize().getParent();
    }

    /** MCL 根（例如 D:\MCLDownload）；Java 在 ext 下，客户端要求 Java 位于 MCLDownload 内。 */
    public Path mclRoot() {
        Path root = gameRoot();
        return root == null ? null : root.getParent();
    }

    public Path cache() {
        Path mcl = mclRoot();
        return mcl == null ? null : mcl.resolve("cache").resolve("shadow-install");
    }

    /** 网易的 Java javaw.exe（&lt;1.16 用 jre8，否则 jdk17）。 */
    public Path javawPath() {
        Path mcl = mclRoot();
        if (mcl == null) {
            return null;
        }
        return mcl.resolve("ext").resolve(JRE_DIR_NAME)
                .resolve(cfg.getMcVersionId() >= 1016000 ? "jdk17" : "jre8")
                .resolve("bin").resolve("javaw.exe");
    }

    /**
     * 启动用的 Java：JavaExe 已配置、存在且位于 MCL 根下（客户端要求 Java 在 MCLDownload 内）时用它，
     * 否则用网易的 Java。
     */
    public Path launchJava() {
        String exe = cfg.getJavaExe();
        Path mcl = mclRoot();
        if (exe != null && !exe.isBlank() && mcl != null) {
            Path candidate = Path.of(exe).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate)
                    && candidate.startsWith(mcl.toString() + java.io.File.separator)) {
                return candidate;
            }
        }
        return javawPath();
    }

    // ---------------------------------------------------------------- main flow

    public void installAll(String gameId, String userToken) throws IOException {
        Log.info("[install] GameDir=" + cfg.getGameDir() + "  缓存=" + cache());
        ensureJava();
        ensureClient(userToken);
        ensureCoreMods(userToken);
        if (gameId != null && !gameId.isEmpty()) {
            ensureServerPack(gameId, userToken);
        } else {
            Log.warn("[install] 没有 gameId，跳过服务器资源包");
        }
        ensureLocalOnlyFiles();
        Log.info("[install] 完成");
    }

    /** CDN 不提供的文件：整个 natives\runtime 目录和 &lt;version&gt;.dat（只有官方启动器会放）。 */
    void ensureLocalOnlyFiles() {
        String version = cfg.getVersionId();
        Path gameDir = Path.of(cfg.getGameDir());
        Path runtimeRel = Path.of("versions", version, "natives", "runtime");
        Path datRel = Path.of("versions", version, version + ".dat");
        boolean needRuntime = !Files.isRegularFile(gameDir.resolve(runtimeRel).resolve("libenvsdk.dll"));
        boolean needDat = !Files.isRegularFile(gameDir.resolve(datRel));
        if (!needRuntime && !needDat) {
            return;
        }

        Path bundled = AppPaths.baseDirectory().resolve("LocalRuntime").resolve(version);
        boolean[] need = fill("内置 LocalRuntime",
                bundled.resolve("natives").resolve("runtime"), bundled.resolve(version + ".dat"),
                needRuntime, needDat);
        needRuntime = need[0];
        needDat = need[1];
        if (!needRuntime && !needDat) {
            return;
        }

        String fallback = cfg.getLocalFallbackDir();
        if (fallback != null && !fallback.isBlank() && Files.isDirectory(Path.of(fallback))) {
            Path src = Path.of(fallback);
            need = fill(fallback, src.resolve(runtimeRel), src.resolve(datRel), needRuntime, needDat);
            needRuntime = need[0];
            needDat = need[1];
        }

        if (needRuntime || needDat) {
            Log.warn("[install] 仍缺 " + (needRuntime ? runtimeRel + " " : "") + (needDat ? datRel.toString() : "")
                    + "（内置 LocalRuntime 里没有此版本，也没配 LocalFallbackDir）");
        }
    }

    /** 从一个来源拷 natives\runtime / &lt;version&gt;.dat 到 GameDir；返回 {还需要 runtime, 还需要 dat}。 */
    private boolean[] fill(String label, Path runtimeSrc, Path datSrc, boolean needRuntime, boolean needDat) {
        String version = cfg.getVersionId();
        Path gameDir = Path.of(cfg.getGameDir());
        if (needRuntime && Files.isDirectory(runtimeSrc)) {
            Path dst = gameDir.resolve("versions").resolve(version).resolve("natives").resolve("runtime");
            try (Stream<Path> stream = Files.walk(runtimeSrc)) {
                for (Path f : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                    copyFile(f, dst.resolve(runtimeSrc.relativize(f).toString()));
                }
            } catch (IOException e) {
                Log.warn("[install] 从 " + label + " 拷贝 natives\\runtime 失败: " + e.getMessage());
                return new boolean[]{needRuntime, needDat};
            }
            Log.info("[install] 从 " + label + " 拷贝 natives\\runtime");
            needRuntime = false;
        }
        if (needDat && Files.isRegularFile(datSrc)) {
            try {
                copyFile(datSrc, gameDir.resolve("versions").resolve(version).resolve(version + ".dat"));
                Log.info("[install] 从 " + label + " 拷贝 " + version + ".dat");
                needDat = false;
            } catch (IOException e) {
                Log.warn("[install] 从 " + label + " 拷贝 " + version + ".dat 失败: " + e.getMessage());
            }
        }
        return new boolean[]{needRuntime, needDat};
    }

    /** 按 McVersionId 选 Java（&lt;1.16 用 jre8，否则 jdk17）并返回 javaw.exe。 */
    public Path ensureJava() throws IOException {
        if (cfg.getMcVersionId() >= 1020006) {
            throw new UnsupportedOperationException("1.20.6+ 需要 Java 21，网易运行时包里没有；请在 JavaExe 指定");
        }
        Path dir = mclRoot().resolve("ext").resolve(JRE_DIR_NAME);
        Path javaw = javawPath();
        if (Files.isRegularFile(javaw)) {
            Log.info("[install] Java 已存在: " + javaw);
            return javaw;
        }

        Path archive = cache().resolve("jre-v64-220420.7z");
        FastDownloader.download(http, JRE_URL, archive, null);
        Archives.extract(archive, dir);
        tryDelete(archive);
        if (!Files.isRegularFile(javaw)) {
            throw new IOException("运行时包里没有对应的 Java: " + javaw);
        }
        Log.info("[install] Java 就绪: " + javaw);
        return javaw;
    }

    public void ensureClient(String userToken) throws IOException {
        JsonNode basePkg = patchInfo(0, userToken);
        JsonNode verPkg = patchInfo(cfg.getMcVersionId(), userToken);

        installArchive("base", Json.str(basePkg, "url"), Json.str(basePkg, "md5"), gameRoot());
        installArchive("ver-" + cfg.getMcVersionId(), Json.str(verPkg, "url"), Json.str(verPkg, "md5"), gameRoot());

        Path libDir = cache().resolve("corelib-" + cfg.getMcVersionId());
        if (installArchive("corelib-" + cfg.getMcVersionId(),
                Json.str(verPkg, "core_lib_url"), Json.str(verPkg, "core_lib_md5"), libDir)) {
            installCoreLibraries(libDir);
        }
    }

    private JsonNode patchInfo(int mcVersion, String userToken) throws IOException {
        JsonNode doc = x19.post(ModInfoProvider.MCL_HOST, "/game-patch-info",
                Map.of("mc_version", mcVersion), cfg.getUid(), userToken);
        return doc.path("entity");
    }

    /** 库包里只有少数文件进 .minecraft，其余（各版本的 _libs）按名字挑。 */
    void installCoreLibraries(Path sourceDir) throws IOException {
        String version = cfg.getVersionId();
        int count = 0;
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            for (Path src : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                String name = src.getFileName().toString();
                Path target = null;
                if (name.equals(version + ".jar") || name.equals(version + ".json")) {
                    target = cfg.getVersionDir().resolve(name);
                } else if (name.startsWith("forge-" + version + "-")) {
                    target = library("net/minecraftforge/forge", "forge-", name);
                } else if (name.startsWith("launchwrapper-")) {
                    target = library("net/minecraft/launchwrapper", "launchwrapper-", name);
                } else if (name.startsWith("MercuriusUpdater-")) {
                    target = library("net/minecraftforge/MercuriusUpdater", "MercuriusUpdater-", name);
                } else if (name.startsWith("modlauncher-")) {
                    target = library(name.startsWith("modlauncher-10.2.")
                            ? "net/minecraftforge/modlauncher" : "cpw/mods/modlauncher", "modlauncher-", name);
                }
                if (target == null) {
                    continue;
                }
                copyFile(src, target);
                count++;
            }
        }
        Log.info("[install] 库包落地 " + count + " 个文件");
    }

    Path library(String group, String prefix, String fileName) {
        String versionSegment = fileName.substring(prefix.length(), fileName.lastIndexOf('.') > 0
                ? fileName.lastIndexOf('.') : fileName.length());
        return cfg.getLibraryDir().resolve(group).resolve(versionSegment).resolve(fileName);
    }

    public void ensureCoreMods(String userToken) throws IOException {
        int uid = cfg.getUid();
        JsonNode doc = x19.post(ModInfoProvider.GATEWAY_HOST, "/game-auth-item-list/query/search-by-game",
                Map.of("mc_version_id", cfg.getMcVersionId(), "game_type", cfg.getGameType()), uid, userToken);
        List<Long> iids = new ArrayList<>();
        for (JsonNode n : doc.path("entity").path("iid_list")) {
            iids.add(n.asLong());
        }

        JsonNode details = x19.post(ModInfoProvider.GATEWAY_HOST, "/user-item-download-v2/get-list",
                Map.of("item_id_list", iids), uid, userToken);
        Path modsDir = Path.of(cfg.getGameDir()).resolve("mods");
        int fresh = 0;
        int total = 0;
        for (JsonNode comp : details.path("entities")) {
            JsonNode subs = comp.path("sub_entities");
            if (!subs.isArray() || subs.isEmpty()) {
                throw new IllegalStateException("核心 mod 缺少下载资源: " + comp);
            }
            JsonNode res = subs.get(0);
            String jarMd5 = Json.str(res, "jar_md5");
            String resName = Json.str(res, "res_name");
            String baseName = resName.lastIndexOf('.') > 0 ? resName.substring(0, resName.lastIndexOf('.')) : resName;
            String jarName = baseName + "@" + comp.path("mtypeid").asInt() + "@" + Json.str(comp, "entity_id") + ".jar";
            Path jarPath = modsDir.resolve(jarName);
            total++;
            if (Files.isRegularFile(jarPath) && FastDownloader.md5Matches(jarPath, jarMd5)) {
                continue;
            }

            String key = Json.str(res, "res_md5");
            Path archive = cache().resolve("mods").resolve(key + ".archive");
            Path extracted = cache().resolve("mods").resolve(key);
            FastDownloader.download(http, Json.str(res, "res_url"), archive, key);
            Archives.extract(archive, extracted);

            Path source = null;
            try (Stream<Path> stream = Files.walk(extracted)) {
                for (Path jar : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                    if (jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                            && FastDownloader.md5Matches(jar, jarMd5)) {
                        source = jar;
                        break;
                    }
                }
            }
            if (source == null) {
                throw new IllegalStateException("核心 mod 包里没有 md5=" + jarMd5 + " 的 jar: " + resName);
            }
            copyFile(source, jarPath);
            tryDelete(archive);
            tryDeleteDir(extracted);
            fresh++;
            Log.info("[install] 核心 mod " + jarName);
        }
        Log.info("[install] 核心 mods " + total + " 个（本次更新 " + fresh + "）");
    }

    public void ensureServerPack(String gameId, String userToken) throws IOException {
        JsonNode doc = x19.post(ModInfoProvider.MCL_HOST, "/user-item-download-v2",
                Map.of("item_id", gameId, "length", 0, "offset", 0), cfg.getUid(), userToken);
        JsonNode entity = doc.path("entity");
        JsonNode subs = entity.path("sub_entities");
        if (!entity.isObject() || !subs.isArray() || subs.isEmpty()) {
            Log.warn("[install] 服务器资源包为空");
            return;
        }
        JsonNode res = subs.get(0);
        Path dir = cache().resolve("server-" + gameId);
        if (!installArchive("server-" + gameId, Json.str(res, "res_url"), Json.str(res, "res_md5"), dir)
                && Files.isRegularFile(dir.resolve(".synced"))) {
            return;
        }

        Path mc = dir.resolve(".minecraft");
        if (!Files.isDirectory(mc)) {
            Log.warn("[install] 服务器资源包里没有 .minecraft 目录");
            return;
        }
        Path gameDir = Path.of(cfg.getGameDir());
        int n = 0;
        try (Stream<Path> stream = Files.walk(mc)) {
            for (Path f : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                copyFile(f, gameDir.resolve(mc.relativize(f).toString()));
                n++;
            }
        }
        Files.writeString(dir.resolve(".synced"), "");
        Log.info("[install] 服务器资源包同步 " + n + " 个文件到 GameDir");
    }

    /** 下载并解压；md5 标记一致时跳过。返回是否真的装了。 */
    boolean installArchive(String key, String url, String md5, Path destination) throws IOException {
        Path marker = cache().resolve(key + ".installed");
        if (md5 != null && !md5.isEmpty() && Files.isRegularFile(marker)
                && Files.readString(marker).trim().equalsIgnoreCase(md5)) {
            Log.info("[install] " + key + " 已是最新（md5=" + md5 + "）");
            return false;
        }
        Path archive = cache().resolve(key + ".7z");
        FastDownloader.download(http, url, archive, md5);
        Path synced = destination.resolve(".synced");
        Files.deleteIfExists(synced);
        Archives.extract(archive, destination);
        Files.createDirectories(cache());
        Files.writeString(marker, md5 == null ? "" : md5);
        tryDelete(archive);
        return true;
    }

    // ---------------------------------------------------------------- helpers

    static void copyFile(Path src, Path dst) throws IOException {
        Path parent = dst.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(dst)) {
            // C# 会先 SetAttributes(Normal) 再覆盖；Java 直接 REPLACE_EXISTING 覆盖
            Files.deleteIfExists(dst);
        }
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 尽力清理
        }
    }

    private static void tryDeleteDir(Path path) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            try (Stream<Path> stream = Files.walk(path)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 尽力清理
                    }
                });
            }
        } catch (IOException ignored) {
            // 尽力清理
        }
    }

    /** 下载 URL 的辅助：给需要直取流的调用方（如后端接口给出的是裸 URL）。 */
    URI uri(String url) {
        return URI.create(url);
    }
}
