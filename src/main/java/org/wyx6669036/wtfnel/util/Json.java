package org.wyx6669036.wtfnel.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 全局共享的 Jackson 配置：宽松读取（注释、尾逗号、未知字段），紧凑写出。
 *
 * <p>对应 C# 里各处的 {@code JsonSerializerOptions { Encoder = UnsafeRelaxedJsonEscaping }}：
 * 中文不转义，输出与原实现一致。</p>
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            // accounts.json 用 LocalDateTime 存 lastUsed；不注册的话 Jackson 会直接拒绝序列化
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 解析成树；失败抛 {@link IllegalArgumentException}（调用方都是把响应体当契约）。 */
    public static JsonNode readTree(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getOriginalMessage(), e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    public static <T> T readValue(String text, Class<T> type) throws IOException {
        return MAPPER.readValue(text, type);
    }

    /** 取文本字段；数字等标量取其字面量，缺失返回空串（等价 C# 的 GetRawText）。 */
    public static String str(JsonNode node, String name) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(name);
        if (v == null || v.isNull()) {
            return "";
        }
        return v.isTextual() ? v.asText() : v.toString();
    }

    public static int intOr(JsonNode node, String name, int fallback) {
        JsonNode v = node == null ? null : node.get(name);
        return v != null && v.isNumber() ? v.asInt() : fallback;
    }
}
