package org.wyx6669036.wtfnel.auth.yggdrasil;

import org.bouncycastle.crypto.AsymmetricBlockCipher;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.encodings.PKCS1Encoding;
import org.bouncycastle.crypto.engines.RSAEngine;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/** PKCS#1 RSA in block mode; private-key encrypt / public-key decrypt is the "signature" usage. */
public final class Rsa {

    private Rsa() {
    }

    public static AsymmetricKeyParameter loadPublicKey(String b64) throws IOException {
        return PublicKeyFactory.createKey(Base64.getDecoder().decode(b64));
    }

    public static AsymmetricKeyParameter loadPrivateKey(String b64) throws IOException {
        return PrivateKeyFactory.createKey(Base64.getDecoder().decode(b64));
    }

    public static byte[] pkcs1(CipherParameters key, byte[] data, boolean forEncryption) {
        AsymmetricBlockCipher engine = new PKCS1Encoding(new RSAEngine());
        engine.init(forEncryption, key);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int block = engine.getInputBlockSize();
        try {
            for (int i = 0; i < data.length; i += block) {
                out.writeBytes(engine.processBlock(data, i, Math.min(block, data.length - i)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("RSA 运算失败: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }
}
