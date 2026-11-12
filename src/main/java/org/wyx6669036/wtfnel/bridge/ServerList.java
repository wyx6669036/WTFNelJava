package org.wyx6669036.wtfnel.bridge;

import org.wyx6669036.wtfnel.log.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ensures a server entry exists in .minecraft\servers.dat, the multiplayer list. This is the manual
 * fallback for auto-join: if the launcher bridge push does not land and the player ends up at the main
 * menu, the target server is already in the list, so clicking Multiplayer joins it in one step. The file
 * is uncompressed Java NBT; it is parsed and merged in place so an existing list is preserved.
 *
 * <p>Port of WtfNel.Core/Bridge/ServerList.cs.</p>
 */
public final class ServerList {

    private ServerList() {
    }

    public static void ensure(String gameDir, String ip, String name) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        Path path = Path.of(gameDir, "servers.dat");
        try {
            NbtCompound root = tryRead(path);
            if (root == null) {
                root = new NbtCompound();
            }
            Object servers = root.get("servers");
            NbtList list = servers instanceof NbtList l && l.elemType == 10 ? l : null;
            if (list == null) {
                list = new NbtList((byte) 10);
                root.set("servers", (byte) 9, list);
            }

            boolean exists = false;
            for (Object item : list.items) {
                if (item instanceof NbtCompound s && s.get("ip") instanceof String existing
                        && existing.equalsIgnoreCase(ip)) {
                    exists = true;
                    break;
                }
            }
            if (exists) {
                Log.info("[servers] 多人列表已含 " + ip);
                return;
            }

            NbtCompound entry = new NbtCompound();
            entry.set("name", (byte) 8, name);
            entry.set("ip", (byte) 8, ip);
            list.items.add(0, entry);

            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.write(path, write(root));
            Log.info("[servers] 已写入多人列表 " + name + " = " + ip);
        } catch (Exception e) {
            Log.warn("[servers] 写入 servers.dat 失败: " + e.getMessage());
        }
    }

    private static NbtCompound tryRead(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(path);
            if (data.length == 0 || data[0] != 10) {
                return null;
            }
            Cursor c = new Cursor(data, 1);
            readString(c);
            return readCompound(c);
        } catch (RuntimeException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static NbtCompound readCompound(Cursor c) {
        NbtCompound compound = new NbtCompound();
        while (true) {
            byte type = c.next();
            if (type == 0) {
                break;
            }
            String name = readString(c);
            compound.set(name, type, readPayload(c, type));
        }
        return compound;
    }

    private static Object readPayload(Cursor c, byte type) {
        switch (type) {
            case 1:
                return c.next();
            case 2:
                return c.int16();
            case 3:
                return c.int32();
            case 4:
                return c.int64();
            case 5:
                return Float.intBitsToFloat(c.int32());
            case 6:
                return Double.longBitsToDouble(c.int64());
            case 7: {
                int len = c.int32();
                return c.bytes(len);
            }
            case 8:
                return readString(c);
            case 9: {
                byte elem = c.next();
                int len = c.int32();
                NbtList list = new NbtList(elem);
                for (int i = 0; i < len; i++) {
                    list.items.add(readPayload(c, elem));
                }
                return list;
            }
            case 10:
                return readCompound(c);
            case 11: {
                int len = c.int32();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = c.int32();
                }
                return arr;
            }
            case 12: {
                int len = c.int32();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = c.int64();
                }
                return arr;
            }
            default:
                throw new IllegalStateException("未知 NBT 标签 " + type);
        }
    }

    private static String readString(Cursor c) {
        int len = c.uint16();
        return new String(c.bytes(len), StandardCharsets.UTF_8);
    }

    private static byte[] write(NbtCompound root) throws IOException {
        ByteArrayOutputStream ms = new ByteArrayOutputStream();
        ms.write(10);
        writeString(ms, "");
        writeCompound(ms, root);
        return ms.toByteArray();
    }

    private static void writeCompound(ByteArrayOutputStream s, NbtCompound c) throws IOException {
        for (Map.Entry<String, Object[]> e : c.entries.entrySet()) {
            byte type = (Byte) e.getValue()[0];
            s.write(type);
            writeString(s, e.getKey());
            writePayload(s, type, e.getValue()[1]);
        }
        s.write(0);
    }

    private static void writePayload(ByteArrayOutputStream s, byte type, Object val) throws IOException {
        switch (type) {
            case 1 -> s.write((Byte) val);
            case 2 -> s.write(be16((Short) val));
            case 3 -> s.write(be32((Integer) val));
            case 4 -> s.write(be64((Long) val));
            case 5 -> s.write(be32(Float.floatToIntBits((Float) val)));
            case 6 -> s.write(be64(Double.doubleToLongBits((Double) val)));
            case 7 -> {
                byte[] b = (byte[]) val;
                s.write(be32(b.length));
                s.write(b);
            }
            case 8 -> writeString(s, (String) val);
            case 9 -> {
                NbtList list = (NbtList) val;
                s.write(list.elemType);
                s.write(be32(list.items.size()));
                for (Object item : list.items) {
                    writePayload(s, list.elemType, item);
                }
            }
            case 10 -> writeCompound(s, (NbtCompound) val);
            case 11 -> {
                int[] arr = (int[]) val;
                s.write(be32(arr.length));
                for (int v : arr) {
                    s.write(be32(v));
                }
            }
            case 12 -> {
                long[] arr = (long[]) val;
                s.write(be32(arr.length));
                for (long v : arr) {
                    s.write(be64(v));
                }
            }
            default -> throw new IllegalStateException("未知 NBT 标签 " + type);
        }
    }

    private static void writeString(ByteArrayOutputStream s, String v) throws IOException {
        byte[] bytes = (v == null ? "" : v).getBytes(StandardCharsets.UTF_8);
        s.write((bytes.length >> 8) & 0xFF);
        s.write(bytes.length & 0xFF);
        s.write(bytes);
    }

    private static byte[] be16(short v) {
        return new byte[]{(byte) (v >> 8), (byte) v};
    }

    private static byte[] be32(int v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] be64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (v >> (56 - 8 * i));
        }
        return b;
    }

    /** Big-endian cursor over the raw NBT bytes. */
    private static final class Cursor {
        private final byte[] data;
        private int pos;

        Cursor(byte[] data, int pos) {
            this.data = data;
            this.pos = pos;
        }

        byte next() {
            return data[pos++];
        }

        int uint16() {
            int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        short int16() {
            return (short) uint16();
        }

        int int32() {
            int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        long int64() {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (data[pos + i] & 0xFFL);
            }
            pos += 8;
            return v;
        }

        byte[] bytes(int len) {
            byte[] out = new byte[len];
            System.arraycopy(data, pos, out, 0, len);
            pos += len;
            return out;
        }
    }

    /** Ordered compound: keeps the original key order so an untouched file round-trips readably. */
    private static final class NbtCompound {
        private final Map<String, Object[]> entries = new LinkedHashMap<>();

        Object get(String name) {
            Object[] entry = entries.get(name);
            return entry == null ? null : entry[1];
        }

        void set(String name, byte type, Object val) {
            entries.put(name, new Object[]{type, val});
        }
    }

    private static final class NbtList {
        private final byte elemType;
        private final List<Object> items = new ArrayList<>();

        NbtList(byte elemType) {
            this.elemType = elemType;
        }
    }
}
