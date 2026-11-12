package org.wyx6669036.wtfnel.auth.yggdrasil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The game-side information needed for one join-server authentication. */
public class GameProfile {

    private static final byte[] TOKEN_KEY = {
            (byte) 172, 36, (byte) 156, 105, (byte) 199, 44, (byte) 179, (byte) 180,
            78, (byte) 192, (byte) 204, 108, 84, 58, (byte) 129, (byte) 149,
    };

    public String gameId = "";
    public String gameVersion = "";
    public String bootstrapMd5 = "";
    public String datFileMd5 = "";
    /** Serialized {"mods":[...]}, which must match what the API returns. */
    public String modInfo = "";
    public int userId;
    public String userToken = "";

    public GameProfile() {
    }

    public GameProfile(String gameId, String gameVersion, String bootstrapMd5, String datFileMd5,
                       String modInfo, int userId, String userToken) {
        this.gameId = gameId;
        this.gameVersion = gameVersion;
        this.bootstrapMd5 = bootstrapMd5;
        this.datFileMd5 = datFileMd5;
        this.modInfo = modInfo;
        this.userId = userId;
        this.userToken = userToken;
    }

    public int authId() {
        return Skip32.encrypt(userId, Skip32.ROLE_KEY);
    }

    public byte[] authToken() {
        byte[] raw = userToken.getBytes(StandardCharsets.US_ASCII);
        if (raw.length != 16) {
            throw new IllegalStateException("userToken 必须是 16 个 ASCII 字符");
        }
        return Bytes.xor(raw, TOKEN_KEY);
    }

    public GameProfile withModInfo(String value) {
        this.modInfo = value;
        return this;
    }
}
