package org.wyx6669036.wtfnel.auth.login;

/**
 * Login result: userId (entity_id) plus the 16-char userToken, fed straight to Yggdrasil and the X19 APIs.
 *
 * <p>Port of WtfNel.Core/Auth/Login/NeteaseLogin.cs (the NeteaseSession record).</p>
 */
public record NeteaseSession(int userId, String userToken, String account, String channel) {
}
