package org.wyx6669036.wtfnel.proxy;

import org.wyx6669036.wtfnel.log.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Passive observer: splits packets by Minecraft's varint length prefix and records their ids.
 * Read-only, never alters a byte; if the stream is encrypted and framing fails, observation disables itself.
 *
 * <p>Port of the {@code PacketTap} class in WtfNel.Core/Proxy/GameRelay.cs.</p>
 */
public final class PacketTap {

    private static final int MAX_FRAME = 8 * 1024 * 1024;

    private final String tag;
    private final int session;
    private final boolean verbose;
    private final Map<Integer, Integer> histogram = new HashMap<>();

    private byte[] buf = new byte[64 * 1024];
    private int head;
    private int tail;
    private boolean disabled;
    private int frames;

    public PacketTap(String tag, int session, boolean verbose) {
        this.tag = tag;
        this.session = session;
        this.verbose = verbose;
    }

    /** True once framing failed and observation stopped; forwarding is unaffected. */
    public boolean isDisabled() {
        return disabled;
    }

    public int getFrames() {
        return frames;
    }

    public void feed(byte[] data, int off, int len) {
        if (disabled) {
            return;
        }
        ensureCapacity(len);
        System.arraycopy(data, off, buf, tail, len);
        tail += len;

        while (true) {
            int[] read = tryReadVarInt(0);
            if (read == null) {
                compact();
                return;
            }
            int length = read[0];
            int lenBytes = read[1];
            if (length < 0 || length > MAX_FRAME) {
                disabled = true;
                compact();
                return;
            }
            if (tail - head < lenBytes + length) {
                compact();
                return;
            }

            int id = -1;
            if (length >= 1) {
                int[] pid = tryReadVarInt(lenBytes);
                if (pid != null) {
                    id = pid[0];
                }
            }

            frames++;
            if (id >= 0) {
                histogram.merge(id, 1, Integer::sum);
                if (verbose && frames <= 200) {
                    Log.debug("[tap:" + tag + "#" + session + "] frame=" + frames + " id=0x"
                            + Integer.toHexString(id) + " len=" + length);
                }
            }

            head += lenBytes + length;

            if (id < 0) {
                disabled = true;
                compact();
                return;
            }
        }
    }

    public void summary() {
        if (frames == 0) {
            return;
        }
        StringBuilder top = new StringBuilder();
        histogram.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .forEach(e -> {
                    if (top.length() > 0) {
                        top.append(", ");
                    }
                    top.append("0x").append(Integer.toHexString(e.getKey())).append("×").append(e.getValue());
                });
        Log.info("[tap:" + tag + "#" + session + "] 观察 " + frames + " 帧，主包号 " + top);
    }

    private void ensureCapacity(int extra) {
        if (tail + extra <= buf.length) {
            return;
        }
        byte[] bigger = new byte[Math.max(buf.length * 2, tail + extra)];
        System.arraycopy(buf, head, bigger, 0, tail - head);
        tail -= head;
        head = 0;
        buf = bigger;
    }

    private void compact() {
        if (head == 0) {
            return;
        }
        if (head == tail) {
            head = 0;
            tail = 0;
            return;
        }
        System.arraycopy(buf, head, buf, 0, tail - head);
        tail -= head;
        head = 0;
    }

    /** Returns {value, bytes} or {@code null} when more data is needed / the varint is malformed. */
    private int[] tryReadVarInt(int off) {
        int value = 0;
        int num = 0;
        while (true) {
            if (head + off + num >= tail) {
                return null;
            }
            byte b = buf[head + off + num];
            value |= (b & 0x7F) << (7 * num);
            num++;
            if ((b & 0x80) == 0) {
                break;
            }
            if (num > 5) {
                return null;
            }
        }
        return new int[]{value, num};
    }
}
