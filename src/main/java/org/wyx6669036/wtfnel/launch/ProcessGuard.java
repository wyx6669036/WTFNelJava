package org.wyx6669036.wtfnel.launch;

import org.wyx6669036.wtfnel.log.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记录本次会话启动的子进程（客户端与替身父进程），退出时一并关掉，不留残余。
 * 覆盖每条退出路径：正常收尾（显式 {@link #killAll}）、Ctrl+C、未处理异常、进程退出钩子。
 * {@link #killAll} 幂等，只会真正执行一次。
 *
 * <p>移植自 WtfNel.Core/Launch/ProcessGuard.cs。C# 的 Console.CancelKeyPress 在 Java 里由
 * shutdown hook 一并覆盖（Ctrl+C 同样会触发 JVM 退出）。C# 的 {@code _targets} / {@code _reaped}
 * 都是实例字段 —— 每个 guard 只负责自己登记过的进程，这里保持一致。</p>
 */
public final class ProcessGuard implements AutoCloseable {

    private record Target(long pid, String name) {
    }

    private final Object lock = new Object();
    private final List<Target> targets = new ArrayList<>();
    private final AtomicInteger reaped = new AtomicInteger();
    private boolean hooked;

    public ProcessGuard() {
        hook();
    }

    private void hook() {
        synchronized (this) {
            if (hooked) {
                return;
            }
            hooked = true;
        }

        Thread shutdown = new Thread(() -> killAll("进程退出"), "wtfnel-guard");
        shutdown.setDaemon(false);
        try {
            Runtime.getRuntime().addShutdownHook(shutdown);
        } catch (IllegalStateException ignored) {
            // JVM 已经在关闭，钩子加不上了；收尾交给调用方的 finally
        }

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            // JVM 自带的「打印栈到 stderr」只在没有处理器时生效；一旦设了处理器，
            // 打印就归我们负责。之前这里只在 previous 非空时转交，导致未处理异常
            // 连一行栈都没有、进程静默以 1 退出。
            if (previous != null) {
                try {
                    previous.uncaughtException(t, e);
                } catch (RuntimeException ignored) {
                    // 前一个处理器自身出错不应该再连锁抛出
                }
            } else {
                printUncaught(t, e);
            }
            killAll("未处理异常");
        });
    }

    /** 手工补上 JVM 默认行为：把线程名与完整调用栈打到 stderr。 */
    static void printUncaught(Thread thread, Throwable error) {
        if (error == null) {
            return;
        }
        System.err.print("Exception in thread \"" + (thread == null ? "main" : thread.getName()) + "\" ");
        error.printStackTrace(System.err);
        System.err.flush();
    }

    /** 注册一个退出时要关掉的进程。 */
    public void track(long pid, String name) {
        if (pid <= 0) {
            return;
        }
        synchronized (lock) {
            targets.add(new Target(pid, name));
        }
    }

    /** 关掉所有还活着的被跟踪进程（连同进程树）；只执行一次。 */
    public void killAll(String reason) {
        if (reaped.getAndSet(1) != 0) {
            return;
        }
        List<Target> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(targets);
        }
        for (Target target : snapshot) {
            ProcessHandle handle = ProcessHandle.of(target.pid()).orElse(null);
            if (handle == null || !handle.isAlive()) {
                continue;
            }
            try {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                handle.destroyForcibly();
                handle.onExit().get(3, TimeUnit.SECONDS);
                Log.info("[guard] (" + reason + ") 已关闭 " + target.name() + " pid=" + target.pid());
            } catch (Exception e) {
                Log.warn("[guard] 关闭 " + target.name() + " pid=" + target.pid() + " 失败: " + e.getMessage());
            }
        }
    }

    public void killAll() {
        killAll("收尾");
    }

    @Override
    public void close() {
        // 钩子留在 JVM 上，退出时统一收尾；与 C# 的 Dispose 只解绑不同，
        // Java 的 shutdown hook 无法安全地“只解绑自己”，保留它是幂等的、无副作用。
    }
}
