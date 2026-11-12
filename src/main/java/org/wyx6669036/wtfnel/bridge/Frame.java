package org.wyx6669036.wtfnel.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Frame format on the local bridge port: a 2-byte little-endian length followed by the payload.
 * The first two bytes of the payload are the big-endian message id.
 *
 * <p>Port of WtfNel.Core/Bridge/Frame.cs.</p>
 */
public final class Frame {

    public static final int HEADER_SIZE = 2;
    public static final int MAX_PAYLOAD = 1 << 20;

    private Frame() {
    }

    /** Reads one frame; returns {@code null} on clean EOF or an invalid length. */
    public static byte[] read(InputStream s) throws IOException {
        byte[] head = new byte[2];
        if (!readExact(s, head)) {
            return null;
        }
        int len = (head[0] & 0xFF) | ((head[1] & 0xFF) << 8);
        if (len <= 0 || len > MAX_PAYLOAD) {
            return null;
        }
        byte[] payload = new byte[len];
        if (!readExact(s, payload)) {
            return null;
        }
        return payload;
    }

    public static void write(OutputStream s, byte[] payload) throws IOException {
        byte[] head = new byte[2];
        head[0] = (byte) (payload.length & 0xFF);
        head[1] = (byte) ((payload.length >> 8) & 0xFF);
        s.write(head);
        s.write(payload);
        s.flush();
    }

    private static boolean readExact(InputStream s, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = s.read(buf, off, buf.length - off);
            if (n <= 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    public static int messageId(byte[] payload) {
        return payload.length < 2 ? -1 : ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
    }

    public static int readU16(byte[] b, int off) {
        return ((b[off + 1] & 0xFF) << 8) | (b[off] & 0xFF);
    }

    public static long readU32(byte[] b, int off) {
        return ((long) (b[off + 3] & 0xFF) << 24) | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 1] & 0xFF) << 8) | (b[off] & 0xFF);
    }

    public static String hexDump(byte[] data) {
        return hexDump(data, 64);
    }

    public static String hexDump(byte[] data, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(data.length, max);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", data[i] & 0xFF)).append(' ');
        }
        if (data.length > max) {
            sb.append("... (").append(data.length).append("B)");
        }
        return sb.toString().stripTrailing();
    }
}
