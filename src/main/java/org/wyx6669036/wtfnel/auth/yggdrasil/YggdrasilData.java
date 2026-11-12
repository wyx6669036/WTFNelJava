package org.wyx6669036.wtfnel.auth.yggdrasil;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Auth server version parameters. crcSalt is bound to the launcher version. */
public record YggdrasilData(String launcherVersion, String channel, String crcSalt) {
}
