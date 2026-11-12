package org.wyx6669036.wtfnel.bridge;

import org.wyx6669036.wtfnel.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local bridge endpoint. After launch the game connects here for state sync; as long as this link
 * stays alive the game will not exit on its own. This implementation does three things: accept the
 * connection, read messages frame by frame, and reply to each frame to keep the UI enabled.
 *
 * <p>Port of WtfNel.Core/Bridge/LauncherBridge.cs. The join target that {@code GameRunner} passes in
 * makes the launcher answer a keep-alive by pushing a "join server" command on the game's behalf.</p>
 */
public final class LauncherBridge implements AutoCloseable {

    /** The join target pushed once login completes (speaking on the launcher's behalf). */
    public record Target(String host, int port, String name) {
    }

    private final int port;
    private final Target autoConnect;
    private final Object writeGate = new Object();
    private final AtomicInteger connSeq = new AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "wtfnel-bridge");
        t.setDaemon(true);
        return t;
    });

    private volatile ServerSocket listener;
    private volatile boolean closed;

    public LauncherBridge(int port) {
        this(port, null);
    }

    public LauncherBridge(int port, Target autoConnect) {
        this.port = port;
        this.autoConnect = autoConnect;
    }

    public int getPort() {
        return port;
    }

    public Target getAutoConnect() {
        return autoConnect;
    }

    /** Binds 127.0.0.1:{@code port} and starts accepting connections in the background. */
    public void start() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        listener = ss;
        Log.info("[bridge] 监听 127.0.0.1:" + port);
        Thread acceptor = new Thread(this::acceptLoop, "wtfnel-bridge-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket client;
            try {
                client = listener.accept();
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                Log.warn("[bridge] accept 失败: " + e.getMessage());
                continue;
            }
            int id = connSeq.incrementAndGet();
            Log.info("[bridge] 接入 #" + id + " <- " + client.getRemoteSocketAddress());
            pool.execute(() -> session(client, id));
        }
    }

    private void session(Socket client, int id) {
        try (client) {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            int pushed = 0;
            while (!closed) {
                byte[] payload = Frame.read(in);
                if (payload == null) {
                    Log.info("[bridge] #" + id + " 对端关闭");
                    return;
                }

                int smid = Frame.messageId(payload);
                Log.info("[bridge] #" + id + " <= smid=" + smid + " (0x" + String.format("%04X", smid & 0xFFFF)
                        + ") len=" + payload.length + " " + Frame.hexDump(payload));
                switch (smid) {
                    case MessageIds.KEEP_ALIVE, MessageIds.STATE_QUERY -> {
                        if (pushed == 0 && autoConnect != null) {
                            pushed = 1;
                            boolean ok = pushConnect(client, autoConnect.host(), autoConnect.port(),
                                    autoConnect.name());
                            Log.info("[bridge] #" + id + " -> 回 1799 进服 " + autoConnect.host() + ":"
                                    + autoConnect.port() + " ok=" + ok);
                            pool.execute(() -> repush(client, id));
                        } else {
                            reply(out, payload);
                        }
                    }
                    case MessageIds.CHAT_BAN_QUERY -> {
                        // Echoing the empty request makes the client's ChatBanReply hit EOF, so it never
                        // notifies and every chat message freezes the render thread for the full 10 s.
                        // Answer "not banned": bool false, long expireAt 0, long delta 0 (longs little-endian).
                        byte[] ban = new byte[2 + 1 + 8 + 8];
                        ban[0] = (byte) (MessageIds.CHAT_BAN_QUERY >> 8);
                        ban[1] = (byte) (MessageIds.CHAT_BAN_QUERY & 0xFF);
                        reply(out, ban);
                    }
                    case MessageIds.RECONNECT_REQUEST -> {
                        if (autoConnect != null) {
                            boolean sent = pushReconnect(client, autoConnect.host(), autoConnect.port(),
                                    autoConnect.name());
                            Log.info("[bridge] #" + id + " -> 回 " + MessageIds.RECONNECT + " 重连 "
                                    + autoConnect.host() + ":" + autoConnect.port() + " ok=" + sent);
                        } else {
                            Log.warn("[bridge] #" + id + " 收到重连请求，但没有进服目标");
                        }
                    }
                    case MessageIds.CONNECT_SERVER ->
                            Log.info("[bridge] #" + id + " 进服指令回执 " + Frame.hexDump(payload));
                    default -> reply(out, payload);
                }
            }
        } catch (SocketException e) {
            Log.info("[bridge] #" + id + " 连接中断");
        } catch (IOException e) {
            Log.info("[bridge] #" + id + " 连接中断");
        } catch (RuntimeException e) {
            Log.warn("[bridge] #" + id + " 异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        Log.info("[bridge] #" + id + " 结束");
    }

    /** Replies to a frame with the same message id and the original payload (currently an echo ack). */
    private void reply(OutputStream stream, byte[] payload) {
        try {
            write(stream, payload);
            int smid = Frame.messageId(payload);
            Log.info("[bridge] => smid=" + smid + " (0x" + String.format("%04X", smid & 0xFFFF)
                    + ") len=" + payload.length + " " + Frame.hexDump(payload));
        } catch (IOException e) {
            Log.warn("[bridge] 应答失败: " + e.getMessage());
        }
    }

    private void write(OutputStream stream, byte[] payload) throws IOException {
        synchronized (writeGate) {
            Frame.write(stream, payload);
        }
    }

    /** Pushes the join command once more after a short delay, covering the case where the first push
     * arrives before the client reaches a state where it can act on it. */
    private void repush(Socket client, int id) {
        if (autoConnect == null) {
            return;
        }
        try {
            Thread.sleep(3500);
            if (client.isClosed() || !client.isConnected()) {
                return;
            }
            boolean ok = pushConnect(client, autoConnect.host(), autoConnect.port(), autoConnect.name());
            Log.info("[bridge] #" + id + " -> 1799 重推 " + autoConnect.host() + ":" + autoConnect.port()
                    + " ok=" + ok);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.debug("[bridge] 重推失败: " + e.getMessage());
        }
    }

    /** Pushes a "join server" command to the connection. */
    public boolean pushConnect(Socket client, String host, int port, String name) {
        try {
            List<Byte> body = new ArrayList<>();
            body.add((byte) (MessageIds.CONNECT_SERVER >> 8));
            body.add((byte) (MessageIds.CONNECT_SERVER & 0xFF));
            appendString(body, host);
            appendInt32(body, port);
            appendString(body, name);
            write(client.getOutputStream(), toArray(body));
            return true;
        } catch (IOException e) {
            Log.warn("[bridge] 推送失败: " + e.getMessage());
            return false;
        }
    }

    /** Answers a reconnect request with ReplyReconnect: string host, int port, string room, boolean. */
    public boolean pushReconnect(Socket client, String host, int port, String room) {
        try {
            List<Byte> body = new ArrayList<>();
            body.add((byte) (MessageIds.RECONNECT >> 8));
            body.add((byte) (MessageIds.RECONNECT & 0xFF));
            appendString(body, host);
            appendInt32(body, port);
            appendString(body, room);
            body.add((byte) 1);
            write(client.getOutputStream(), toArray(body));
            return true;
        } catch (IOException e) {
            Log.warn("[bridge] 重连推送失败: " + e.getMessage());
            return false;
        }
    }

    private static void appendInt32(List<Byte> b, int v) {
        b.add((byte) (v & 0xFF));
        b.add((byte) ((v >> 8) & 0xFF));
        b.add((byte) ((v >> 16) & 0xFF));
        b.add((byte) ((v >> 24) & 0xFF));
    }

    private static void appendString(List<Byte> b, String s) {
        byte[] raw = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        b.add((byte) (raw.length & 0xFF));
        b.add((byte) ((raw.length >> 8) & 0xFF));
        for (byte value : raw) {
            b.add(value);
        }
    }

    private static byte[] toArray(List<Byte> b) {
        byte[] out = new byte[b.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = b.get(i);
        }
        return out;
    }

    @Override
    public void close() {
        closed = true;
        ServerSocket ss = listener;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        pool.shutdownNow();
    }
}
