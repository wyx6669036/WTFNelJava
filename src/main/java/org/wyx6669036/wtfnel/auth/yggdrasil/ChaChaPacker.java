package org.wyx6669036.wtfnel.auth.yggdrasil;

import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.io.IOException;
import java.util.zip.CRC32;

/**
 * ChaCha8 stream cipher for the auth server TCP channel (RFC 7539 layout, 12-byte nonce).
 *
 * <p>Note the asymmetry inherited from the C# reference: {@link #pack} encrypts everything after the
 * 2-byte length field, while {@link #unpack} decrypts the whole frame including the length field. The
 * client packs what it sends and unpacks what the server sends, so this is the wire behaviour, not a bug
 * to be "fixed" — a local pack/unpack round trip therefore cannot succeed on either implementation.</p>
 */
public final class ChaChaPacker extends ChaCha7539Engine {

    /** [len:2][crc32:4][type:1][0x88 x3][data]; everything after len is encrypted. */
    public ChaChaPacker(byte[] key, byte[] iv, boolean encryption, int rounds) {
        this.rounds = rounds;
        init(encryption, new ParametersWithIV(new KeyParameter(key), iv));
    }

    public ChaChaPacker(byte[] key, byte[] iv, boolean encryption) {
        this(key, iv, encryption, 8);
    }

    public byte[] pack(byte type, byte[] data) {
        byte[] p = new byte[data.length + 10];
        int len = p.length - 2;
        p[0] = (byte) (len & 0xFF);
        p[1] = (byte) ((len >>> 8) & 0xFF);
        p[6] = type;
        p[7] = (byte) 0x88;
        p[8] = (byte) 0x88;
        p[9] = (byte) 0x88;
        System.arraycopy(data, 0, p, 10, data.length);
        // C# does `Crc32.Hash(...).CopyTo(p, 2)`, i.e. the raw little-endian bytes of the uint.
        int crc = crc32(p, 6, p.length - 6);
        p[2] = (byte) (crc & 0xFF);
        p[3] = (byte) ((crc >>> 8) & 0xFF);
        p[4] = (byte) ((crc >>> 16) & 0xFF);
        p[5] = (byte) ((crc >>> 24) & 0xFF);
        processBytes(p, 2, p.length - 2, p, 2);
        return p;
    }

    public Unpacked unpack(byte[] data) throws IOException {
        if (data.length < 8) {
            throw new IOException("认证响应过短");
        }
        processBytes(data, 0, data.length, data, 0);
        int expected = Bytes.readInt32Le(data, 0);
        int actual = crc32(data, 4, data.length - 4);
        if (expected != actual) {
            throw new IOException("认证响应校验失败");
        }
        byte type = data[4];
        return new Unpacked(type, Bytes.slice(data, 8, data.length));
    }

    public record Unpacked(byte type, byte[] data) {
    }

    private static int crc32(byte[] data, int off, int len) {
        CRC32 crc = new CRC32();
        crc.update(data, off, len);
        return (int) crc.getValue();
    }
}
