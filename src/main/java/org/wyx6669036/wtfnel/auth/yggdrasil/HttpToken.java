package org.wyx6669036.wtfnel.auth.yggdrasil;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** The user-token request-header signature for the X19 HTTP APIs. */
public final class HttpToken {

    private HttpToken() {
    }

    public static Map<String, String> compute(String path, String body, String userId, String userToken) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        byte[] material = Bytes.concat(
                md5Hex(userToken.getBytes(StandardCharsets.UTF_8)),
                body.getBytes(StandardCharsets.UTF_8),
                "0eGsBkhl".getBytes(StandardCharsets.US_ASCII),
                normalized.getBytes(StandardCharsets.UTF_8));
        String digest = new String(md5Hex(material), StandardCharsets.UTF_8);

        StringBuilder bin = new StringBuilder(digest.length() * 8);
        for (int i = 0; i < digest.length(); i++) {
            String b = Integer.toBinaryString(digest.charAt(i));
            bin.append("0".repeat(8 - b.length())).append(b);
        }
        String bits = bin.toString();
        String rotated = bits.substring(6) + bits.substring(0, 6);

        byte[] bytes = digest.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < rotated.length() / 8; i++) {
            int mask = 0;
            for (int j = 0; j < 8; j++) {
                if (rotated.charAt(i * 8 + 7 - j) == '1') {
                    mask |= 1 << j;
                }
            }
            bytes[i] ^= (byte) mask;
        }
        String token = Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(bytes, 12)) + "1";
        token = token.replace('+', 'm').replace('/', 'o');

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("user-id", userId);
        headers.put("user-token", token);
        return headers;
    }

    private static byte[] md5Hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : Skip32.md5(data)) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
