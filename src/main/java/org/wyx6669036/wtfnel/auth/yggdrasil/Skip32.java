package org.wyx6669036.wtfnel.auth.yggdrasil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Skip32 block cipher: turns userId into the authId and drives the character uuid. */
public final class Skip32 {

    private static final int[] F_TABLE = {
            163, 215, 9, 131, 248, 72, 246, 244, 179, 33, 21, 120, 153, 177, 175, 249, 231, 45, 77, 138,
            206, 76, 202, 46, 82, 149, 217, 30, 78, 56, 68, 40, 10, 223, 2, 160, 23, 241, 96, 104,
            18, 183, 122, 195, 233, 250, 61, 83, 150, 132, 107, 186, 242, 99, 154, 25, 124, 174, 229, 245,
            247, 22, 106, 162, 57, 182, 123, 15, 193, 147, 129, 27, 238, 180, 26, 234, 208, 145, 47, 184,
            85, 185, 218, 133, 63, 65, 191, 224, 90, 88, 128, 95, 102, 11, 216, 144, 53, 213, 192, 167,
            51, 6, 101, 105, 69, 0, 148, 86, 109, 152, 155, 118, 151, 252, 178, 194, 176, 254, 219, 32,
            225, 235, 214, 228, 221, 71, 74, 29, 66, 237, 158, 110, 73, 60, 205, 67, 39, 210, 7, 212,
            222, 199, 103, 24, 137, 203, 48, 31, 141, 198, 143, 170, 200, 116, 220, 201, 93, 92, 49, 164,
            112, 136, 97, 44, 159, 13, 43, 135, 80, 130, 84, 100, 38, 125, 3, 64, 52, 75, 28, 115,
            209, 196, 253, 59, 204, 251, 127, 171, 230, 62, 91, 165, 173, 4, 35, 156, 20, 81, 34, 240,
            41, 121, 113, 126, 255, 140, 14, 226, 12, 239, 188, 114, 117, 111, 55, 161, 236, 211, 142, 98,
            139, 134, 16, 232, 8, 119, 17, 190, 146, 79, 36, 197, 50, 54, 157, 207, 243, 166, 187, 172,
            94, 108, 169, 19, 87, 37, 181, 227, 189, 168, 58, 1, 5, 89, 42, 70,
    };

    /** "SaintSteve": the key used for both the authId and the uuid tail. */
    public static final byte[] ROLE_KEY = "SaintSteve".getBytes(StandardCharsets.UTF_8);

    private Skip32() {
    }

    private static int g(byte[] key, int k, int w) {
        int g1 = w >> 8;
        int g2 = w & 0xFF;
        int g3 = F_TABLE[g2 ^ (key[(4 * k) % 10] & 0xFF)] ^ g1;
        int g4 = F_TABLE[g3 ^ (key[(4 * k + 1) % 10] & 0xFF)] ^ g2;
        int g5 = F_TABLE[g4 ^ (key[(4 * k + 2) % 10] & 0xFF)] ^ g3;
        int g6 = F_TABLE[g5 ^ (key[(4 * k + 3) % 10] & 0xFF)] ^ g4;
        return (g5 << 8) + g6;
    }

    public static int encrypt(int value, byte[] key) {
        int wl = (((value >> 24) & 0xFF) << 8) + ((value >> 16) & 0xFF);
        int wr = (((value >> 8) & 0xFF) << 8) + (value & 0xFF);
        int k = 0;
        for (int i = 0; i < 12; i++) {
            wr ^= g(key, k, wl) ^ k;
            k++;
            wl ^= g(key, k, wr) ^ k;
            k++;
        }
        return ((wr >> 8) << 24) | ((wr & 0xFF) << 16) | ((wl >> 8) << 8) | (wl & 0xFF);
    }

    /**
     * The character uuid (--uuid): md5(roleName) with its last 4 bytes replaced by Skip32(userId),
     * then marked as a v4 uuid. Returned as 32 uppercase hex characters.
     */
    public static String roleUuid(String roleName, int userId) {
        byte[] b = md5(roleName.getBytes(StandardCharsets.UTF_8));
        int enc = encrypt(userId, ROLE_KEY);
        b[12] = (byte) (enc & 0xFF);
        b[13] = (byte) ((enc >>> 8) & 0xFF);
        b[14] = (byte) ((enc >>> 16) & 0xFF);
        b[15] = (byte) ((enc >>> 24) & 0xFF);
        b[6] = (byte) ((b[6] & 0x0F) | 0x40);
        b[8] = (byte) ((b[8] & 0x3F) | 0x80);
        return Bytes.hex(b);
    }

    public static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
