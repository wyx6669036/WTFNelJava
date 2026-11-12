package org.wyx6669036.wtfnel.auth.yggdrasil;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Little-endian binary helpers shared by the auth protocol: length-prefixed strings and fixed frames. */
public final class Bytes {

    private Bytes() {
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    public static byte[] xor(byte[] a, byte[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("长度不一致");
        }
        byte[] r = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = (byte) (a[i] ^ b[i]);
        }
        return r;
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().toUpperCase(java.util.Locale.ROOT);
    }

    public static byte[] unhex(String hex) {
        String s = hex.trim();
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ---------------------------------------------------------------- little-endian writers

    public static void writeInt(ByteArrayOutputStream s, int v) {
        s.write(v & 0xFF);
        s.write((v >>> 8) & 0xFF);
        s.write((v >>> 16) & 0xFF);
        s.write((v >>> 24) & 0xFF);
    }

    public static void writeLong(ByteArrayOutputStream s, long v) {
        for (int i = 0; i < 8; i++) {
            s.write((int) ((v >>> (8 * i)) & 0xFF));
        }
    }

    public static void writeShort(ByteArrayOutputStream s, int v) {
        writeShort(s, v, true);
    }

    public static void writeShort(ByteArrayOutputStream s, int v, boolean littleEndian) {
        int lo = v & 0xFF;
        int hi = (v >>> 8) & 0xFF;
        s.write(littleEndian ? lo : hi);
        s.write(littleEndian ? hi : lo);
    }

    /** One-byte length prefix followed by UTF-8 bytes. */
    public static void writeByteString(ByteArrayOutputStream s, String v) {
        byte[] b = utf8(v);
        s.write(b.length & 0xFF);
        s.writeBytes(b);
    }

    /** Two-byte length prefix followed by UTF-8 bytes; the handshake uses this one in big-endian. */
    public static void writeShortString(ByteArrayOutputStream s, String v, boolean littleEndian) {
        byte[] b = utf8(v);
        writeShort(s, b.length, littleEndian);
        s.writeBytes(b);
    }

    public static void writeShortString(ByteArrayOutputStream s, String v) {
        writeShortString(s, v, true);
    }

    public static void writeShortBytes(ByteArrayOutputStream s, byte[] b) {
        writeShort(s, b.length);
        s.writeBytes(b);
    }

    // ---------------------------------------------------------------- little-endian readers

    public static int readInt32Le(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /** Reads [len:2 LE][payload]. */
    public static byte[] readInt16Frame(InputStream in) throws IOException {
        byte[] head = in.readNBytes(2);
        if (head.length < 2) {
            throw new EOFException("连接已关闭");
        }
        int len = (head[0] & 0xFF) | ((head[1] & 0xFF) << 8);
        if (len < 0) {
            throw new IOException("帧长度为负");
        }
        byte[] buf = in.readNBytes(len);
        if (buf.length < len) {
            throw new EOFException("帧数据不足: " + buf.length + "/" + len);
        }
        return buf;
    }

    public static byte[] slice(byte[] b, int from, int to) {
        return Arrays.copyOfRange(b, from, Math.min(to, b.length));
    }
}
