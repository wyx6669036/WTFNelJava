package org.wyx6669036.wtfnel.launch;

import org.wyx6669036.wtfnel.auth.yggdrasil.LaunchToken;
import org.wyx6669036.wtfnel.config.ShadowConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用内置启动模板（resources/launch-template.txt）拼出整条启动命令行。
 * 当前账号的 uid/token、所选角色与 uuid、本地路径、端口、服务器都在启动时填进去，不依赖外部文本文件。
 */
public final class LaunchCommand {

    /** 原生库解压目录（LWJGL/JNA/netty，相对 .minecraft），与官方启动器同名。 */
    private static final String NATIVE_CACHE = "bin\\d4abd45eda44d8516fe1c7a0c4f47a1dd04ab0d1";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)\\}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private LaunchCommand() {
    }

    public static String build(ShadowConfig cfg, String java, String gameId, String userToken) {
        Map<String, String> vars = new HashMap<>();
        vars.put("java", java);
        vars.put("natives_dir", esc(cfg.getNativeDir().toString()));
        vars.put("runtime_dir", esc(cfg.getRuntimeDir().toString()));
        vars.put("native_cache", NATIVE_CACHE);
        vars.put("auth_port", Integer.toString(cfg.getAuthPort()));
        vars.put("launcher_port", Integer.toString(cfg.getBridgePort()));
        vars.put("game_id", gameId);
        vars.put("user_id", Integer.toString(cfg.getUid()));
        vars.put("token", LaunchToken.encrypt(userToken));
        vars.put("max_memory", Integer.toString(cfg.getMaxMemoryMb()));
        vars.put("version", cfg.getVersionId());
        vars.put("username", quote(cfg.getUserName()));
        vars.put("uuid", cfg.getUuid());
        vars.put("client_id", cfg.getClientId());
        vars.put("xuid", cfg.getXuid());
        vars.put("quick_play", "quickPlay\\java\\" + Instant.now().toEpochMilli() + ".json");
        vars.put("filter_key", randomLower(32));
        vars.put("filter_path", esc(cfg.getFilterPath()));
        vars.put("launcher_version", cfg.getLauncherVersion());
        vars.put("launcher_brand", cfg.getLauncherBrand());
        vars.put("launcher_file_version", isBlank(cfg.getLauncherFileVersion())
                ? cfg.getLauncherVersion()
                : cfg.getLauncherFileVersion());
        vars.put("connect", cfg.isAutoConnect()
                ? "--quickPlayMultiplayer " + cfg.getConnectHost() + ":" + cfg.getConnectPort()
                : "");
        vars.put("extra_jvm", quoteAll(cfg.getExtraJvmArgs()));
        vars.put("extra_game", quoteAll(cfg.getExtraGameArgs()));

        String line = replacePlaceholders(loadTemplate(), vars);
        return line.replaceAll(" {2,}", " ").trim();
    }

    static String replacePlaceholders(String template, Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder(template.length() + 512);
        while (m.find()) {
            String value = vars.get(m.group(1));
            if (value == null) {
                throw new IllegalStateException("启动模板里有未知占位符 " + m.group());
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 读取内置模板：丢掉 '#' 注释行与空行，其余用空格连接。 */
    private static String loadTemplate() {
        InputStream in = LaunchCommand.class.getResourceAsStream("/launch-template.txt");
        if (in == null) {
            throw new IllegalStateException("内置启动模板缺失");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (!first) {
                    sb.append(' ');
                }
                sb.append(trimmed);
                first = false;
            }
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 路径里的反斜杠翻倍，避免被当成转义。 */
    private static String esc(String path) {
        return path.replace("\\", "\\\\");
    }

    /** 含空格/制表符/引号时才加引号。 */
    private static String quote(String v) {
        if (v.indexOf(' ') < 0 && v.indexOf('\t') < 0 && v.indexOf('"') < 0) {
            return v;
        }
        return "\"" + v.replace("\"", "\\\"") + "\"";
    }

    private static String quoteAll(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String v : values) {
            out.add(quote(v));
        }
        return String.join(" ", out);
    }

    private static String randomLower(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
