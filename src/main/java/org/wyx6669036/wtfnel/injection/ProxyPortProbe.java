package org.wyx6669036.wtfnel.injection;

import org.wyx6669036.wtfnel.log.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 注入前后各扫一次本机监听端口，用差集找出 payload 真正监听的端口，而不是相信它可能永远不写的交接文件。
 * 移植自 WtfNel.Core/Injection/ProxyPortProbe.cs。
 *
 * <p>C# 用 {@code IPGlobalProperties.GetActiveTcpListeners()}，JDK 没有等价 API，
 * 这里改成解析 {@code netstat -ano -p TCP} 的输出。只关心端口号，所以不解析 PID 列。</p>
 */
public final class ProxyPortProbe {

    public static final int BASE_PORT = 13337;
    public static final int SPAN = 32;

    private ProxyPortProbe() {
    }

    public static Set<Integer> snapshot() {
        return listening();
    }

    /** 等一个新出现的、位于 [13337, 13369) 的监听端口；超时返回 null。 */
    public static Integer waitForNew(Set<Integer> before, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            for (int port : listening()) {
                if (port < BASE_PORT || port >= BASE_PORT + SPAN) {
                    continue;
                }
                if (before != null && before.contains(port)) {
                    continue;
                }
                return port;
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    public static Integer waitForNew(Set<Integer> before) {
        return waitForNew(before, 15000);
    }

    public static boolean isListening(int port) {
        return listening().contains(port);
    }

    private static Set<Integer> listening() {
        Set<Integer> ports = new HashSet<>();
        try {
            for (int port : scanNetstat()) {
                ports.add(port);
            }
        } catch (RuntimeException e) {
            Log.debug("[proxy] 枚举监听端口失败：" + e.getMessage());
        }
        return ports;
    }

    private static Set<Integer> scanNetstat() {
        Set<Integer> ports = new HashSet<>();
        Process process = null;
        try {
            process = new ProcessBuilder("netstat", "-ano", "-p", "TCP")
                    .redirectErrorStream(true)
                    .start();

            Charset charset = Charset.defaultCharset();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int port = parseListenPort(line);
                    if (port > 0) {
                        ports.add(port);
                    }
                }
            }

            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException e) {
            Log.debug("[proxy] 运行 netstat 失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return ports;
    }

    /**
     * 解析一行 netstat。只认同协议列是 TCP 且对端地址是通配 0 的连接（即监听态），
     * 不依赖"状态"列的文字——那一列在部分系统上会被本地化。
     *
     * <p>典型行：{@code TCP    0.0.0.0:13337    0.0.0.0:0    LISTENING    1234}</p>
     */
    static int parseListenPort(String line) {
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length < 3) {
            return -1;
        }
        String protocol = tokens[0];
        if (!protocol.equalsIgnoreCase("TCP")) {
            return -1;
        }

        String foreign = tokens[2];
        if (!foreign.endsWith(":0")) {
            return -1;
        }

        String local = tokens[1];
        int colon = local.lastIndexOf(':');
        if (colon < 0 || colon == local.length() - 1) {
            return -1;
        }

        String address = local.substring(0, colon);
        if (!isWildcardOrLoopback(address)) {
            return -1;
        }

        try {
            int port = Integer.parseInt(local.substring(colon + 1));
            return port > 0 && port <= 65535 ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isWildcardOrLoopback(String address) {
        if (address.startsWith("[")) {
            // IPv6 写法 [::]:port 或 [::1]:port
            String inner = address.substring(1, address.endsWith("]") ? address.length() - 1 : address.length());
            return inner.equals("::") || inner.equals("::1");
        }
        return address.equals("0.0.0.0") || address.startsWith("127.");
    }
}
