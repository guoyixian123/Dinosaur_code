package dinocode.agent;

import dinocode.compact.Recovery;
import dinocode.compact.state.AutoCompactTrackingState;
import dinocode.compact.state.ContentReplacementState;
import dinocode.compact.state.SessionContext;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 上下文管理的长生命周期状态（ch08）：跨轮（跨 run）持有，由 TUI/Main 构造并注入 Agent。
 * usageAnchor 锚定主对话路径最近一次真实 usage（摘要请求不更新）；
 * runLock 保证手动 /compact 与主循环互斥（F37）。
 */
public final class CompactContext {

    public final ContentReplacementState replacement = new ContentReplacementState();
    public final Recovery.RecoveryState recovery = new Recovery.RecoveryState();
    public final AutoCompactTrackingState autoTracking = new AutoCompactTrackingState();
    /** 会话上下文：ch09 /resume 恢复后可整体替换。 */
    private volatile SessionContext session;

    public SessionContext session() {
        return session;
    }
    public volatile int contextWindow;

    private final ReentrantLock anchorLock = new ReentrantLock();
    private long usageAnchor;
    private int anchorMsgLen;
    private final ReentrantLock runLock = new ReentrantLock();

    public CompactContext(SessionContext session, int contextWindow) {
        this.session = session;
        this.contextWindow = contextWindow;
    }

    public long getUsageAnchor() {
        anchorLock.lock();
        try {
            return usageAnchor;
        } finally {
            anchorLock.unlock();
        }
    }

    public int getAnchorMsgLen() {
        anchorLock.lock();
        try {
            return anchorMsgLen;
        } finally {
            anchorLock.unlock();
        }
    }

    public void updateAnchor(long anchor, int msgLen) {
        anchorLock.lock();
        try {
            this.usageAnchor = anchor;
            this.anchorMsgLen = msgLen;
        } finally {
            anchorLock.unlock();
        }
    }

    /** 主循环与手动 /compact 互斥（F37）。返回锁 guard（try-with-resources 用）。 */
    public RunGuard acquireRun() {
        runLock.lock();
        return new RunGuard(runLock);
    }

    public boolean tryAcquireRun() {
        return runLock.tryLock();
    }

    public void releaseRun() {
        runLock.unlock();
    }

    /** ch09 /resume：替换会话上下文并重置锚点（历史已切换，锚点失效）。 */
    public void resetSession(SessionContext newSession) {
        anchorLock.lock();
        try {
            this.session = newSession;
            this.usageAnchor = 0;
            this.anchorMsgLen = 0;
        } finally {
            anchorLock.unlock();
        }
    }

    /** RunLock guard。 */
    public record RunGuard(ReentrantLock lock) implements AutoCloseable {
        @Override
        public void close() {
            lock.unlock();
        }
    }
}
