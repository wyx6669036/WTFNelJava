package org.wyx6669036.wtfnel.auth.login;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;

/**
 * x19 core encrypted request body (same as FandNEL's HttpUtil): AES-CBC without padding, with the
 * IV and the key index appended.
 *
 * <p>Port of WtfNel.Core/Auth/Login/NeteaseLogin.cs (X19HttpCipher).</p>
 */
final class X19HttpCipher {

    private static final byte[][] KEYS = {
            bytes("MK6mipwmOUedplb6"), bytes("OtEylfId6dyhrfdn"), bytes("VNbhn5mvUaQaeOo9"), bytes("bIEoQGQYjKd02U0J"),
            bytes("fuaJrPwaH2cfXXLP"), bytes("LEkdyiroouKQ4XN1"), bytes("jM1h27H4UROu427W"), bytes("DhReQada7gZybTDk"),
            bytes("ZGXfpSTYUvcdKqdY"), bytes("AZwKf7MWZrJpGR5W"), bytes("amuvbcHw38TcSyPU"), bytes("SI4QotspbjhyFdT0"),
            bytes("VP4dhjKnDGlSJtbB"), bytes("UXDZx4KhZywQ2tcn"), bytes("NIK73ZNvNqzva4kd"), bytes("WeiW7qU766Q1YQZI"),
    };

    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private X19HttpCipher() {
    }

    static byte[] encrypt(byte[] body) {
        byte[] padded = new byte[(body.length + 16 + 15) / 16 * 16];
        System.arraycopy(body, 0, padded, 0, body.length);
        byte[] iv = randomLetters(16).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(iv, 0, padded, body.length, 16);
        byte flags = (byte) ((ThreadLocalRandom.current().nextInt(0, KEYS.length - 1) << 4) | 2);
        byte[] enc = aes(padded, KEYS[(flags >> 4) & 0xF], iv, Cipher.ENCRYPT_MODE);
        byte[] out = new byte[16 + enc.length + 1];
        System.arraycopy(iv, 0, out, 0, 16);
        System.arraycopy(enc, 0, out, 16, enc.length);
        out[out.length - 1] = flags;
        return out;
    }

    static byte[] decrypt(byte[] body) {
        if (body.length < 18) {
            return null;
        }
        byte flags = body[body.length - 1];
        byte[] iv = new byte[16];
        System.arraycopy(body, 0, iv, 0, 16);
        byte[] enc = new byte[body.length - 17];
        System.arraycopy(body, 16, enc, 0, enc.length);
        byte[] dec = aes(enc, KEYS[(flags >> 4) & 0xF], iv, Cipher.DECRYPT_MODE);
        int nonZero = 0;
        int end = dec.length - 1;
        while (nonZero < 16 && end >= 0) {
            if (dec[end--] != 0) {
                nonZero++;
            }
        }
        return java.util.Arrays.copyOf(dec, end + 1);
    }

    private static byte[] aes(byte[] data, byte[] key, byte[] iv, int mode) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES 运算失败: " + e.getMessage(), e);
        }
    }

    static String randomLetters(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        }
        return sb.toString();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
