package org.wyx6669036.wtfnel;

import org.wyx6669036.wtfnel.log.Log;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * 一个跑起来的客户端（{@link NelAccount#launchGame} 的返回值）。
 *
 * <p>{@code launchGame} 立刻返回这个对象，整段启动（装文件 → 起桥接/认证 → 拉客户端 → 注入 → 等退出）
 * 都在名为 {@code wtfnel-launch} 的后台线程里跑，宿主线程该干什么干什么：</p>
 *
 * <pre>{@code
 * GameClient client = account.launchGame(account.userId(), "角色名", true, false);
 * System.out.println(client.state() + " pid=" + client.pid());
 * // ... 宿主继续跑 ...
 * client.stop();               // 关客户端（500ms 内生效，收尾会杀掉客户端与替身父进程）
 * int code = client.await();   // 只在真的要等它退干净时才调
 * }</pre>
 *
 * <p>生命周期由自己管：客户端退出后后台线程走到头，对象自动变成 {@link State#EXITED}
 * （失败则是 {@link State#FAILED}），宿主不需要任何收尾调用。要显式等待就用 {@link #await()}。</p>
 *
 * <p>三条约定：</p>
 * <ul>
 *   <li>同一个 JVM 里一次只能跑一个客户端（桥接 11655 / 认证 11656 都是固定端口），
 *       第二个 {@code launchGame} 会直接抛 {@link IllegalStateException}；</li>
 *   <li>启动线程不是 daemon：宿主 {@code main} 返回后 JVM 不会走退出流程，客户端继续跑，
 *       直到它自己退出、或被 {@link #stop()} 停掉；</li>
 *   <li>配置（角色名 / uid / token / 两个开关）在调用 {@code launchGame} 的那一刻就已经写好，
 *       后台启动失败不会回头改它。</li>
 * </ul>
 */
public final class GameClient implements AutoCloseable {

    /** 客户端状态。{@code RUNNING} 里也包含「还在装文件、还没拉起进程」的阶段。 */
    public enum State {
        /** 启动线程还在跑（可能还没起到拉起进程那一步）。 */
        RUNNING("运行中"),
        /** 客户端自己退出了（或被 {@link #stop()} 收掉），{@link #exitCode()} 是它的退出码。 */
        EXITED("已退出"),
        /** 启动过程抛异常（缺 java、端口被占……），见 {@link #failure()}。 */
        FAILED("启动失败");

        private final String label;

        State(String label) {
            this.label = label;
        }

        /** 中文名（运行中 / 已退出 / 启动失败）。 */
        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 后台线程要执行的东西：跑完返回退出码，{@code cancelled} 为 true 表示宿主请求停止。 */
    @FunctionalInterface
    public interface LaunchBody {
        int run(BooleanSupplier cancelled, LongConsumer onClientStarted) throws IOException;
    }

    /** 挂在一次启动上的身份与开关（调 launchGame 的那一刻就固定下来）。 */
    public record Spec(String role, int uid, boolean hwidSpoof, boolean proxy) {
    }

    /** 启动编排用的进程级锁：一次只允许一个客户端，也保护被上一个客户端读着的配置对象。 */
    static final Object LOCK = new Object();

    private static GameClient active;

    private final String label;
    private final String role;
    private final int uid;
    private final boolean hwidSpoof;
    private final boolean proxy;
    private final CompletableFuture<Integer> future = new CompletableFuture<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final long startedAt = System.currentTimeMillis();

    private volatile Thread thread;
    private volatile long pid;
    private volatile int exitCode;
    private volatile long endedAt;
    private volatile Throwable failure;

    private GameClient(Spec spec, String label) {
        this.role = spec.role();
        this.uid = spec.uid();
        this.hwidSpoof = spec.hwidSpoof();
        this.proxy = spec.proxy();
        this.label = label != null && !label.isBlank()
                ? label
                : (spec.role() == null || spec.role().isBlank() ? "客户端" : "角色 " + spec.role());
    }

    /** 当前正在跑的那一个；没有就是 null。 */
    public static GameClient current() {
        synchronized (LOCK) {
            return runningInLock();
        }
    }

    /** 有客户端还没退出就抛异常（不占位，只判断）。 */
    public static void checkIdle() {
        synchronized (LOCK) {
            GameClient running = runningInLock();
            if (running != null) {
                throw newlyRunning(running);
            }
        }
    }

    /** 调用方必须已经持锁：还活着的那一个，结束/失败/取消的都算没有。 */
    private static GameClient runningInLock() {
        if (active == null || active.future.isDone()) {
            return null;
        }
        return active;
    }

    private static IllegalStateException newlyRunning(GameClient running) {
        return new IllegalStateException("已经有一个客户端在跑（" + running.label
                + "，pid=" + (running.pid > 0 ? running.pid : "还没起来")
                + "）：桥接端口 11655 / 认证端口 11656 是固定端口，同一进程一次只能跑一个客户端；"
                + "先 stop() 或者等它退出");
    }

    /** 起一个后台启动线程，标签按角色名自动拼（「角色 xxx」）。 */
    public static GameClient start(Spec spec, LaunchBody body) {
        return start(spec, body, null);
    }

    /**
     * 起一个后台启动线程。查重与占位在同一次持锁里完成，两个线程同时调也只有一个能过。
     *
     * @param label 日志与错误消息里显示的名字；null 表示按角色名自动拼
     */
    public static GameClient start(Spec spec, LaunchBody body, String label) {
        GameClient client = new GameClient(spec, label);
        synchronized (LOCK) {
            GameClient running = runningInLock();
            if (running != null) {
                throw newlyRunning(running);
            }
            active = client;
        }
        Thread worker = new Thread(() -> client.execute(body), "wtfnel-launch");
        // 不是 daemon：宿主 main 返回之后 JVM 不该立刻走退出流程，
        // 否则 ProcessGuard 的 shutdown hook 会把刚拉起来的客户端一起收掉。
        worker.setDaemon(false);
        client.thread = worker;
        try {
            worker.start();
        } catch (Throwable error) {
            synchronized (LOCK) {
                if (active == client) {
                    active = null;
                }
            }
            throw error;
        }
        return client;
    }

    private void execute(LaunchBody body) {
        Log.info("[api] 后台启动线程开始（" + label + "）");
        try {
            int code = body.run(cancelled::get, value -> this.pid = value);
            exitCode = code;
            endedAt = System.currentTimeMillis();
            Log.info("[api] 客户端已退出（" + label + "），退出码=" + code);
            future.complete(code);
        } catch (Throwable error) {
            failure = error;
            endedAt = System.currentTimeMillis();
            Log.error("[api] 后台启动失败（" + label + "）: " + describe(error));
            future.completeExceptionally(error);
        } finally {
            synchronized (LOCK) {
                if (active == this) {
                    active = null;
                }
            }
        }
    }

    // ---------------------------------------------------------------- 状态

    /** 这次启动的描述（「角色 名字」）。 */
    public String label() {
        return label;
    }

    /** 以哪个角色启动的。 */
    public String role() {
        return role;
    }

    /** 这次启动的账号 id。 */
    public int uid() {
        return uid;
    }

    /** 客户端状态：运行中 / 已退出 / 启动失败。 */
    public State state() {
        if (!future.isDone()) {
            return State.RUNNING;
        }
        return future.isCompletedExceptionally() ? State.FAILED : State.EXITED;
    }

    /** 客户端 pid；还没起到那一步就是 0。 */
    public long pid() {
        return pid;
    }

    /** 退出码；还没退出时是 0。启动失败（{@link State#FAILED}）也保持 0，具体原因看 {@link #failure()}。 */
    public int exitCode() {
        return exitCode;
    }

    /** 这次启动有没有注入改机 payload。 */
    public boolean hwidSpoof() {
        return hwidSpoof;
    }

    /** 这次启动有没有注入代理 payload。 */
    public boolean proxy() {
        return proxy;
    }

    /** 还在跑（既没退出，也还没失败）。 */
    public boolean isRunning() {
        return !future.isDone();
    }

    /** 已经结束（不管成功还是失败）。 */
    public boolean isDone() {
        return future.isDone();
    }

    /** 正常退出了（有退出码）。 */
    public boolean isExited() {
        return future.isDone() && !future.isCompletedExceptionally();
    }

    /** 启动失败时的异常；没失败就是 null。 */
    public Throwable failure() {
        return failure;
    }

    /** 后台线程；还没开始就是 null。 */
    public Thread thread() {
        return thread;
    }

    /** 启动时刻（{@link System#currentTimeMillis()}）。 */
    public long startedAtMillis() {
        return startedAt;
    }

    /** 结束时刻；还没结束是 0。 */
    public long endedAtMillis() {
        return endedAt;
    }

    /** 跑了多久（毫秒）；还没结束就是从启动到现在。 */
    public long elapsedMillis() {
        long end = endedAt;
        return (end > 0 ? end : System.currentTimeMillis()) - startedAt;
    }

    /** 宿主有没有调用过 {@link #stop()}。 */
    public boolean isStopRequested() {
        return cancelled.get();
    }

    // ---------------------------------------------------------------- 收尾

    /**
     * 关客户端：{@code GameRunner} 每 500ms 看一次这个标记，看到就跳出等待并收尾
     * （替身父进程与客户端都会被强制关掉）。幂等；已经结束就什么也不做。
     *
     * <p>它不等客户端真的死掉，要等就接着调 {@link #await()}。</p>
     */
    public void stop() {
        if (future.isDone()) {
            return;
        }
        if (cancelled.compareAndSet(false, true)) {
            Log.info("[api] 已请求停止客户端（" + label + "，pid=" + (pid > 0 ? pid : "还没起来") + "）");
        }
    }

    /** 等价于 {@code stop(); await();}，供 try-with-resources 用。 */
    @Override
    public void close() {
        stop();
        try {
            await();
        } catch (IOException e) {
            Log.warn("[api] 关闭客户端时出错（" + label + "）: " + e.getMessage());
        }
    }

    /** 阻塞到客户端退出，返回退出码。 */
    public int await() throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("等待客户端退出时被中断（" + label + "）");
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        } catch (CancellationException e) {
            throw new IOException("启动已取消（" + label + "）");
        }
    }

    /**
     * 最多等 {@code timeout}，客户端先退出就返回退出码，超时返回 {@code null}（它还在跑）。
     */
    public Integer await(long timeout, TimeUnit unit) throws IOException {
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("等待客户端退出时被中断（" + label + "）");
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        } catch (CancellationException e) {
            throw new IOException("启动已取消（" + label + "）");
        }
    }

    /** 退出时回调退出码；失败时回调不会触发（用 {@link #future()} 的 whenComplete 处理失败）。 */
    public GameClient onExit(Consumer<Integer> listener) {
        if (listener != null) {
            future.thenAccept(listener);
        }
        return this;
    }

    /** 底层 future：成功给退出码，失败给异常。 */
    public CompletableFuture<Integer> future() {
        return future;
    }

    private IOException rethrow(Throwable cause) {
        if (cause instanceof IOException io) {
            return io;
        }
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IOException("启动失败（" + label + "）: " + cause, cause);
    }

    private static String describe(Throwable error) {
        StringBuilder sb = new StringBuilder(String.valueOf(error));
        Throwable root = error.getCause();
        while (root != null && root != root.getCause() && root != error) {
            sb.append(" <- ").append(root);
            root = root.getCause();
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        State s = state();
        return "GameClient[" + label + " pid=" + (pid > 0 ? String.valueOf(pid) : "-")
                + " " + s.label()
                + (s == State.EXITED ? " 退出码=" + exitCode : "")
                + " 改机=" + (hwidSpoof ? "开" : "关") + " 代理=" + (proxy ? "开" : "关") + "]";
    }
}
