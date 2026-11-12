package org.wyx6669036.wtfnel.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.wyx6669036.wtfnel.util.AppPaths;
import org.wyx6669036.wtfnel.util.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The account list stored in accounts.json next to the exe, most recently used first.
 *
 * <p>Port of WtfNel.Core/Auth/AccountStore.cs.</p>
 */
public final class AccountStore {

    /**
     * accounts.json 用 PascalCase 键（Accounts/LastUsed/...），与 C# 原版写出的文件逐字节兼容，
     * 因此这里不能借用 {@link org.wyx6669036.wtfnel.util.Json} 的默认命名。
     */
    static final ObjectMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE)
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** A saved login account (stored in plain text in accounts.json). */
    public static final class SavedAccount {
        /** User-chosen display name. */
        private String name = "";
        /** netease = email + password; 4399pc / 4399com = 4399 username + password (stored in email); cookie = sauth_json. */
        private String channel = "netease";
        private String email = "";
        private String password = "";
        private String cookie = "";
        /** The userId from the most recent login, for display only. */
        private int userId;
        /** Last used time, stored as an ISO-8601 local date-time. */
        private LocalDateTime lastUsed;
        /** The character (sub-account) last used to enter the game. */
        private String lastRole = "";

        public String getName() {
            return name;
        }

        public void setName(String value) {
            this.name = value == null ? "" : value;
        }

        public String getChannel() {
            return channel;
        }

        public void setChannel(String value) {
            this.channel = value == null ? "" : value;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String value) {
            this.email = value == null ? "" : value;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String value) {
            this.password = value == null ? "" : value;
        }

        public String getCookie() {
            return cookie;
        }

        public void setCookie(String value) {
            this.cookie = value == null ? "" : value;
        }

        public int getUserId() {
            return userId;
        }

        public void setUserId(int value) {
            this.userId = value;
        }

        public LocalDateTime getLastUsed() {
            return lastUsed;
        }

        public void setLastUsed(LocalDateTime value) {
            this.lastUsed = value;
        }

        public String getLastRole() {
            return lastRole;
        }

        public void setLastRole(String value) {
            this.lastRole = value == null ? "" : value;
        }

        /** The credential for this channel: the cookie for cookie accounts, the password otherwise. */
        @JsonIgnore
        public String secret() {
            return "cookie".equals(channel) ? cookie : password;
        }

        @JsonIgnore
        public String channelLabel() {
            return switch (channel) {
                case "cookie" -> "Cookie";
                case "4399pc" -> "4399";
                case "4399com" -> "4399com";
                default -> "网易邮箱";
            };
        }
    }

    private List<SavedAccount> accounts = new ArrayList<>();

    public List<SavedAccount> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<SavedAccount> value) {
        this.accounts = value == null ? new ArrayList<>() : value;
    }

    /** accounts.json next to the exe. */
    public static Path filePath() {
        return AppPaths.baseDirectory().resolve("accounts.json");
    }

    /** Reads the account list; a missing or unreadable file yields an empty list. */
    public static AccountStore load() {
        Path path = filePath();
        if (!Files.isRegularFile(path)) {
            return new AccountStore();
        }
        try {
            AccountStore store = JSON.readValue(Text.readUtf8(path), AccountStore.class);
            return store == null ? new AccountStore() : store;
        } catch (IOException | RuntimeException e) {
            org.wyx6669036.wtfnel.log.Log.warn("[login] 读取 accounts.json 失败: " + e.getMessage());
            return new AccountStore();
        }
    }

    public void save() {
        try {
            Path path = filePath();
            Path dir = path.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.writeString(path, JSON.writeValueAsString(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入 accounts.json: " + e.getMessage(), e);
        }
    }

    /** Accounts ordered by most recent use. */
    public List<SavedAccount> ordered() {
        List<SavedAccount> copy = new ArrayList<>(accounts);
        copy.sort(Comparator.comparing(
                (SavedAccount a) -> a.getLastUsed(),
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return copy;
    }

    /**
     * 记下一个登录成功的账号（同渠道 + 同账号只留一条，更新 lastUsed）。
     * cookie 渠道把 sauth_json 存进 cookie 字段，其余渠道存进 password。
     */
    public static SavedAccount remember(String channel, String account, String secret, int userId) {
        AccountStore store = load();
        SavedAccount saved = store.find(channel, account);
        if (saved == null) {
            saved = new SavedAccount();
            store.getAccounts().add(saved);
        }
        saved.setChannel(channel);
        saved.setName(account);
        saved.setEmail(account);
        saved.setUserId(userId);
        saved.setLastUsed(LocalDateTime.now());
        if ("cookie".equalsIgnoreCase(channel)) {
            saved.setCookie(secret == null ? "" : secret);
            saved.setPassword("");
        } else {
            saved.setPassword(secret == null ? "" : secret);
        }
        store.save();
        return saved;
    }

    /** 记住账号最后用的角色，下次启动可以直接接上。 */
    public static void rememberRole(String channel, String account, String role) {
        AccountStore store = load();
        SavedAccount saved = store.find(channel, account);
        if (saved == null) {
            return;
        }
        saved.setLastRole(role);
        saved.setLastUsed(LocalDateTime.now());
        store.save();
    }

    /** 按渠道 + 账号找一条；找不到返回 null。 */
    public SavedAccount find(String channel, String account) {
        String ch = channel == null ? "" : channel.trim();
        String name = account == null ? "" : account.trim();
        for (SavedAccount saved : accounts) {
            if (!ch.equalsIgnoreCase(saved.getChannel())) {
                continue;
            }
            String other = "cookie".equalsIgnoreCase(ch) ? saved.getName() : saved.getEmail();
            if (name.equalsIgnoreCase(other == null ? "" : other)) {
                return saved;
            }
        }
        return null;
    }

    /** ISO-8601 form used when writing {@code lastUsed} to JSON. */
    public static String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
