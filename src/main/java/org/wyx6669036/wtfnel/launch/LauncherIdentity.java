package org.wyx6669036.wtfnel.launch;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.wyx6669036.wtfnel.config.ShadowConfig;
import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.util.AppPaths;
import org.wyx6669036.wtfnel.util.NativeAssets;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 让替身启动器通过网易客户端的原生校验：WPFLauncher.exe 的版本资源、MCLauncher 注册表键、
 * filter 目录，全部对齐到 x19 补丁表里发布的最新官方启动器版本。
 *
 * <p>移植自 WtfNel.Core/Launch/LauncherIdentity.cs。C# 用 FileVersionInfo 读版本资源，
 * 这里手写一个最小 VS_VERSION_INFO 解析器（只读 StringFileInfo 的固定几个键）。</p>
 */
public final class LauncherIdentity {

    public static final String PATCH_LIST_URL = "https://x19.update.netease.com/pl/x19_java_patchlist";

    private static final String FALLBACK_VERSION = "1.15.34.21425";

    private static final Pattern PATCH_ENTRY =
            Pattern.compile("^\\s*\"(\\d+\\.\\d+\\.\\d+\\.\\d+)\"\\s*:", Pattern.MULTILINE);

    private LauncherIdentity() {
    }

    public static String ensure(ShadowConfig cfg, String mclRoot) {
        String version = resolveVersion(cfg);
        cfg.setLauncherFileVersion(version);

        Path stub = resolveStub(cfg);
        prepareStub(stub, version);
        cfg.setFilterPath(ensureFilterPath());

        if (cfg.isWriteLauncherRegistry()) {
            ensureRegistry(mclRoot, version, stub.getParent());
        }

        Log.info("[launcher-id] 版本 " + version + "  替身 " + versionOf(stub) + "  路径 " + stub);
        return version;
    }

    public static String resolveVersion(ShadowConfig cfg) {
        String fetched = fetchLatestVersion();
        if (fetched != null && !fetched.isBlank()) {
            if (!fetched.equals(cfg.getLauncherFileVersion())) {
                Log.info("[launcher-id] 补丁表最新版本 " + fetched);
            }
            return fetched;
        }

        if (cfg.getLauncherFileVersion() != null && !cfg.getLauncherFileVersion().isBlank()) {
            Log.warn("[launcher-id] 拉取补丁表失败，沿用缓存版本 " + cfg.getLauncherFileVersion());
            return cfg.getLauncherFileVersion().trim();
        }

        Log.warn("[launcher-id] 拉取补丁表失败且没有缓存，使用内置版本 " + FALLBACK_VERSION);
        return FALLBACK_VERSION;
    }

    public static String fetchLatestVersion() {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(PATCH_LIST_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "WPFLauncher/1.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                Log.warn("[launcher-id] 补丁表 HTTP " + resp.statusCode());
                return null;
            }
            Matcher m = PATCH_ENTRY.matcher(resp.body());
            String last = null;
            while (m.find()) {
                last = m.group(1);
            }
            return last;
        } catch (Exception e) {
            Log.debug("[launcher-id] 补丁表请求异常：" + rootMessage(e));
            return null;
        }
    }

    public static Path resolveStub(ShadowConfig cfg) {
        Path base = appBaseDirectory();
        Path full = base.resolve(cfg.getLauncherPath()).toAbsolutePath().normalize();
        if (Files.exists(full)) {
            return full;
        }
        Path side = base.resolve("WPFLauncher.exe");
        if (Files.exists(side)) {
            return side;
        }
        // 程序目录下没有替身，就看 jar 里带没带（-PskipNative 打的包没有，只能报错）。
        Path fromJar = NativeAssets.installLauncher(base);
        return fromJar != null ? fromJar : full;
    }

    private static void prepareStub(Path stub, String version) {
        if (!Files.exists(stub)) {
            Log.warn("[launcher-id] 替身不存在：" + stub);
            return;
        }

        if (isAligned(stub, version)) {
            return;
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                patchBinary(stub, version);
                Log.info("[launcher-id] 已写入版本资源 " + version);
                return;
            } catch (IOException e) {
                if (attempt == 0) {
                    Log.info("[launcher-id] 替身被占用，结束同名进程后重试");
                    killByImagePath(stub);
                    sleep(200);
                    continue;
                }
                Log.warn("[launcher-id] 写版本资源失败：" + e.getMessage());
                return;
            } catch (Exception e) {
                Log.warn("[launcher-id] 写版本资源失败：" + rootMessage(e));
                return;
            }
        }
    }

    /** 读版本资源里的 Company/Product/OriginalFilename/FileVersion，与版本号比对。 */
    public static boolean isAligned(Path stub, String version) {
        try {
            Map<String, String> info = readVersionStrings(Files.readAllBytes(stub));
            if (info.isEmpty()) {
                return false;
            }
            return "NetEase".equalsIgnoreCase(info.get("CompanyName"))
                    && "我的世界启动器".equals(info.get("ProductName"))
                    && "WPFLauncher.exe".equalsIgnoreCase(info.get("OriginalFilename"))
                    && sameVersion(info.get("FileVersion"), version);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean sameVersion(String left, String right) {
        int[] a = parseVersion(left);
        int[] b = parseVersion(right);
        if (a == null || b == null) {
            return false;
        }
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(int[] v) {
        return v[0] + "." + v[1] + "." + v[2] + "." + v[3];
    }

    private static int[] parseVersion(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = text.trim().split("\\.");
        if (parts.length < 2) {
            return null;
        }
        int[] out = new int[4];
        for (int i = 0; i < Math.min(4, parts.length); i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /** 直接把新的 VS_VERSION_INFO 写回替身的资源槽。 */
    public static void patchBinary(Path path, String version) throws IOException {
        byte[] blob = buildBlob(version, true);
        byte[] pe = Files.readAllBytes(path);
        int[] slot = locateVersion(pe);
        int dataOffset = slot[0];
        int slotSize = slot[1];
        int sizeFieldOffset = slot[2];

        if (blob.length > slotSize) {
            blob = buildBlob(version, false);
        }
        if (blob.length > slotSize) {
            throw new IllegalStateException("版本资源槽不足：需要 " + blob.length + " 字节，可用 " + slotSize + " 字节");
        }

        byte[] write = new byte[slotSize];
        System.arraycopy(blob, 0, write, 0, blob.length);
        try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.position(dataOffset);
            channel.write(ByteBuffer.wrap(write));
            channel.position(sizeFieldOffset);
            channel.write(ByteBuffer.wrap(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(blob.length).array()));
            channel.force(true);
        }
    }

    private static String versionOf(Path path) {
        try {
            Map<String, String> info = readVersionStrings(Files.readAllBytes(path));
            if (info.isEmpty()) {
                return "无版本资源";
            }
            return info.get("CompanyName") + "/" + info.get("ProductName") + "/" + info.get("FileVersion");
        } catch (Exception e) {
            return "无版本资源";
        }
    }

    /** 结束镜像路径等于 path 的进程，好让被占用的替身可写。 */
    private static void killByImagePath(Path path) {
        String full = path.toAbsolutePath().normalize().toString();
        ProcessHandle.allProcesses().forEach(p -> {
            if (!p.isAlive()) {
                return;
            }
            String cmd = p.info().command().orElse(null);
            if (cmd != null && cmd.equalsIgnoreCase(full)) {
                p.destroyForcibly();
            }
        });
    }

    private static String ensureFilterPath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            localAppData = Path.of(System.getProperty("user.home"), "AppData", "Local").toString();
        }
        Path dir = Path.of(localAppData, "Netease", "MCLauncher", "config", "fltmp");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            Log.debug("[launcher-id] 创建 filter 目录失败：" + rootMessage(e));
        }
        return dir.toString();
    }

    private static void ensureRegistry(String mclRoot, String version, Path stubDir) {
        if (!ParentedLauncher.isWindows()) {
            return;
        }
        try {
            String install = null;
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, "Software\\Netease\\MCLauncher")) {
                install = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER,
                        "Software\\Netease\\MCLauncher", "InstallLocation");
            }
            if (installedLauncherOk(install)) {
                Log.info("[launcher-id] 系统里已有官方启动器，注册表保持原样：" + install);
                return;
            }

            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, "Software\\Netease\\MCLauncher");
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER,
                    "Software\\Netease\\MCLauncher", "InstallLocation", stubDir.toString());
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER,
                    "Software\\Netease\\MCLauncher", "DisplayVersion", version);
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER,
                    "Software\\Netease\\MCLauncher", "DownloadPath", mclRoot);
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER,
                    "Software\\Netease\\MCLauncher", "MinecraftBENeteasePath",
                    Path.of(mclRoot, "MinecraftBENetease").toString());
            Log.info("[launcher-id] 注册表 InstallLocation -> " + stubDir + "  DisplayVersion -> " + version);
        } catch (Exception e) {
            Log.warn("[launcher-id] 写注册表失败：" + rootMessage(e));
        }
    }

    private static boolean installedLauncherOk(String installLocation) {
        if (installLocation == null || installLocation.isBlank()) {
            return false;
        }
        Path exe = Path.of(installLocation, "WPFLauncher.exe");
        if (!Files.exists(exe)) {
            return false;
        }
        try {
            Map<String, String> info = readVersionStrings(Files.readAllBytes(exe));
            return "NetEase".equalsIgnoreCase(info.get("CompanyName"))
                    && "我的世界启动器".equals(info.get("ProductName"));
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 版本资源

    private static byte[] buildBlob(String version, boolean withExtras) {
        int[] v = parseVersion(version);
        if (v == null) {
            v = new int[]{0, 0, 0, 0};
        }

        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[]{"CompanyName", "NetEase"});
        pairs.add(new String[]{"FileDescription", "我的世界启动器"});
        pairs.add(new String[]{"FileVersion", version});
        pairs.add(new String[]{"OriginalFilename", "WPFLauncher.exe"});
        pairs.add(new String[]{"ProductName", "我的世界启动器"});
        pairs.add(new String[]{"ProductVersion", version});

        if (withExtras) {
            pairs.add(3, new String[]{"InternalName", "WPFLauncher.exe"});
            pairs.add(4, new String[]{"LegalCopyright", "Copyright \u00a9 NetEase"});
        }

        ByteArrayOutputStream table = new ByteArrayOutputStream();
        for (String[] kv : pairs) {
            byte[] value = (kv[1] + "\0").getBytes(StandardCharsets.UTF_16LE);
            writeAll(table, block(kv[0], kv[1].length() + 1, 1, value, new byte[0]));
        }

        byte[] stringTable = block("000004b0", 0, 1, new byte[0], table.toByteArray());
        byte[] stringFileInfo = block("StringFileInfo", 0, 1, new byte[0], stringTable);
        byte[] translation = block("Translation", 4, 0,
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x04B00000).array(), new byte[0]);
        byte[] varFileInfo = block("VarFileInfo", 0, 1, new byte[0], translation);

        byte[] children = new byte[stringFileInfo.length + varFileInfo.length];
        System.arraycopy(stringFileInfo, 0, children, 0, stringFileInfo.length);
        System.arraycopy(varFileInfo, 0, children, stringFileInfo.length, varFileInfo.length);

        return block("VS_VERSION_INFO", 52, 0, fixedInfo(v[0], v[1], v[2], v[3]), children);
    }

    private static byte[] fixedInfo(int v1, int v2, int v3, int v4) {
        ByteBuffer buf = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0xFEEF04BD);
        buf.putInt(0x00010000);
        buf.putInt((v1 << 16) | v2);
        buf.putInt((v3 << 16) | v4);
        buf.putInt((v1 << 16) | v2);
        buf.putInt((v3 << 16) | v4);
        buf.putInt(0x3F);
        buf.putInt(0);
        buf.putInt(0x40004);
        buf.putInt(1);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(0);
        return buf.array();
    }

    private static byte[] block(String key, int valueLength, int type, byte[] value, byte[] children) {
        ByteArrayOutputStream ms = new ByteArrayOutputStream();
        writeAll(ms, new byte[6]);
        writeAll(ms, (key + "\0").getBytes(StandardCharsets.UTF_16LE));
        align(ms);
        if (value.length > 0) {
            writeAll(ms, value);
        }
        align(ms);
        if (children.length > 0) {
            writeAll(ms, children);
        }

        byte[] arr = ms.toByteArray();
        if (arr.length > 0xFFFF) {
            throw new IllegalStateException("版本资源块过大");
        }
        arr[0] = (byte) (arr.length & 0xFF);
        arr[1] = (byte) (arr.length >> 8);
        arr[2] = (byte) (valueLength & 0xFF);
        arr[3] = (byte) (valueLength >> 8);
        arr[4] = (byte) (type & 0xFF);
        arr[5] = (byte) (type >> 8);
        return arr;
    }

    private static void align(ByteArrayOutputStream ms) {
        while (ms.size() % 4 != 0) {
            ms.write(0);
        }
    }

    private static void writeAll(ByteArrayOutputStream ms, byte[] data) {
        ms.write(data, 0, data.length);
    }

    /** 只解析顶层 VS_VERSION_INFO → StringFileInfo → 语言表 → 各键值。 */
    static Map<String, String> readVersionStrings(byte[] pe) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            int[] slot = locateVersion(pe);
            int offset = slot[0];
            int size = slot[1];
            readBlock(pe, offset, offset + size, out);
        } catch (Exception ignored) {
            // 解析失败按“没有版本资源”处理
        }
        return out;
    }

    /**
     * 递归遍历版本资源块。块头 6 字节（wLength/wValueLength/wType），随后是 UTF-16 键名，
     * 4 字节对齐后是值，再对齐后是子块。type==1 表示文本值，wValueLength 以字符计（含结尾 0），
     * 其它情况以字节计。
     */
    private static void readBlock(byte[] pe, int start, int limit, Map<String, String> out) {
        if (start + 6 > limit) {
            return;
        }
        int length = u16(pe, start);
        int valueLength = u16(pe, start + 2);
        int type = u16(pe, start + 4);
        if (length <= 0 || start + length > limit) {
            return;
        }

        int keyStart = start + 6;
        int keyEnd = readUtf16z(pe, keyStart, start + length);
        String key = new String(pe, keyStart, Math.max(0, keyEnd - keyStart), StandardCharsets.UTF_16LE);
        int valueStart = align4(keyEnd + 2);
        int valueBytes = type == 1 ? valueLength * 2 : valueLength;
        int childStart = align4(valueStart + valueBytes);

        if (valueBytes > 0 && !"VS_VERSION_INFO".equals(key)) {
            int strEnd = readUtf16z(pe, valueStart, Math.min(start + length, valueStart + valueBytes));
            out.put(key, new String(pe, valueStart, Math.max(0, strEnd - valueStart), StandardCharsets.UTF_16LE));
        }

        while (childStart + 6 <= start + length) {
            int childLength = u16(pe, childStart);
            if (childLength <= 0) {
                break;
            }
            readBlock(pe, childStart, start + length, out);
            childStart = align4(childStart + childLength);
        }
    }

    private static int align4(int value) {
        return (value + 3) & ~3;
    }

    private static int readUtf16z(byte[] pe, int start, int limit) {
        int i = start;
        while (i + 1 < limit && (pe[i] != 0 || pe[i + 1] != 0)) {
            i += 2;
        }
        return i;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    /** 返回 {数据偏移, 槽大小, 大小字段偏移}。 */
    static int[] locateVersion(byte[] pe) {
        if (pe.length < 0x40) {
            throw new IllegalStateException("PE 头无效");
        }
        int lfanew = i32(pe, 0x3C);
        if (lfanew <= 0 || lfanew + 24 > pe.length) {
            throw new IllegalStateException("PE 头无效");
        }
        if (u32(pe, lfanew) != 0x00004550) {
            throw new IllegalStateException("PE 签名无效");
        }

        int coff = lfanew + 4;
        int sectionCount = u16(pe, coff + 2);
        int optionalSize = u16(pe, coff + 16);
        int optional = coff + 20;
        int magic = u16(pe, optional);
        int dirOffset = magic == 0x20B ? optional + 112 : optional + 96;
        if (dirOffset + 16 > pe.length) {
            throw new IllegalStateException("数据目录越界");
        }

        int resourceRva = u32(pe, dirOffset + 16);
        if (resourceRva == 0) {
            throw new IllegalStateException("没有资源节");
        }

        int sectionTable = optional + optionalSize;
        int resourceBase = rvaToOffset(pe, sectionTable, sectionCount, resourceRva);

        int versionDir = findEntry(pe, resourceBase, resourceBase, 16, true);
        if (versionDir < 0) {
            throw new IllegalStateException("没有 RT_VERSION");
        }

        int nameDir = findEntry(pe, versionDir, resourceBase, 1, true);
        if (nameDir < 0) {
            throw new IllegalStateException("没有 VS_VERSION_INFO 资源");
        }

        int leafOffset = firstLeaf(pe, nameDir);
        if (leafOffset < 0) {
            throw new IllegalStateException("版本资源没有语言项");
        }

        int dataEntry = rvaToOffset(pe, sectionTable, sectionCount, resourceRva + leafOffset);
        if (dataEntry + 16 > pe.length) {
            throw new IllegalStateException("资源数据项越界");
        }

        int dataRva = u32(pe, dataEntry);
        int dataSize = u32(pe, dataEntry + 4);
        int dataOffset = rvaToOffset(pe, sectionTable, sectionCount, dataRva);
        if (dataSize == 0) {
            throw new IllegalStateException("版本资源长度异常");
        }
        if (dataOffset + dataSize > pe.length) {
            throw new IllegalStateException("版本资源越界");
        }

        return new int[]{dataOffset, dataSize, dataEntry + 4};
    }

    private static int findEntry(byte[] pe, int dirOffset, int resourceBase, int wanted, boolean directory) {
        int count = u16(pe, dirOffset + 12) + u16(pe, dirOffset + 14);
        for (int i = 0; i < count; i++) {
            int entry = dirOffset + 16 + i * 8;
            if (entry + 8 > pe.length) {
                return -1;
            }
            long name = u32(pe, entry) & 0xFFFFFFFFL;
            if (name != wanted) {
                continue;
            }
            long target = u32(pe, entry + 4) & 0xFFFFFFFFL;
            boolean isDir = (target & 0x80000000L) != 0;
            if (isDir != directory) {
                return -1;
            }
            return resourceBase + (int) (target & 0x7FFFFFFFL);
        }
        return -1;
    }

    private static int firstLeaf(byte[] pe, int dirOffset) {
        int count = u16(pe, dirOffset + 12) + u16(pe, dirOffset + 14);
        if (count == 0) {
            return -1;
        }
        int entry = dirOffset + 16;
        if (entry + 8 > pe.length) {
            return -1;
        }
        return (int) (u32(pe, entry + 4) & 0x7FFFFFFFL);
    }

    private static int rvaToOffset(byte[] pe, int sectionTable, int sectionCount, int rva) {
        long unsignedRva = rva & 0xFFFFFFFFL;
        for (int i = 0; i < sectionCount; i++) {
            int s = sectionTable + i * 40;
            if (s + 40 > pe.length) {
                break;
            }
            long virtualSize = u32(pe, s + 8) & 0xFFFFFFFFL;
            long virtualAddress = u32(pe, s + 12) & 0xFFFFFFFFL;
            long rawSize = u32(pe, s + 16) & 0xFFFFFFFFL;
            long rawPointer = u32(pe, s + 20) & 0xFFFFFFFFL;
            long span = Math.max(virtualSize, rawSize);
            if (unsignedRva < virtualAddress || unsignedRva >= virtualAddress + span) {
                continue;
            }
            long delta = unsignedRva - virtualAddress;
            if (delta >= rawSize) {
                break;
            }
            return (int) (rawPointer + delta);
        }
        throw new IllegalStateException(String.format("RVA 0x%x 无法映射到文件偏移", rva));
    }

    private static int i32(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int u32(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** 与 C# 的 AppContext.BaseDirectory 对齐：替身按程序所在目录解析，不看当前工作目录。 */
    private static Path appBaseDirectory() {
        return AppPaths.baseDirectory();
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
