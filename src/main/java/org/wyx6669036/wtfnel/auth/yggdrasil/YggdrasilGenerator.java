package org.wyx6669036.wtfnel.auth.yggdrasil;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Local generation of the handshake and join packets. */
public final class YggdrasilGenerator {

    private static final String PRC_CHECK = "[]";

    private static final byte[] MC_VERSION_SALT = {1, 0, 4, (byte) 128, (byte) 210, 62, (byte) 247, 17, 1};
    private static final byte[] TCP_SALT = {
            47, (byte) 132, (byte) 174, (byte) 163, (byte) 153, 33, 41, 38,
            (byte) 218, (byte) 191, (byte) 149, (byte) 163, (byte) 171, (byte) 175, 55, (byte) 224,
    };

    private static final String PUBLIC_KEY_B64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA4HJFrYdVTeoSvH6qsnJElfXuf7FnxxFQdz3gRCs66LDrZfaoGoWt2e/aGIOv8uGHliBWnZP42Ike9Qf5aiYVtQ4mlj2bXZjifHG35LlS1Bq6yCA6k1WevWcrGWOuLzny3jo8Wbdi0lIFMTT2hN98sF2k4YcvyE9zhqxfRNFGVI5kLyxm9CeTKAXGBU5mw3yQWJ8cPRR4866jpGGOhBWlJdilWt2NES9bid8SbhTT55wqumnVO5J5/DaMyTgKIQngH7NyZQljAhdK5I23dzpGop322n2eQ+mTNLuquwU453o1cbyQobgC6vh5/F1QT2INBR2qYCnRzzJ6hrhE5kIMZwIDAQAB";
    private static final String PRIVATE_KEY_B64 = "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQDobJGIddxcs08xzTIVFanc4/84J1DbxiW7wLokIrap3txzyyMXj+AcDa8jLopLJkMg9rZLzL50Dwp+hmTTgiIcjkM1DVREsRBltzqjVNyiYPL2VGHLn+/eEivjhLNWUMcWrlAoJ5JJBi22oGPLlKDVJpg33JPI5nPZpwufdB0ecn2V0CAeeGyswQyAaqIjoiYOoP3HipjEYGQHp1RsADf4ozGRgv+2HGiSOyhlv00ixnF0nRUTzVh18ka0N3LdLMpAN/YAPkO8tmoWp0asyU3X+nyZFd29povvrRy4rL1lYmo8jdpfpSL+Yk+9+RybjBAhXRx6uDoBxaa2kwE4fyrrAgMBAAECggEAWT42szruHfoLkofDjyz+R/6TZLBT788pdeoOjwl1McyMwTlihA2Oc7cdZFjeaPSMGgAhBwHarx2HXgWkeUIibuyBCcHQdX+3WBb+wPA4t3CaWdMUqecDZzV6/KVbZu0lRKQxyvlGxhtFOjZjmyu6hZ2IHQrpA97Y5N2rLNKcy69W+QYJZappmBfbVgWM0NRmgmpg6siQ0Cm6Ryil3SBAPBVv+EUPiD9jdXbtVq7VwN4YmwUGScp2Fib1oUnqEAja1hfihVnRFQ246nKXhIc/YVrNmwBrxAVwaaPFRka6XjKkSF0WVbpqQLXhbY1fS8FoXGpVhiF6o4rTQJbpQxhQgQKBgQD7XysfPNt9G63gTrkZvjvEk5LKsRG42MAYEkuzxEal9E/AQv0jJrk0f1WO09hCcYQeaOQXhM9mezNQv5jXEnmepXqM3NTw1Di5yh3uvjSXQjdUt+7haNw+QjggBqZxyQZjtadYairSzfmWe7OwJIkCmdgaJKxm4qMExk9kUgApGwKBgQDstBdJHU/KEBqVpsIlu185vFFuaAvxiHjXHqGwytJMQ/5aVqaphIiQCAxaEogPSzPSm28UHVQiZeFO059EKOpSJscW4pV95Dr5BAbHuYecacqnZKbQqb69//Cfpne9tGYXlmP6QnYfPoc4wYfTfPyU2x3KtDhVxtEDutpSNU1ycQKBgBabWn92s66uvJZ9vfvotetZ8ku0XQmoxK3lh1Vlg40NSdbar3Vn2CQ2h3VO7BYdq2oouMq8sQJgdh7+/DnreXChJUJh4ey+yVM8MDD2fjhURjGiUSOIkLYwsmd+8Z0uHRr+jUxQUAWhbJ7yBRkEUCYhu+OuBKtEGrElPKKjFUydAoGAQLj9pQBe0OGWY1U1wRt67k6P9aB9o42tfSTjEXRkDHaLFiibab7TmI6a0gY/Le9iPDREKzvZxY4WDXfQFNMbP1tbFObf+Yxuk6iGMhaI/jvvLdZXxrajcVCKex0JoNWzFMAKlmOV6PUwBFTmzu1eI1XGz6Z3wPycKmjtSY1JoAECgYBBOfaUDMaG1xLzv+q1jPPs2U4lXPK2BXFE5RaliUGC+LIQREXPishII2LYFW3gtXj5QWfIGq6x0d6ca6Bja2vYRDDe5tlT/2VbZahiHpb2PYL/2WgeoHl7sT9Bb/nsKyo85Sv+doop6huy4+aeTiQHgrGR9JYMVBSIx6P8Tt5phA==";

    private final YggdrasilData data;
    private final AsymmetricKeyParameter publicKey;
    private final AsymmetricKeyParameter privateKey;

    public YggdrasilGenerator(YggdrasilData data) {
        this.data = data;
        try {
            this.publicKey = Rsa.loadPublicKey(PUBLIC_KEY_B64);
            this.privateKey = Rsa.loadPrivateKey(PRIVATE_KEY_B64);
        } catch (IOException e) {
            throw new IllegalStateException("内置 RSA 密钥解析失败", e);
        }
    }

    /** The built-in client signing key (exposed for golden-vector tests). */
    AsymmetricKeyParameter privateKeyForTest() {
        return privateKey;
    }

    /** Handshake frame (with its 2-byte length prefix): authId + AES(loginSeed) + version/channel + signature. */
    public byte[] generateInitializeMessage(GameProfile profile, byte[] loginSeed, byte[] signContent) throws IOException {
        byte[] plain = Rsa.pkcs1(publicKey, signContent, false);
        if (plain.length < 51) {
            throw new IOException("签名内容长度无效");
        }
        byte[] clientKey = Bytes.slice(plain, 0, 19);
        byte[] expected = Skip32.sha256(loginSeed);
        for (int i = 0; i < 32; i++) {
            if (plain[19 + i] != expected[i]) {
                throw new IOException("服务端签名校验失败");
            }
        }
        return buildInitializeFrame(profile, loginSeed, clientKey);
    }

    /**
     * Everything {@link #generateInitializeMessage} does after the server signature check. Split out so
     * the frame layout can be compared byte-for-byte against the C# reference without the server key.
     */
    byte[] buildInitializeFrame(GameProfile profile, byte[] loginSeed, byte[] clientKey) throws IOException {
        int authId = profile.authId();
        byte[] seed = aesEcbNoPadding(loginSeed, profile.authToken());
        byte[] signHash = Skip32.sha256(buildSign(profile, authId, seed));
        byte[] sign = Rsa.pkcs1(privateKey, Bytes.concat(clientKey, signHash), true);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Bytes.writeInt(body, authId);
        body.writeBytes(seed);
        Bytes.writeShortString(body, data.launcherVersion(), false);
        Bytes.writeByteString(body, data.channel());
        body.writeBytes(TCP_SALT);
        Bytes.writeShortBytes(body, sign);
        Bytes.writeByteString(body, profile.gameVersion);
        body.writeBytes(MC_VERSION_SALT);

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        Bytes.writeShort(frame, body.size());
        frame.writeBytes(body.toByteArray());
        return frame.toByteArray();
    }

    /** The join message (the plaintext payload of a type-9 packet). */
    public byte[] generateJoinMessage(GameProfile profile, String serverId, byte[] loginSeed) {
        return generateJoinMessage(profile, serverId, loginSeed, (int) (System.currentTimeMillis() / 1000L));
    }

    /** Same as {@link #generateJoinMessage(GameProfile, String, byte[])} with an injectable timestamp (for golden vectors). */
    public byte[] generateJoinMessage(GameProfile profile, String serverId, byte[] loginSeed, int now) {
        ByteArrayOutputStream ms = new ByteArrayOutputStream();
        Bytes.writeLong(ms, Long.parseLong(profile.gameId));
        Bytes.writeByteString(ms, serverId);
        Bytes.writeByteString(ms, data.launcherVersion());
        Bytes.writeByteString(ms, profile.gameVersion);
        Bytes.writeInt(ms, now);
        ms.writeBytes(buildHashData(profile, now, loginSeed));
        Bytes.writeShortString(ms, profile.modInfo);
        Bytes.writeShortString(ms, PRC_CHECK);
        Bytes.writeShort(ms, 0);
        Bytes.writeByteString(ms, data.channel());
        return ms.toByteArray();
    }

    /** Handshake sign hash (SHA-256 over authId + AES seed + version/channel/crcSalt/gameVersion + salt). */
    byte[] signHash(GameProfile profile, byte[] loginSeed) throws IOException {
        byte[] seed = aesEcbNoPadding(loginSeed, profile.authToken());
        return Skip32.sha256(buildSign(profile, profile.authId(), seed));
    }

    /** The AES-ECB seed sent in the handshake body. */
    byte[] aesSeed(GameProfile profile, byte[] loginSeed) throws IOException {
        return aesEcbNoPadding(loginSeed, profile.authToken());
    }

    private byte[] buildSign(GameProfile profile, int authId, byte[] seed) {
        ByteArrayOutputStream ms = new ByteArrayOutputStream();
        Bytes.writeInt(ms, authId);
        ms.writeBytes(seed);
        ms.writeBytes(Bytes.utf8(data.launcherVersion()));
        ms.writeBytes(Bytes.utf8(data.channel()));
        ms.writeBytes(Bytes.utf8(data.crcSalt()));
        ms.writeBytes(Bytes.utf8(profile.gameVersion));
        ms.writeBytes(MC_VERSION_SALT);
        return ms.toByteArray();
    }

    private byte[] buildHashData(GameProfile profile, int time, byte[] loginSeed) {
        String s = data.launcherVersion() + profile.gameVersion + time + data.crcSalt()
                + profile.modInfo + profile.bootstrapMd5 + profile.datFileMd5 + PRC_CHECK;
        byte[] userIdLe = new byte[4];
        userIdLe[0] = (byte) (profile.userId & 0xFF);
        userIdLe[1] = (byte) ((profile.userId >>> 8) & 0xFF);
        userIdLe[2] = (byte) ((profile.userId >>> 16) & 0xFF);
        userIdLe[3] = (byte) ((profile.userId >>> 24) & 0xFF);
        return Skip32.sha256(Bytes.concat(Bytes.utf8(s), userIdLe, loginSeed));
    }

    private static byte[] aesEcbNoPadding(byte[] data, byte[] key) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new IOException("AES 运算失败: " + e.getMessage(), e);
        }
    }
}
