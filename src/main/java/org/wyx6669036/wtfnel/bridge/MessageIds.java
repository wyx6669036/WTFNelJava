package org.wyx6669036.wtfnel.bridge;

/** Known message ids, named after the numbers observed in runtime logs. */
public final class MessageIds {

    public static final int LOGIN = 0;
    public static final int PING = 0x1202;
    public static final int KEEP_ALIVE = 0x0501;
    /** Reconnect request sent when a DisconnectedScreen click is redirected; the launcher must answer with {@link #RECONNECT}. */
    public static final int RECONNECT_REQUEST = 0x0502;
    /** ReplyReconnect: string host, int port, string room, boolean. The client reconnects itself on this frame. */
    public static final int RECONNECT = 0x0704;
    public static final int CONNECT_SERVER = 0x0707;
    public static final int TOKEN_UPDATE = 5121;
    public static final int STATE_QUERY = 2050;
    /** Chat-ban query; the client blocks chat sending up to 10 s on the render thread until it gets (bool ban, long expireAt, long delta). */
    public static final int CHAT_BAN_QUERY = 4613;

    private MessageIds() {
    }
}
