package dinocode.compact;

import dinocode.compact.state.AutoCompactTrackingState;
import dinocode.compact.state.ContentReplacementState;
import dinocode.compact.state.SessionContext;
import dinocode.core.ChatEvent;
import dinocode.core.ErrorKind;
import dinocode.core.Message;
import dinocode.core.ToolResult;
import dinocode.provider.ChatProvider;
import dinocode.provider.ChatRequest;
import dinocode.provider.EventStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextCompactor 编排集成单测（ch08 AC5~AC8/AC12~AC20）。
 * FakeProvider：第 1 个请求返回摘要文本（无工具），可编程注入错误。
 */
class CompactorTest {

    @TempDir
    Path root;

    /** 可编程 fake：每次 chat 按脚本返回；脚本耗尽重复最后一条。记录收到的请求。 */
    private static final class FakeProvider implements ChatProvider {
        final List<List<ChatEvent>> scripts = new ArrayList<>();
        final List<ChatRequest> requests = new ArrayList<>();
        int calls;

        @Override
        public EventStream chat(ChatRequest request) {
            requests.add(request);
            int i = Math.min(calls, scripts.size() - 1);
            calls++;
            List<ChatEvent> script = scripts.get(i);
            return new EventStream() {
                int idx;

                @Override
                public ChatEvent next() {
                    return idx < script.size() ? script.get(idx++) : null;
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public String model() {
            return "fake";
        }

        @Override
        public String baseUrl() {
            return "http://fake";
        }
    }

    private static FakeProvider summaryProvider() {
        FakeProvider p = new FakeProvider();
        p.scripts.add(List.of(
                new ChatEvent.TextDelta("草稿<summary>"),
                new ChatEvent.TextDelta("## 1 主要请求和意图\n做任务。\n## 6 所有用户消息原文\n用户要我做事。"),
                new ChatEvent.TextDelta("</summary>")));
        return p;
    }

    private static FakeProvider errorProvider(ErrorKind kind, String msg) {
        FakeProvider p = new FakeProvider();
        p.scripts.add(List.of(new ChatEvent.Failure(kind, msg)));
        return p;
    }

    private ContextCompactor.Input input(ChatProvider p, List<Message> msgs, int contextWindow,
                                         ContextCompactor.TriggerKind trigger, int anchorMsgLen) {
        SessionContext sc = SessionContext.create(root);
        return new ContextCompactor.Input(
                msgs, p, contextWindow, List.of(),
                new ContentReplacementState(), new Recovery.RecoveryState(),
                new AutoCompactTrackingState(), sc,
                100000, anchorMsgLen, 190000, trigger);
    }

    private static List<Message> heavyHistory() {
        // 60000 字节工具结果（> 单条 50000 阈值）+ 一条用户消息
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.user("读大文件"));
        msgs.add(Message.assistantWithTools("", List.of(new dinocode.core.ToolCall("t1", "ReadFile", "{}"))));
        msgs.add(Message.tool(List.of(new ToolResult("t1", "x".repeat(60000), false))));
        return msgs;
    }

    // ---------- AUTO 路径（AC5） ----------

    @Test
    void autoBelowThresholdOnlyRunsLayer1() throws Exception {
        FakeProvider p = summaryProvider();
        // anchor=100000 + 大结果 60000B ≈ 117143 token < 200000-33000=167000 → 不触发 Layer2
        List<Message> msgs = heavyHistory();
        ContextCompactor.Result r = ContextCompactor.manage(
                input(p, msgs, 200000, ContextCompactor.TriggerKind.AUTO, 1));

        assertEquals(0, p.calls); // 未发摘要请求（AC5 反向）
        assertNotNull(r.newMsgs()); // Layer1 替换后的列表
        assertTrue(r.newMsgs().get(2).toolResults().get(0).content().startsWith("[content offloaded]"));
    }

    @Test
    void layer1ResultIsNeverTheInputListReference() {
        // 回归守护：offloadAndSnip 无改动时也必须返回新列表。
        // 若返回入参引用，Agent 的 clear+addAll 回写会把对话历史清空
        //（表现为每轮请求只有 system、无 user 消息，输入 token 恒定）。
        List<Message> msgs = List.of(Message.user("用户消息"));
        List<Message> out = ContextCompactor.offloadAndSnip(
                msgs, new ContentReplacementState(),
                SessionContext.create(root));
        assertTrue(out != msgs, "返回值不得与入参是同一引用");
        assertEquals(1, out.size());
        assertEquals("用户消息", out.get(0).content());
    }

    @Test
    void autoTriggersSummaryAtThreshold() throws Exception {
        FakeProvider p = summaryProvider();
        // anchor 拉高让估算越过 167000 阈值
        List<Message> msgs = heavyHistory();
        ContextCompactor.Input in = input(p, msgs, 200000, ContextCompactor.TriggerKind.AUTO, 1);
        ContextCompactor.Input tuned = new ContextCompactor.Input(
                in.messages(), in.provider(), in.contextWindow(), in.toolDefs(),
                in.replacement(), in.recovery(), in.autoTracking(), in.session(),
                180000, 1, 190000, ContextCompactor.TriggerKind.AUTO);

        ContextCompactor.Result r = ContextCompactor.manage(tuned);
        assertEquals(1, p.calls); // 发了一次摘要请求
        // 新历史：摘要 user 消息在首
        assertEquals("## 历史会话摘要", r.newMsgs().get(0).content().substring(0, "## 历史会话摘要".length()));
        assertTrue(r.afterTokens() < r.beforeTokens());
    }

    @Test
    void autoSummaryRequestHasNoTools() throws Exception {
        FakeProvider p = summaryProvider();
        List<Message> msgs = heavyHistory();
        ContextCompactor.Input in = input(p, msgs, 200000, ContextCompactor.TriggerKind.AUTO, 1);
        ContextCompactor.Input tuned = new ContextCompactor.Input(
                in.messages(), in.provider(), in.contextWindow(), in.toolDefs(),
                in.replacement(), in.recovery(), in.autoTracking(), in.session(),
                180000, 1, 190000, ContextCompactor.TriggerKind.AUTO);
        ContextCompactor.manage(tuned);

        assertEquals(1, p.requests.size());
        assertTrue(p.requests.get(0).tools().isEmpty()); // AC6：摘要请求不带工具
    }

    @Test
    void autoSkippedWhenTripped() throws Exception {
        FakeProvider p = summaryProvider();
        List<Message> msgs = heavyHistory();
        ContextCompactor.Input in = input(p, msgs, 200000, ContextCompactor.TriggerKind.AUTO, 1);
        in.autoTracking().recordFailure();
        in.autoTracking().recordFailure();
        in.autoTracking().recordFailure(); // 熔断（AC19）
        ContextCompactor.Input tuned = new ContextCompactor.Input(
                in.messages(), in.provider(), in.contextWindow(), in.toolDefs(),
                in.replacement(), in.recovery(), in.autoTracking(), in.session(),
                180000, 1, 190000, ContextCompactor.TriggerKind.AUTO);

        ContextCompactor.manage(tuned);
        assertEquals(0, p.calls); // 熔断后不自动摘要（F29）
    }

    @Test
    void autoFailureRecordsBreaker() throws Exception {
        FakeProvider p = errorProvider(ErrorKind.OTHER, "内部错误");
        List<Message> msgs = heavyHistory();
        ContextCompactor.Input in = input(p, msgs, 200000, ContextCompactor.TriggerKind.AUTO, 1);
        ContextCompactor.Input tuned = new ContextCompactor.Input(
                in.messages(), in.provider(), in.contextWindow(), in.toolDefs(),
                in.replacement(), in.recovery(), in.autoTracking(), in.session(),
                180000, 1, 190000, ContextCompactor.TriggerKind.AUTO);

        try {
            ContextCompactor.manage(tuned);
        } catch (CompactException expected) {
            // 记一次失败
        }
        assertEquals(1, p.calls);
        // 再失败两次 → 熔断（AC19）
        try {
            ContextCompactor.manage(tuned);
        } catch (CompactException ignored) {
        }
        try {
            ContextCompactor.manage(tuned);
        } catch (CompactException ignored) {
        }
        assertTrue(tuned.autoTracking().tripped());
    }

    // ---------- MANUAL 路径（AC12~AC15） ----------

    @Test
    void manualBypassesThresholdAndBreaker() throws Exception {
        FakeProvider p = summaryProvider();
        List<Message> msgs = heavyHistory();
        ContextCompactor.Input in = input(p, msgs, 200000, ContextCompactor.TriggerKind.MANUAL, 1);
        // 人为熔断
        in.autoTracking().recordFailure();
        in.autoTracking().recordFailure();
        in.autoTracking().recordFailure();

        ContextCompactor.Result r = ContextCompactor.manage(in);
        assertEquals(1, p.calls); // 仍执行摘要（AC14 跳过熔断）
        assertTrue(r.afterTokens() < r.beforeTokens()); // 压缩生效（AC15 数字变化）
    }

    // ---------- EMERGENCY 路径（AC16~AC17 相关编排） ----------

    @Test
    void emergencyRunsLayer1ThenSummary() throws Exception {
        FakeProvider p = summaryProvider();
        List<Message> msgs = heavyHistory();
        ContextCompactor.Input in = input(p, msgs, 200000, ContextCompactor.TriggerKind.EMERGENCY, 1);
        // 人为熔断也不影响（F29）
        in.autoTracking().recordFailure();
        in.autoTracking().recordFailure();
        in.autoTracking().recordFailure();

        ContextCompactor.Result r = ContextCompactor.manage(in);
        assertEquals(1, p.calls);
        assertTrue(Files.exists(in.session().spillDir().resolve("t1"))); // 先跑了 Layer1（AC16 前置）
        assertTrue(r.newMsgs().get(0).content().contains("历史会话摘要"));
    }

    // ---------- PTL 重试（AC18/F27） ----------

    @Test
    void ptlRetryDropsOldestGroupUntilFit() throws Exception {
        // provider 前 2 次返回 PTL，第 3 次成功
        FakeProvider p = new FakeProvider();
        p.scripts.add(List.of(new ChatEvent.Failure(ErrorKind.CONTEXT_OVERFLOW, "prompt is too long")));
        p.scripts.add(List.of(new ChatEvent.Failure(ErrorKind.CONTEXT_OVERFLOW, "prompt is too long")));
        p.scripts.add(List.of(
                new ChatEvent.TextDelta("<summary>摘要成功</summary>")));

        // 5 组 user 消息
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            msgs.add(Message.user("组" + i));
        }
        ContextCompactor.Input in = input(p, msgs, 200000, ContextCompactor.TriggerKind.MANUAL, msgs.size());
        ContextCompactor.Result r = ContextCompactor.manage(in);

        assertEquals(3, p.calls); // 1 初始 + 2 次重试
        assertTrue(r.newMsgs().get(0).content().contains("摘要成功"));
        // 第 3 次请求的输入应比第 1 次少 2 组（丢最旧 1 组/次）
        int len0 = summaryInputLen(p.requests.get(0));
        int len2 = summaryInputLen(p.requests.get(2));
        assertTrue(len2 < len0);
    }

    private static int summaryInputLen(ChatRequest req) {
        return req.history().isEmpty() ? 0
                : req.history().get(0).content().getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void ptlRetryExhaustionThrowsAndCountsOnceInAuto() throws Exception {
        FakeProvider p = new FakeProvider(); // 恒返回 PTL（脚本重复最后一条）
        p.scripts.add(List.of(new ChatEvent.Failure(ErrorKind.CONTEXT_OVERFLOW, "prompt is too long")));

        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            msgs.add(Message.user("组" + i));
        }
        ContextCompactor.Input in = input(p, msgs, 200000, ContextCompactor.TriggerKind.AUTO, msgs.size());
        ContextCompactor.Input tuned = new ContextCompactor.Input(
                in.messages(), in.provider(), in.contextWindow(), in.toolDefs(),
                in.replacement(), in.recovery(), in.autoTracking(), in.session(),
                180000, msgs.size(), 190000, ContextCompactor.TriggerKind.AUTO);

        // 第 1 次自动摘要失败（PTL 重试用光）→ 计 1 次失败
        try {
            ContextCompactor.manage(tuned);
        } catch (CompactException expected) {
            //
        }
        // 再失败 2 次后应熔断（F28：整轮失败算 1 次，3 次熔断）
        try {
            ContextCompactor.manage(tuned);
        } catch (CompactException ignored) {
        }
        try {
            ContextCompactor.manage(tuned);
        } catch (CompactException ignored) {
        }
        assertTrue(tuned.autoTracking().tripped(), "连续 3 次自动摘要失败应熔断");
        // 熔断后第 4 次不再发起请求（AC19）
        int before = p.calls;
        try {
            ContextCompactor.manage(tuned);
        } catch (CompactException ignored) {
        }
        assertEquals(before, p.calls);
    }

    @Test
    void ptlDoesNotSendEmptyMessages() throws Exception {
        FakeProvider p = new FakeProvider();
        p.scripts.add(List.of(new ChatEvent.Failure(ErrorKind.CONTEXT_OVERFLOW, "prompt is too long")));
        // 只有 1 组：丢完 1 组后无剩余 → 直接抛，不发空请求（F27）
        List<Message> msgs = List.of(Message.user("唯一一组"));
        ContextCompactor.Input in = input(p, msgs, 200000, ContextCompactor.TriggerKind.MANUAL, 1);

        try {
            ContextCompactor.manage(in);
        } catch (CompactException expected) {
            //
        }
        assertEquals(1, p.calls); // 只有初始 1 次，没有空消息请求
    }
}
