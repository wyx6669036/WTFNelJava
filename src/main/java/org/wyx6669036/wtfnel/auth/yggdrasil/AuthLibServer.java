package org.wyx6669036.wtfnel.auth.yggdrasil;

import org.wyx6669036.wtfnel.log.Log;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The client authlib's local auth port (launch argument -DlauncherControlPort).
 *
 * <p>Request: gameId(UTF-8) / userId(UTF-8) / serverId(UTF-16LE), each field as [len:4 LE][bytes].
 * Response: uint32 LE, 0 = success. The protocol matches FandNEL's AuthLibProtocol.
 * On each request it runs {@link YggdrasilClient#joinServer} locally.</p>
 *
 * <p>Port of WtfNel.Core/Auth/Yggdrasil/AuthLibServer.cs.</p>
 */
public final class AuthLibServer implements AutoCloseable {

    /** Builds the profile for one join request (fills in game version, md5s and modInfo). */
    @FunctionalInterface
    public interface ProfileFactory {
        GameProfile create(String gameId, int userId) throws IOException;
    }

    /** 本地实现的进服认证（真实实现是 {@link YggdrasilClient#joinServer}）。 */
    @FunctionalInterface
    public interface JoinAuthenticator {
        void joinServer(GameProfile profile, String serverId) throws IOException;
    }

    private final int requestedPort;
    private final JoinAuthenticator client;
    private final ProfileFactory profiles;
    private volatile ServerSocket listener;
    private volatile boolean closed;

    public AuthLibServer(int port, YggdrasilClient client, ProfileFactory profiles) {
        this(port, client::joinServer, profiles);
    }

    public AuthLibServer(int port, JoinAuthenticator client, ProfileFactory profiles) {
        this.requestedPort = port;
        this.client = client;
        this.profiles = profiles;
    }

    /** The bound port (equal to the requested one unless the socket was closed). */
    public int port() {
        ServerSocket socket = listener;
        return socket == null ? requestedPort : socket.getLocalPort();
    }

    /** Binds loopback and starts the accept thread. */
    public void start() throws IOException {
        ServerSocket socket = new ServerSocket(requestedPort, 50, InetAddress.getLoopbackAddress());
        listener = socket;
        Log.info("[authlib] 监听 127.0.0.1:" + port());
        Thread thread = new Thread(() -> acceptLoop(socket), "wtfnel-authlib");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop(ServerSocket socket) {
        while (!closed) {
            Socket accepted;
            try {
                accepted = socket.accept();
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                Log.warn("[authlib] accept 失败: " + e.getMessage());
                continue;
            }
            Thread worker = new Thread(() -> handle(accepted), "wtfnel-authlib-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handle(Socket connection) {
        try (Socket socket = connection) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            int result = 1;
            try {
                String gameId = new String(readField(in), StandardCharsets.UTF_8);
                String userId = new String(readField(in), StandardCharsets.UTF_8);
                String serverId = new String(readField(in), StandardCharsets.UTF_16LE);
                Log.info("[authlib] 进服认证请求 gameId=" + gameId + " userId=" + userId + " serverId=" + serverId);

                GameProfile profile = profiles.create(gameId, Integer.parseInt(userId.trim()));
                client.joinServer(profile, serverId);
                result = 0;
                Log.info("[authlib] 认证成功");
            } catch (Exception e) {
                Log.error("[authlib] 认证失败: " + e.getMessage());
            }

            byte[] resp = new byte[4];
            resp[0] = (byte) (result & 0xFF);
            resp[1] = (byte) ((result >>> 8) & 0xFF);
            resp[2] = (byte) ((result >>> 16) & 0xFF);
            resp[3] = (byte) ((result >>> 24) & 0xFF);
            try {
                out.write(resp);
                out.flush();
            } catch (IOException ignored) {
                // the client may already be gone
            }
        } catch (IOException e) {
            Log.debug("[authlib] 连接异常: " + e.getMessage());
        }
    }

    private static byte[] readField(InputStream in) throws IOException {
        DataInputStream data = in instanceof DataInputStream d ? d : new DataInputStream(in);
        byte[] head = data.readNBytes(4);
        if (head.length < 4) {
            throw new EOFException("authlib 请求头不完整");
        }
        int len = (head[0] & 0xFF) | ((head[1] & 0xFF) << 8) | ((head[2] & 0xFF) << 16) | ((head[3] & 0xFF) << 24);
        if (len < 0 || len > 1 << 20) {
            throw new IOException("authlib 字段长度无效");
        }
        byte[] buf = data.readNBytes(len);
        if (buf.length < len) {
            throw new EOFException("authlib 字段数据不足");
        }
        return buf;
    }

    @Override
    public void close() {
        closed = true;
        ServerSocket socket = listener;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
