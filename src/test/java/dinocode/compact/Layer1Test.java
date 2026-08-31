package dinocode.compact;

import dinocode.compact.state.ContentReplacementState;
import dinocode.compact.state.SessionContext;
import dinocode.core.Message;
import dinocode.core.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer1 单测（ch08 AC1~AC4）：单条/聚合落盘、幂等、决策冻结、落盘失败降级。
 */
class Layer1Test {

    @TempDir
    Path root;

    private SessionContext session() {
        return SessionContext.create(root);
    }

    private static String bigContent(int bytes) {
        return "x".repeat(bytes);
    }

    private static Message toolMsg(String id, String content) {
        return Message.tool(List.of(new ToolResult(id, content, false)));
    }

    @Test
    void singleResultOverLimitIsSpilledAndReplaced() throws Exception {
        SessionContext sc = session();
        ContentReplacementState state = new ContentReplacementState();
        // 60000 字节 > 50000 阈值（AC1）
        List<Message> out = ContextCompactor.offloadAndSnip(
                List.of(toolMsg("t1", bigContent(60000))), state, sc);

        String preview = out.get(0).toolResults().get(0).content();
        assertTrue(preview.contains("[content offloaded] original size: 60000 bytes"));
        assertTrue(preview.contains("[saved to] " + sc.spillDir().resolve("t1")));
        assertTrue(preview.contains("[head preview]"));
        assertTrue(preview.contains("文件读取工具")); // 重读提示
        // 预览头 ≤ 2048 字节（x.repeat 的头部）
        assertTrue(preview.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 60000);
        // 落盘原文存在
        assertEquals(60000, Files.readString(sc.spillDir().resolve("t1")).length());
    }

    @Test
    void aggregateOverLimitSpillsLargestFirst() {
        SessionContext sc = session();
        ContentReplacementState state = new ContentReplacementState();
        // 3 × 80000 = 240000 > 200000（AC2）：按字节大到小落盘直至回落
        // 字节相同时按任意序，但最终聚合 ≤ 200000
        Message msg = Message.tool(List.of(
                new ToolResult("a", bigContent(80000), false),
                new ToolResult("b", bigContent(80000), false),
                new ToolResult("c", bigContent(80000), false)));
        List<Message> out = ContextCompactor.offloadAndSnip(List.of(msg), state, sc);

        long remaining = out.get(0).toolResults().stream()
                .mapToLong(r -> r.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .sum();
        assertTrue(remaining <= 200000, "聚合应回落到 200000 以下，实际 " + remaining);
        long replaced = out.get(0).toolResults().stream()
                .filter(r -> r.content().startsWith("[content offloaded]")).count();
        // 240000 - N×80000 ≤ 200000 → N ≥ 1；同时剩余项聚合达标的最小数量（预览体也有体积但远小于原文）
        assertTrue(replaced >= 1);
    }

    @Test
    void spillIsIdempotentAndDecisionFrozen() throws Exception {
        SessionContext sc = session();
        ContentReplacementState state = new ContentReplacementState();
        List<Message> first = ContextCompactor.offloadAndSnip(
                List.of(toolMsg("t1", bigContent(60000))), state, sc);
        String preview1 = first.get(0).toolResults().get(0).content();
        Path spill = sc.spillDir().resolve("t1");
        String mtime1 = Files.getLastModifiedTime(spill).toString();

        // 第二轮：同 id 再跑（AC3/AC4）——预览逐字节一致、文件不重写
        List<Message> second = ContextCompactor.offloadAndSnip(
                List.of(toolMsg("t1", bigContent(60000))), state, sc);
        assertEquals(preview1, second.get(0).toolResults().get(0).content());
        assertEquals(mtime1, Files.getLastModifiedTime(spill).toString());
    }

    @Test
    void keptDecisionStaysOriginal() {
        SessionContext sc = session();
        ContentReplacementState state = new ContentReplacementState();
        // 小内容：不触发单条也不触发聚合 → KEPT 冻结
        List<Message> out = ContextCompactor.offloadAndSnip(
                List.of(toolMsg("small", "短内容")), state, sc);
        assertEquals("短内容", out.get(0).toolResults().get(0).content());

        // 第二轮仍是原文（决策冻结不翻转）
        List<Message> out2 = ContextCompactor.offloadAndSnip(
                List.of(toolMsg("small", "短内容")), state, sc);
        assertEquals("短内容", out2.get(0).toolResults().get(0).content());
    }

    @Test
    void spillFailureSkipsWithoutLedger() throws Exception {
        SessionContext sc = SessionContext.create(root);
        // 把 spillDir 变成不可写：删目录后建同名文件
        Files.delete(sc.spillDir());
        Files.writeString(sc.spillDir(), "not a dir");

        ContentReplacementState state = new ContentReplacementState();
        List<Message> out = ContextCompactor.offloadAndSnip(
                List.of(toolMsg("t1", bigContent(60000))), state, sc);
        // 落盘失败：保持原文、不写账本（N6/AC4 子断言）
        assertEquals(bigContent(60000), out.get(0).toolResults().get(0).content());
        assertFalse(state.seen("t1"));
    }

    @Test
    void smallResultsBelowLimitsUntouched() {
        SessionContext sc = session();
        ContentReplacementState state = new ContentReplacementState();
        List<Message> msgs = List.of(toolMsg("a", "x".repeat(1000)), toolMsg("b", "y".repeat(1000)));
        List<Message> out = ContextCompactor.offloadAndSnip(msgs, state, sc);
        assertEquals(msgs, out); // 无变化直接返回原列表
    }
}
