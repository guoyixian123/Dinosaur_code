package dinocode.compact.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 工具结果替换决策账本（ch08 F5/N2）。
 * seenIds 记录已决策的 toolUseId（无论替换还是保留），决策冻结不再翻转；
 * replacements 只存「决定替换」那支的预览字符串。
 *
 * <p>并发安全：「查账本 → 决策 → 写账本」在 decideOnce 的同一临界区内原子完成，
 * 不存在「已 Seen 但 replacement 未写」的中间态（AC23a）。
 */
public final class ContentReplacementState {

    public enum Decision {KEPT, REPLACED, SKIP}

    public record DecisionResult(Decision decision, String preview) {
        public static DecisionResult kept() {
            return new DecisionResult(Decision.KEPT, null);
        }

        public static DecisionResult replaced(String preview) {
            return new DecisionResult(Decision.REPLACED, preview);
        }

        public static DecisionResult skip() {
            return new DecisionResult(Decision.SKIP, null);
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> seenIds = new HashSet<>();
    private final Map<String, String> replacements = new HashMap<>();

    /**
     * 一次性完成「查账本 → 决策 → 写账本」。
     * 已 Seen：直接返回存量结果（KEPT→原 content，REPLACED→replacements[id]，不重新构造）。
     * 未 Seen：调 decide 回调（持锁状态）——KEPT 只写 seenIds；REPLACED 两者都写；
     * SKIP（落盘失败）两者都不写，下轮重试。
     */
    public String decideOnce(String id, String original, Supplier<DecisionResult> decide) {
        lock.lock();
        try {
            if (seenIds.contains(id)) {
                return replacements.getOrDefault(id, original);
            }
            DecisionResult r = decide.get();
            return switch (r.decision()) {
                case KEPT -> {
                    seenIds.add(id);
                    yield original;
                }
                case REPLACED -> {
                    seenIds.add(id);
                    replacements.put(id, r.preview());
                    yield r.preview();
                }
                case SKIP -> original;
            };
        } finally {
            lock.unlock();
        }
    }

    /** 该 id 是否已决策过（测试与断言用）。 */
    public boolean seen(String id) {
        lock.lock();
        try {
            return seenIds.contains(id);
        } finally {
            lock.unlock();
        }
    }
}
