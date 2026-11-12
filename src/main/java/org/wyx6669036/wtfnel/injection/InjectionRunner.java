package org.wyx6669036.wtfnel.injection;

import org.wyx6669036.wtfnel.config.ShadowConfig;
import org.wyx6669036.wtfnel.log.Log;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 后台注入线程：等目标进程出现，再逐个把 payload 注进去。
 * 移植自 WtfNel.Core/Injection/InjectionRunner.cs。
 *
 * <p>它在客户端启动之后马上跑，不阻塞主流程 —— 注入失败也只是一条警告，
 * 白端模式下用户照样能玩。</p>
 */
public final class InjectionRunner {

    private static final Set<Consumer<Integer>> PROXY_READY = new java.util.concurrent.CopyOnWriteArraySet<>();

    private static volatile int proxyPort;

    private InjectionRunner() {
    }

    /** payload 内部起的代理端口；注入成功且探测到新监听后才有值。 */
    public static int proxyPort() {
        return proxyPort;
    }

    public static void addProxyReadyListener(Consumer<Integer> listener) {
        if (listener != null) {
            PROXY_READY.add(listener);
        }
    }

    public static void removeProxyReadyListener(Consumer<Integer> listener) {
        PROXY_READY.remove(listener);
    }

    static InjectionService.GameTarget parseTarget(String name) {
        String value = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "scpsl", "scp:sl", "scp" -> InjectionService.GameTarget.SCP_SL;
            default -> InjectionService.GameTarget.MINECRAFT;
        };
    }

    static InjectionStrategy parseStrategy(String name) {
        String value = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "reflective", "反射" -> InjectionStrategy.REFLECTIVE;
            case "manualmap", "manual", "手动映射" -> InjectionStrategy.MANUAL_MAP;
            case "loadlibrary", "load", "落盘" -> InjectionStrategy.LOAD_LIBRARY;
            default -> InjectionStrategy.AUTO;
        };
    }

    /**
     * 按文件名推断初始化导出。两个 payload 的导出名不一样，配置里可以不写。
     */
    static String initExportFor(String payloadPath) {
        String fileName = Path.of(payloadPath).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = (dot > 0 ? fileName.substring(0, dot) : fileName).toLowerCase(Locale.ROOT);

        if (stem.contains("hwidspoof")) {
            return "WtfNelHwidInit";
        }
        if (stem.contains("payload.proxy") || stem.endsWith(".proxy")) {
            return "WtfNelProxyInit";
        }
        return null;
    }

    static List<String> defaultPayloads(ShadowConfig cfg) {
        List<String> payloads = new ArrayList<>();
        if (cfg.isEnableProxy()) {
            payloads.add(InjectionService.PROXY_PAYLOAD);
        }
        if (cfg.isEnableHwidSpoof()) {
            payloads.add(InjectionService.HWID_PAYLOAD);
        }
        return payloads;
    }

    public static List<InjectionResult> injectAll(ShadowConfig cfg) {
        return injectAll(cfg, null);
    }

    /**
     * 注入全部 payload。processId 给了就直接用，否则在 InjectWaitSeconds 秒内轮询目标进程。
     */
    public static List<InjectionResult> injectAll(ShadowConfig cfg, Integer processId) {
        InjectionService.GameTarget target = parseTarget(cfg.getInjectTarget());
        InjectionStrategy strategy = parseStrategy(cfg.getInjectStrategy());
        List<String> payloads = cfg.getInjectPayloads() != null && !cfg.getInjectPayloads().isEmpty()
                ? cfg.getInjectPayloads()
                : defaultPayloads(cfg);

        List<InjectionResult> results = new ArrayList<>();

        int pid;
        if (processId != null) {
            pid = processId;
        } else {
            InjectionService.InjectionProfile profile = InjectionService.InjectionProfile.forTarget(target);
            long deadline = System.nanoTime() + Math.max(5, cfg.getInjectWaitSeconds()) * 1_000_000_000L;
            ProcessTarget.ProcessCandidate candidate = null;
            while (candidate == null) {
                candidate = ProcessTarget.newest(profile.find());
                if (candidate != null) {
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    InjectionResult timeout = InjectionResult.fail(strategy,
                            "等待 " + cfg.getInjectWaitSeconds() + " 秒没找到 " + target.label() + " 进程", 0);
                    Log.warn("[inject] " + timeout.detail());
                    results.add(timeout);
                    return results;
                }
                sleep(250);
            }

            pid = candidate.processId();
            Log.info(String.format("[inject] 目标 %s：pid %d「%s」%s", target.label(), pid, candidate.title(),
                    Path.of(candidate.executable()).getFileName()));
        }

        for (String name : payloads) {
            Path resolved = InjectionService.resolvePayload(name);
            if (resolved == null) {
                InjectionResult missing = InjectionResult.fail(strategy,
                        "payload 不存在：" + name + "（放到 " + InjectionService.payloadDirectory() + "）", 0);
                Log.warn("[inject] " + missing.detail());
                results.add(missing);
                continue;
            }

            String initExport = cfg.getInjectInitExport() == null || cfg.getInjectInitExport().isBlank()
                    ? initExportFor(resolved.toString())
                    : cfg.getInjectInitExport();

            InjectionOptions options = new InjectionOptions()
                    .strategy(strategy)
                    .initExport(initExport)
                    .fallbackToLoadLibrary(true)
                    .log(Log::info);

            String fileName = resolved.getFileName().toString();
            boolean isProxy = fileName.toLowerCase(Locale.ROOT).contains("payload.proxy");
            Set<Integer> listeningBefore = isProxy ? ProxyPortProbe.snapshot() : null;

            InjectionResult result = InjectionService.inject(pid, resolved.toString(), options);
            if (result.success()) {
                Log.info("[inject] " + result);
            } else {
                Log.warn("[inject] " + result);
            }
            results.add(result);

            if (result.success() && listeningBefore != null) {
                announceProxyPort(listeningBefore);
            }
        }

        return results;
    }

    /**
     * 等 payload 把代理端口监听起来。只看「新增」的监听，因为注入前那个端口可能已经被
     * 上一个进程占用着，只按端口号判断会误报。
     */
    private static void announceProxyPort(Set<Integer> before) {
        Integer port = ProxyPortProbe.waitForNew(before);
        if (port == null) {
            Log.warn(String.format("[inject] 没等到代理端口：预期 127.0.0.1:%d 起 %d 个端口内出现新监听",
                    ProxyPortProbe.BASE_PORT, ProxyPortProbe.SPAN));
            return;
        }

        proxyPort = port;
        Log.info("[inject] 代理端口已开启：127.0.0.1:" + port);
        for (Consumer<Integer> listener : PROXY_READY) {
            try {
                listener.accept(port);
            } catch (RuntimeException e) {
                Log.warn("[inject] ProxyReady 回调异常：" + e);
            }
        }
    }

    /** 起一个后台线程做注入，不阻塞启动流程。 */
    public static Thread startBackground(ShadowConfig cfg) {
        Thread thread = new Thread(() -> {
            try {
                injectAll(cfg);
            } catch (RuntimeException e) {
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                Log.warn("[inject] 注入线程异常：" + root.getMessage());
                Log.debug(String.valueOf(e));
            }
        }, "wtfnel-inject");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
