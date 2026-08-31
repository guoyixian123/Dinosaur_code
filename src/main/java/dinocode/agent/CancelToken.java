package dinocode.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * per-turn 轻量取消句柄（ch04 F7）：volatile 标志 + 取消回调 + 派生超时 token。
 */
public final class CancelToken {

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dino-cancel-scheduler");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean cancelled;
    private final List<Runnable> callbacks = new ArrayList<>();
    private final CancelToken parent;

    public CancelToken() {
        this.parent = null;
    }

    private CancelToken(CancelToken parent) {
        this.parent = parent;
    }

    public void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        synchronized (callbacks) {
            for (Runnable r : callbacks) {
                r.run();
            }
        }
    }

    public boolean isCancelled() {
        return cancelled || (parent != null && parent.isCancelled());
    }

    /** 注册取消回调（用于关闭底层流等）。 */
    public void onCancel(Runnable r) {
        if (isCancelled()) {
            r.run();
            return;
        }
        synchronized (callbacks) {
            callbacks.add(r);
        }
    }

    /** 派生 token：到点自动 cancel；父 token 取消时一并生效。 */
    public CancelToken withTimeout(Duration timeout) {
        CancelToken child = new CancelToken(this);
        ScheduledFuture<?> future = SCHEDULER.schedule(child::cancel, timeout.toMillis(), TimeUnit.MILLISECONDS);
        child.onCancel(() -> future.cancel(false));
        return child;
    }
}
