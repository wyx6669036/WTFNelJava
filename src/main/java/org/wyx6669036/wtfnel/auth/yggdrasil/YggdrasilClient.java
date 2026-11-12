package org.wyx6669036.wtfnel.auth.yggdrasil;

import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.util.Json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * NetEase join-server authentication (Yggdrasil over TCP).
 *
 * <p>The flow matches FandNEL's NetEaseConnection: receive the 272-byte context, send the handshake,
 * read status 0, send the type-9 join packet over the ChaCha8 channel, then a leading 0 byte means
 * success. The handshake and join packets are generated locally by {@link YggdrasilGenerator}.</p>
 *
 * <p>Port of WtfNel.Core/Auth/Yggdrasil/YggdrasilClient.cs.</p>
 */
public class YggdrasilClient implements AutoCloseable {

    private static final String AUTH_SERVER_LIST_URL = "https://x19.update.netease.com/authserver.list";
    private static final byte[] CHACHA_NONCE = "163 NetEase\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String fixedServer;
    private final YggdrasilGenerator generator;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public YggdrasilClient(YggdrasilData data) {
        this(data, "");
    }

    public YggdrasilClient(YggdrasilData data, String fixedServer) {
        this.fixedServer = fixedServer == null ? "" : fixedServer.trim();
        this.generator = new YggdrasilGenerator(data);
    }

    /** One full join-server authentication: handshake, then the type-9 join packet. */
    public void joinServer(GameProfile profile, String serverId) throws IOException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        Server server = resolveServer();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(server.host(), server.port()), (int) TIMEOUT.toMillis());
            socket.setSoTimeout((int) TIMEOUT.toMillis());
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            byte[] context = Bytes.readInt16Frame(in);
            if (context.length < 272) {
                throw new IOException("认证上下文长度异常: " + context.length);
            }
            byte[] loginSeed = Bytes.slice(context, 0, 16);
            byte[] signContent = Bytes.slice(context, 16, 272);

            out.write(initializeMessage(profile, loginSeed, signContent));
            out.flush();
            byte[] status = Bytes.readInt16Frame(in);
            if (status.length < 1 || status[0] != 0) {
                int code = status.length > 0 ? status[0] & 0xFF : 0xFF;
                throw new IllegalStateException(String.format(
                        "认证握手被拒绝: 0x%02X（多半是 CrcSalt/启动器版本/userToken 不匹配）", code));
            }

            byte[] authToken = profile.authToken();
            ChaChaPacker packer = new ChaChaPacker(Bytes.concat(authToken, loginSeed), CHACHA_NONCE, true);
            ChaChaPacker unpacker = new ChaChaPacker(Bytes.concat(loginSeed, authToken), CHACHA_NONCE, false);

            out.write(packer.pack((byte) 9, generator.generateJoinMessage(profile, serverId, loginSeed)));
            out.flush();
            ChaChaPacker.Unpacked reply = unpacker.unpack(Bytes.readInt16Frame(in));
            if (reply.type() != 9 || reply.data().length == 0 || reply.data()[0] != 0) {
                int code = reply.data().length > 0 ? reply.data()[0] & 0xFF : 0xFF;
                throw new IllegalStateException("进服认证失败: type=" + reply.type() + " code=0x"
                        + String.format("%02X", code));
            }
            if (System.nanoTime() > deadline) {
                throw new IOException("认证超时");
            }
        }
    }

    /**
     * Builds the handshake frame from the context the server just sent. Split out so tests can drive the
     * wire protocol without holding the server's RSA private key (the real check is covered by
     * YggdrasilGoldenTest).
     */
    byte[] initializeMessage(GameProfile profile, byte[] loginSeed, byte[] signContent) throws IOException {
        return generator.generateInitializeMessage(profile, loginSeed, signContent);
    }

    private record Server(String host, int port) {
    }

    private Server resolveServer() throws IOException {
        if (!fixedServer.isEmpty()) {
            int idx = fixedServer.lastIndexOf(':');
            if (idx <= 0 || idx == fixedServer.length() - 1) {
                throw new IOException("YggdrasilServer 需要 host:port 形式: " + fixedServer);
            }
            return new Server(fixedServer.substring(0, idx), Integer.parseInt(fixedServer.substring(idx + 1).trim()));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(AUTH_SERVER_LIST_URL))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        String body;
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("认证服务器列表 HTTP " + response.statusCode());
            }
            body = response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("认证服务器列表请求被中断", e);
        }

        List<AuthServer> usable = new ArrayList<>();
        for (AuthServer server : Json.readValue(body, AuthServer[].class)) {
            if (server.ip() != null && !server.ip().isBlank() && server.port() > 0 && server.port() < 65536) {
                usable.add(server);
            }
        }
        if (usable.isEmpty()) {
            throw new IOException("认证服务器列表为空");
        }
        AuthServer pick = usable.get(ThreadLocalRandom.current().nextInt(usable.size()));
        Log.info("[ygg] 认证服务器 " + pick.ip() + ":" + pick.port());
        return new Server(pick.ip(), pick.port());
    }

    @Override
    public void close() {
        // the shared HttpClient needs no shutdown in Java 21
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AuthServer(@JsonProperty("IP") String ip, @JsonProperty("Port") int port) {
    }
}
