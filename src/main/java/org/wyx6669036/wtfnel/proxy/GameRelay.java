package org.wyx6669036.wtfnel.proxy;

import org.wyx6669036.wtfnel.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Forwarding layer between the game and the server. The game connects to a local port and this process
 * relays the byte stream verbatim to the real server. Alongside, a packet sequence is parsed for
 * observation only; a parse failure just disables observation and never affects forwarding.
 *
 * <p>Port of WtfNel.Core/Proxy/GameRelay.cs.</p>
 */
public final class GameRelay implements AutoCloseable {

    private static final int BUFFER = 16 * 1024;

    private final String remoteHost;
    private final int remotePort;
    private final int localPort;
    private final boolean verbose;
    private final AtomicInteger connSeq = new AtomicInteger();

    private volatile ServerSocket listener;
    private volatile boolean closed;

    public GameRelay(String remoteHost, int remotePort, int localPort, boolean verbose) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.localPort = localPort;
        this.verbose = verbose;
    }

    public void start() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort));
        listener = ss;
        Log.info("[relay] 本地 127.0.0.1:" + localPort + " -> " + remoteHost + ":" + remotePort);
        Thread acceptor = new Thread(this::acceptLoop, "wtfnel-relay-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket local;
            try {
                local = listener.accept();
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                Log.warn("[relay] accept 失败: " + e.getMessage());
                continue;
            }
            int id = connSeq.incrementAndGet();
            Thread t = new Thread(() -> session(local, id), "wtfnel-relay-" + id);
            t.setDaemon(true);
            t.start();
        }
    }

    private void session(Socket local, int id) {
        try (local; Socket remote = new Socket()) {
            local.setTcpNoDelay(true);
            remote.setTcpNoDelay(true);
            try {
                remote.connect(new InetSocketAddress(remoteHost, remotePort), 15000);
            } catch (IOException e) {
                Log.warn("[relay] #" + id + " 连接目标失败: " + e.getMessage());
                return;
            }

            Log.info("[relay] #" + id + " 建立 " + local.getRemoteSocketAddress() + " <-> "
                    + remoteHost + ":" + remotePort);

            PacketTap tapC2S = new PacketTap("C2S", id, verbose);
            PacketTap tapS2C = new PacketTap("S2C", id, verbose);

            Pump up = new Pump("C2S", local, remote, tapC2S);
            Pump down = new Pump("S2C", remote, local, tapS2C);
            Thread tUp = startPump(up, "relay-up-" + id);
            Thread tDown = startPump(down, "relay-down-" + id);

            while (tUp.isAlive() && tDown.isAlive()) {
                tUp.join(500);
                tDown.join(500);
                if (closed) {
                    break;
                }
            }
            try {
                local.close();
            } catch (IOException ignored) {
                // best effort
            }
            try {
                remote.close();
            } catch (IOException ignored) {
                // best effort
            }
            tUp.join(2000);
            tDown.join(2000);

            tapC2S.summary();
            tapS2C.summary();
            Log.info("[relay] #" + id + " 结束");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            Log.warn("[relay] #" + id + " 异常: " + e.getMessage());
        }
    }

    private static Thread startPump(Pump pump, String name) {
        Thread t = new Thread(pump, name);
        t.setDaemon(true);
        t.start();
        return t;
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
    }

    /** One direction of the relay: copy bytes verbatim while feeding the passive observer. */
    private final class Pump implements Runnable {
        private final String tag;
        private final Socket from;
        private final Socket to;
        private final PacketTap tap;

        Pump(String tag, Socket from, Socket to, PacketTap tap) {
            this.tag = tag;
            this.from = from;
            this.to = to;
            this.tap = tap;
        }

        @Override
        public void run() {
            byte[] buf = new byte[BUFFER];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                while (!closed) {
                    int n = in.read(buf);
                    if (n <= 0) {
                        break;
                    }
                    tap.feed(buf, 0, n);
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (SocketException e) {
                // peer closed; nothing to do
            } catch (IOException e) {
                if (!closed) {
                    Log.warn("[relay] 转发异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
        }
    }
}
