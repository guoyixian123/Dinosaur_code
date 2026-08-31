package dinocode.compact;

import dinocode.compact.state.AutoCompactTrackingState;
import dinocode.compact.state.ContentReplacementState;
import dinocode.compact.state.SessionContext;
import dinocode.core.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态类与恢复段单测（ch08 AC9/AC11/AC23a/AC23b/AC23c）。
 */
class StateAndRecoveryTest {

    @TempDir
    Path root;

    // ---------- ContentReplacementState（AC23a） ----------

    @Test
    void decideOnceFreezesKeptAndReplaced() {
        ContentReplacementState state = new ContentReplacementState();
        // KEPT 冻结
        assertEquals("原文", state.decideOnce("k1", "原文",
                ContentReplacementState.DecisionResult::kept));
        assertEquals("原文", state.decideOnce("k1", "原文",
                () -> ContentReplacementState.DecisionResult.replaced("不该生效")));// 已冻结 KEPT，回调不执行

        // REPLACED 冻结：预览逐字节复用
        String p1 = state.decideOnce("r1", "长内容",
                () -> ContentReplacementState.DecisionResult.replaced("预览v1"));
        String p2 = state.decideOnce("r1", "长内容",
                () -> ContentReplacementState.DecisionResult.replaced("预览v2"));
        assertEquals("预览v1", p1);
        assertEquals(p1, p2); // 不重新构造（F5d）
    }

    @Test
    void decideOnceSkipDoesNotWriteLedger() {
        ContentReplacementState state = new ContentReplacementState();
        String r = state.decideOnce("s1", "原文",
                ContentReplacementState.DecisionResult::skip);
        assertEquals("原文", r);
        assertFalse(state.seen("s1")); // SKIP 不进账本（F5b）
        // 下次可重新决策
        String r2 = state.decideOnce("s1", "原文",
                () -> ContentReplacementState.DecisionResult.replaced("新预览"));
        assertEquals("新预览", r2);
        assertTrue(state.seen("s1"));
    }

    @Test
    void decideOnceConcurrentNoRace() throws Exception {
        ContentReplacementState state = new ContentReplacementState();
        int threads = 50;
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    for (int j = 0; j < 20; j++) {
                        String r = state.decideOnce("id" + j, "original",
                                () -> ContentReplacementState.DecisionResult.replaced("preview"));
                        // 同一 id 无论谁先决策，后续读到的都是同一份预览
                        assertEquals("preview", r);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
    }

    // ---------- AutoCompactTrackingState（AC19/AC23c） ----------

    @Test
    void autoTrackingTripAndReset() {
        AutoCompactTrackingState t = new AutoCompactTrackingState();
        t.recordFailure();
        t.recordFailure();
        assertFalse(t.tripped());
        t.recordFailure();
        assertTrue(t.tripped()); // 3 次熔断
        t.recordSuccess();
        assertFalse(t.tripped()); // 成功清零
        t.recordFailure();
        assertFalse(t.tripped()); // 重新计数
    }

    // ---------- Recovery（AC9/AC11/AC23b） ----------

    @Test
    void recoverySnapshotOrderByTimestampDesc() throws Exception {
        Recovery.RecoveryState rs = new Recovery.RecoveryState();
        rs.recordFile("a.txt", "a");
        Thread.sleep(5);
        rs.recordFile("b.txt", "b");
        Thread.sleep(5);
        rs.recordFile("c.txt", "c");
        List<Recovery.FileReadRecord> snap = rs.snapshot();
        assertEquals(3, snap.size());
        assertEquals("c.txt", Path.of(snap.get(0).path()).getFileName().toString()); // 最新在前
        assertEquals("a.txt", Path.of(snap.get(2).path()).getFileName().toString());
    }

    @Test
    void recoverySameFileKeepsLatest() {
        Recovery.RecoveryState rs = new Recovery.RecoveryState();
        rs.recordFile("/tmp/x.txt", "v1");
        rs.recordFile("/tmp/x.txt", "v2");
        assertEquals(1, rs.snapshot().size());
        assertEquals("v2", rs.snapshot().get(0).content());
    }

    @Test
    void recoveryAttachmentShowsAtMostFiveFilesTruncated() {
        Recovery.RecoveryState rs = new Recovery.RecoveryState();
        for (int i = 0; i < 7; i++) {
            rs.recordFile("/tmp/f" + i + ".txt", "内容" + i);
        }
        String attachment = Recovery.buildRecoveryAttachment(rs.snapshot(), List.of());
        // 只有最近 5 个（f6..f2）；f1、f0 不出现（AC9）
        assertTrue(attachment.contains("f6.txt"));
        assertTrue(attachment.contains("f2.txt"));
        assertFalse(attachment.contains("f1.txt"));
        assertFalse(attachment.contains("f0.txt"));
        // 边界提示固定文案（AC11）
        assertTrue(attachment.contains("不要依据摘要内容做猜测"));
    }

    @Test
    void recoveryFileBlockTruncatesTail() {
        Recovery.FileReadRecord rec = new Recovery.FileReadRecord("/tmp/big.txt",
                "x".repeat(20000), java.time.Instant.now());
        String block = Recovery.renderFileBlock(rec);
        // 5000 token × 3.5 = 17500 字符保留头部，尾部截掉并标注（AC9）
        assertTrue(block.contains("(content truncated)"));
        assertFalse(block.contains("x".repeat(18000))); // 尾部确被截掉
        assertTrue(block.contains("x".repeat(100)));    // 头部确有内容
    }

    @Test
    void recoveryToolsBlockMatchesDefs() {
        List<ToolDefinition> defs = List.of(
                new ToolDefinition("ReadFile", "读文件", Map.of("type", "object")),
                new ToolDefinition("Bash", "执行命令", Map.of("type", "object")));
        String attachment = Recovery.buildRecoveryAttachment(List.of(), defs);
        assertTrue(attachment.contains("- ReadFile: 读文件"));
        assertTrue(attachment.contains("- Bash: 执行命令"));
        // 工具名集合 == defs（AC10 的集合断言）
        for (ToolDefinition d : defs) {
            assertTrue(attachment.contains(d.name()));
        }
    }

    @Test
    void recoveryAttachmentIsDeterministic() {
        Recovery.RecoveryState rs = new Recovery.RecoveryState();
        rs.recordFile("/tmp/a.txt", "内容");
        List<ToolDefinition> defs = List.of(new ToolDefinition("ReadFile", "读", Map.of()));
        String s1 = Recovery.buildRecoveryAttachment(rs.snapshot(), defs);
        String s2 = Recovery.buildRecoveryAttachment(rs.snapshot(), defs);
        assertEquals(s1, s2);
    }

    @Test
    void sessionContextCreatesSpillDir() {
        SessionContext sc = SessionContext.create(root);
        // ch09 F9：新格式 ID
        assertTrue(sc.sessionId().matches("\\d{8}-\\d{6}-[0-9a-f]{4}"),
                "实际: " + sc.sessionId());
        assertTrue(java.nio.file.Files.isDirectory(sc.spillDir()));
        // 同 ID open：spillDir 一致
        try {
            SessionContext sc2 = SessionContext.open(root, sc.sessionId());
            assertEquals(sc.spillDir(), sc2.spillDir());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
