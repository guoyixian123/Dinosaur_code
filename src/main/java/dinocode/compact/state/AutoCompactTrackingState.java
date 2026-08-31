package dinocode.compact.state;

import dinocode.compact.CompactConstants;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 自动摘要熔断计数（ch08 F28/F29）：连续失败达到阈值即熔断自动路径。
 * 手动 /compact 与紧急压缩不读此状态；任意一次自动摘要成功立即清零（AC19）。
 */
public final class AutoCompactTrackingState {

    private final ReentrantLock lock = new ReentrantLock();
    private int consecutiveFailures;

    public void recordSuccess() {
        lock.lock();
        try {
            consecutiveFailures = 0;
        } finally {
            lock.unlock();
        }
    }

    public void recordFailure() {
        lock.lock();
        try {
            consecutiveFailures++;
        } finally {
            lock.unlock();
        }
    }

    public boolean tripped() {
        lock.lock();
        try {
            return consecutiveFailures >= CompactConstants.MAX_CONSECUTIVE_AUTO_COMPACT_FAILURES;
        } finally {
            lock.unlock();
        }
    }
}
