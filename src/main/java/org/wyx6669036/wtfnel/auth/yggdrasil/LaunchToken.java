package org.wyx6669036.wtfnel.auth.yggdrasil;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Encrypt/decrypt for the -DToken launch argument: AES-128-CBC with zero padding,
 * plaintext = 8 random chars + userToken(16) + 8 random chars, hex encoded.
 */
public final class LaunchToken {

    private static final byte[] KEY = "debbde3548928fab".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] IV = "afd4c5c5a7c456a1".getBytes(StandardCharsets.US_ASCII);
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private LaunchToken() {
    }

    private static String random8() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private static Cipher cipher(int mode) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(mode, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(IV));
        return c;
    }

    public static String encrypt(String userToken) {
        byte[] plain = (random8() + userToken + random8()).getBytes(StandardCharsets.US_ASCII);
        if (plain.length % 16 != 0) {
            plain = java.util.Arrays.copyOf(plain, (plain.length + 15) / 16 * 16);
        }
        try {
            return Bytes.hex(cipher(Cipher.ENCRYPT_MODE).doFinal(plain));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("-DToken 加密失败", e);
        }
    }

    /** Recovers the 16-char userToken from -DToken. */
    public static String decrypt(String hex) throws IOException {
        try {
            byte[] plain = cipher(Cipher.DECRYPT_MODE).doFinal(Bytes.unhex(hex));
            int len = plain.length;
            while (len > 0 && plain[len - 1] == 0) {
                len--;
            }
            String text = new String(plain, 0, len, StandardCharsets.US_ASCII);
            if (text.length() < 32) {
                throw new IOException("-DToken 解密结果长度异常");
            }
            return text.substring(8, text.length() - 8);
        } catch (GeneralSecurityException e) {
            throw new IOException("-DToken 解密失败: " + e.getMessage(), e);
        }
    }
}
