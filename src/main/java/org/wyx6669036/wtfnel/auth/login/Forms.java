package org.wyx6669036.wtfnel.auth.login;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

/** Form/URL helpers shared by the login channels (equivalent to FormUrlEncodedContent + Uri.EscapeDataString). */
final class Forms {

    private Forms() {
    }

    /** RFC 3986 style escaping: percent-escapes UTF-8 bytes, keeps unreserved characters, encodes space as %20. */
    static String escape(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    static String encode(Map<String, String> values) {
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            joiner.add(escape(entry.getKey()) + "=" + escape(entry.getValue()));
        }
        return joiner.toString();
    }

    /** Parses a query string, keeping only well-formed key=value pairs (C# takes parts.Length == 2). */
    static Map<String, String> parseQuery(String url) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        int start = url.indexOf('?');
        String query = start < 0 ? url : url.substring(start + 1);
        for (String item : query.split("&")) {
            String[] parts = item.split("=");
            if (parts.length == 2) {
                map.put(parts[0], parts[1]);
            }
        }
        return map;
    }

    /** The value of one query parameter, or "" when absent. */
    static String queryValue(String url, String key) {
        String found = parseQuery(url).get(key);
        return found == null ? "" : found;
    }
}
