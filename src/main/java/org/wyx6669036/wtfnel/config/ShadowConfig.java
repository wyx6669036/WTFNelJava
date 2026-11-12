package org.wyx6669036.wtfnel.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.wyx6669036.wtfnel.util.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime configuration. Every field can be overridden in shadow.json to switch machines or accounts.
 *
 * <p>Port of WtfNel.Core/Configuration/ShadowConfig.cs. JSON property names stay PascalCase
 * (matching the original files) while the Java fields are camelCase.</p>
 */
public class ShadowConfig {

    /** Default network server item id. */
    public static final String DEFAULT_GAME_ID = "4661334467366178884";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .propertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    /** The .minecraft root directory. Empty triggers auto-detection (see {@link GameDirLocator}). */
    private String gameDir = "";

    /**
     * Java used by the client. Empty uses the NetEase Java ({@code <MCL root>\ext\jre-v64-220420},
     * downloaded if missing); the client requires Java to live under MCLDownload, so a path elsewhere is ignored.
     */
    private String javaExe = "";

    /** The version directory name under versions. */
    private String versionId = "1.20";

    /** The program used as the client's parent process. Defaults to the WPFLauncher.exe stub next to the exe. */
    private String launcherPath = "WPFLauncher.exe";

    /** Launcher process name, used to reuse a running instance. */
    private String launcherProcessName = "WPFLauncher";

    /** Launcher build written into the placeholder's version resource; refreshed from the x19 patch list on every launch. */
    private String launcherFileVersion = "";

    /** -Dminecraft.launcher.brand, the official client reports "netease". */
    private String launcherBrand = "netease";

    /** filterpath of --userProperties; empty means %LOCALAPPDATA%\Netease\MCLauncher\config\fltmp. */
    private String filterPath = "";

    /** Rewrite the placeholder's version resource and the filter path, and point the MCLauncher key at it. */
    private boolean writeLauncherRegistry = true;

    /** Whether to create the client process under the launcher process as its parent. */
    private boolean useParentProcess = true;

    /** The real game server (direct IP/host). Empty or "auto" resolves it from X19 by gameId. */
    private String remoteHost = "auto";

    /** false = client connects directly to remoteHost:remotePort; true = connects to the local localPort. */
    private boolean useRelay = false;
    private int remotePort = 25565;

    /** Local forwarding port; the game connects here and traffic is relayed to remoteHost:remotePort. */
    private int localPort = 25565;

    /** Local bridge port, written as launcherport in the game's launch arguments. */
    private int bridgePort = 11655;

    /** Max game memory (-Xmx, in MB). */
    private int maxMemoryMb = 4096;

    private String userName = "";
    private String uuid = "";
    private int uid;
    private String clientId = "MjdhN2RkMDUtOGIzNy00MjFiLWE3OTQtNDllYzQ0MTY0ZWEz";
    private String xuid = "2535472070902804";

    /** The launcherVersion in userPropertiesEx. */
    private String launcherVersion = "1.15.31.20647";
    private int gameType = 2;

    /** The authlib local auth port, written as -DlauncherControlPort. */
    private int authPort = 11656;

    /** The crcSalt bound to the launcher version. */
    private String crcSalt = "2AFA3B6116260DE4300577E013EAC757";

    /** Launcher version used for authentication; empty uses {@link #getLauncherVersion()}. Must match crcSalt. */
    private String yggdrasilLauncherVersion = "1.15.34.21425";

    /** -DlauncherGameId: the network server item id to join (exceeds int). Empty uses {@link #DEFAULT_GAME_ID}. */
    private String launcherGameId = "";

    private String yggdrasilChannel = "netease";

    /** Fixed auth server host:port; empty picks one at random from authserver.list. */
    private String yggdrasilServer = "";

    /** Live login channel: empty = no login (use userToken); netease = email+password; cookie = sauth_json. */
    private String loginChannel = "";

    private String loginAccount = "";
    private String loginPassword = "";

    /** The sauth_json when loginChannel = cookie (or placed in cookie.txt next to the jars). */
    private String loginCookie = "";

    /** 16-character NetEase userToken. Empty derives it from the template -DToken; set, it also rewrites -DToken. */
    private String userToken = "";

    /** Game version name used in authentication (e.g. "1.20"). */
    private String yggGameVersion = "1.20";

    /** The mc_version_id for X19 APIs (1.20 = 1020000). */
    private int mcVersionId = 1020000;

    /** Empty looks up the built-in mapping by {@link #getYggGameVersion()}. */
    private String bootstrapMd5 = "";
    private String datFileMd5 = "";

    /** Before launch, fetch Java, the game itself, core mods and the server resource pack from the CDN. */
    private boolean autoInstall = true;

    /** Source .minecraft copied into the tree for files the NetEase CDN does not serve. */
    private String localFallbackDir = "";

    /** Connect to the server directly, or stay in the lobby. */
    private boolean autoConnect = true;

    /** Extra JVM arguments (inserted before the main class). */
    private List<String> extraJvmArgs = new ArrayList<>();

    /** Extra game arguments (appended at the end). */
    private List<String> extraGameArgs = new ArrayList<>();

    private String injectTarget = "Minecraft";

    private List<String> injectPayloads = new ArrayList<>();

    private String injectStrategy = "Auto";

    private int injectWaitSeconds = 180;

    private String injectInitExport = "";

    private boolean enableProxy = true;
    private boolean enableHwidSpoof = true;

    public static ShadowConfig load(Path path) {
        if (path == null || !Files.exists(path)) {
            return new ShadowConfig();
        }
        try {
            // 走 Text 而不是 Files.readString：记事本 / PowerShell 写出来的 JSON 常带 UTF-8 BOM，
            // 而 Jackson 会把 BOM 当成非法字符抛「Unexpected character (code 65279)」。
            String text = Text.readUtf8(path);
            ShadowConfig cfg = MAPPER.readValue(text, ShadowConfig.class);
            return cfg == null ? new ShadowConfig() : cfg;
        } catch (IOException e) {
            throw new IllegalStateException("读取配置失败: " + path + " - " + e.getMessage(), e);
        }
    }

    public void save(Path path) {
        try {
            Path dir = path.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.writeString(path, MAPPER.writeValueAsString(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("保存配置失败: " + path + " - " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- derived values

    @JsonIgnore
    public String getGameIdOrDefault() {
        return launcherGameId == null || launcherGameId.isBlank() ? DEFAULT_GAME_ID : launcherGameId.trim();
    }

    /** The client's auto-connect target: the real server directly, or the local forwarding port. */
    @JsonIgnore
    public String getConnectHost() {
        return useRelay ? "127.0.0.1" : remoteHost;
    }

    @JsonIgnore
    public int getConnectPort() {
        return useRelay ? localPort : remotePort;
    }

    @JsonIgnore
    public Path getLibraryDir() {
        return Path.of(gameDir, "libraries");
    }

    @JsonIgnore
    public Path getNativeDir() {
        return Path.of(gameDir, "versions", versionId, "natives");
    }

    @JsonIgnore
    public Path getRuntimeDir() {
        return getNativeDir().resolve("runtime");
    }

    @JsonIgnore
    public Path getVersionDir() {
        return Path.of(gameDir, "versions", versionId);
    }

    // ---------------------------------------------------------------- accessors

    public String getGameDir() {
        return gameDir;
    }

    public void setGameDir(String value) {
        this.gameDir = value;
    }

    public String getJavaExe() {
        return javaExe;
    }

    public void setJavaExe(String value) {
        this.javaExe = value;
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String value) {
        this.versionId = value;
    }

    public String getLauncherPath() {
        return launcherPath;
    }

    public void setLauncherPath(String value) {
        this.launcherPath = value;
    }

    public String getLauncherProcessName() {
        return launcherProcessName;
    }

    public void setLauncherProcessName(String value) {
        this.launcherProcessName = value;
    }

    public String getLauncherFileVersion() {
        return launcherFileVersion;
    }

    public void setLauncherFileVersion(String value) {
        this.launcherFileVersion = value;
    }

    public String getLauncherBrand() {
        return launcherBrand;
    }

    public void setLauncherBrand(String value) {
        this.launcherBrand = value;
    }

    public String getFilterPath() {
        return filterPath;
    }

    public void setFilterPath(String value) {
        this.filterPath = value;
    }

    public boolean isWriteLauncherRegistry() {
        return writeLauncherRegistry;
    }

    public void setWriteLauncherRegistry(boolean value) {
        this.writeLauncherRegistry = value;
    }

    public boolean isUseParentProcess() {
        return useParentProcess;
    }

    public void setUseParentProcess(boolean value) {
        this.useParentProcess = value;
    }

    public String getRemoteHost() {
        return remoteHost;
    }

    public void setRemoteHost(String value) {
        this.remoteHost = value;
    }

    public boolean isUseRelay() {
        return useRelay;
    }

    public void setUseRelay(boolean value) {
        this.useRelay = value;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public void setRemotePort(int value) {
        this.remotePort = value;
    }

    public int getLocalPort() {
        return localPort;
    }

    public void setLocalPort(int value) {
        this.localPort = value;
    }

    public int getBridgePort() {
        return bridgePort;
    }

    public void setBridgePort(int value) {
        this.bridgePort = value;
    }

    public int getMaxMemoryMb() {
        return maxMemoryMb;
    }

    public void setMaxMemoryMb(int value) {
        this.maxMemoryMb = value;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String value) {
        this.userName = value;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String value) {
        this.uuid = value;
    }

    public int getUid() {
        return uid;
    }

    public void setUid(int value) {
        this.uid = value;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String value) {
        this.clientId = value;
    }

    public String getXuid() {
        return xuid;
    }

    public void setXuid(String value) {
        this.xuid = value;
    }

    public String getLauncherVersion() {
        return launcherVersion;
    }

    public void setLauncherVersion(String value) {
        this.launcherVersion = value;
    }

    public int getGameType() {
        return gameType;
    }

    public void setGameType(int value) {
        this.gameType = value;
    }

    public int getAuthPort() {
        return authPort;
    }

    public void setAuthPort(int value) {
        this.authPort = value;
    }

    public String getCrcSalt() {
        return crcSalt;
    }

    public void setCrcSalt(String value) {
        this.crcSalt = value;
    }

    public String getYggdrasilLauncherVersion() {
        return yggdrasilLauncherVersion;
    }

    public void setYggdrasilLauncherVersion(String value) {
        this.yggdrasilLauncherVersion = value;
    }

    public String getLauncherGameId() {
        return launcherGameId;
    }

    public void setLauncherGameId(String value) {
        this.launcherGameId = value;
    }

    public String getYggdrasilChannel() {
        return yggdrasilChannel;
    }

    public void setYggdrasilChannel(String value) {
        this.yggdrasilChannel = value;
    }

    public String getYggdrasilServer() {
        return yggdrasilServer;
    }

    public void setYggdrasilServer(String value) {
        this.yggdrasilServer = value;
    }

    public String getLoginChannel() {
        return loginChannel;
    }

    public void setLoginChannel(String value) {
        this.loginChannel = value;
    }

    public String getLoginAccount() {
        return loginAccount;
    }

    public void setLoginAccount(String value) {
        this.loginAccount = value;
    }

    public String getLoginPassword() {
        return loginPassword;
    }

    public void setLoginPassword(String value) {
        this.loginPassword = value;
    }

    public String getLoginCookie() {
        return loginCookie;
    }

    public void setLoginCookie(String value) {
        this.loginCookie = value;
    }

    public String getUserToken() {
        return userToken;
    }

    public void setUserToken(String value) {
        this.userToken = value;
    }

    public String getYggGameVersion() {
        return yggGameVersion;
    }

    public void setYggGameVersion(String value) {
        this.yggGameVersion = value;
    }

    public int getMcVersionId() {
        return mcVersionId;
    }

    public void setMcVersionId(int value) {
        this.mcVersionId = value;
    }

    public String getBootstrapMd5() {
        return bootstrapMd5;
    }

    public void setBootstrapMd5(String value) {
        this.bootstrapMd5 = value;
    }

    public String getDatFileMd5() {
        return datFileMd5;
    }

    public void setDatFileMd5(String value) {
        this.datFileMd5 = value;
    }

    public boolean isAutoInstall() {
        return autoInstall;
    }

    public void setAutoInstall(boolean value) {
        this.autoInstall = value;
    }

    public String getLocalFallbackDir() {
        return localFallbackDir;
    }

    public void setLocalFallbackDir(String value) {
        this.localFallbackDir = value;
    }

    public boolean isAutoConnect() {
        return autoConnect;
    }

    public void setAutoConnect(boolean value) {
        this.autoConnect = value;
    }

    public List<String> getExtraJvmArgs() {
        return extraJvmArgs;
    }

    public void setExtraJvmArgs(List<String> value) {
        this.extraJvmArgs = value == null ? new ArrayList<>() : value;
    }

    public List<String> getExtraGameArgs() {
        return extraGameArgs;
    }

    public void setExtraGameArgs(List<String> value) {
        this.extraGameArgs = value == null ? new ArrayList<>() : value;
    }

    public String getInjectTarget() {
        return injectTarget;
    }

    public void setInjectTarget(String value) {
        this.injectTarget = value;
    }

    public List<String> getInjectPayloads() {
        return injectPayloads;
    }

    public void setInjectPayloads(List<String> value) {
        this.injectPayloads = value == null ? new ArrayList<>() : value;
    }

    public String getInjectStrategy() {
        return injectStrategy;
    }

    public void setInjectStrategy(String value) {
        this.injectStrategy = value;
    }

    public int getInjectWaitSeconds() {
        return injectWaitSeconds;
    }

    public void setInjectWaitSeconds(int value) {
        this.injectWaitSeconds = value;
    }

    public String getInjectInitExport() {
        return injectInitExport;
    }

    public void setInjectInitExport(String value) {
        this.injectInitExport = value;
    }

    public boolean isEnableProxy() {
        return enableProxy;
    }

    public void setEnableProxy(boolean value) {
        this.enableProxy = value;
    }

    public boolean isEnableHwidSpoof() {
        return enableHwidSpoof;
    }

    public void setEnableHwidSpoof(boolean value) {
        this.enableHwidSpoof = value;
    }
}
