package dinocode.agent;

import dinocode.core.ChatEvent;
import dinocode.core.Message;
import dinocode.core.Role;
import dinocode.core.ToolCall;
import dinocode.core.Usage;
import dinocode.provider.ChatProvider;
import dinocode.provider.ChatRequest;
import dinocode.provider.EventStream;
import dinocode.prompt.Reminder;
import dinocode.tool.Result;
import dinocode.tool.Tool;
import dinocode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 循环单测（ch04 AC1–AC9、AC13）：多轮 fake provider、并发分批、停止条件、Plan 工具集。
 */
class AgentTest {

    private static final class FakeStream implements EventStream {
        private final List<ChatEvent> events;
        private int index;

        FakeStream(List<ChatEvent> events) {
            this.events = events;
        }

        @Override
        public ChatEvent next() {
            return index < events.size() ? events.get(index++) : null;
        }

        @Override
        public void close() {
        }
    }

    /** 多轮脚本：第 n 次 chat 返回脚本第 n 条；耗尽后重复最后一条。 */
    private static final class FakeProvider implements ChatProvider {
        final List<List<ChatEvent>> scripts;
        final List<ChatRequest> requests = new ArrayList<>();
        int callCount;

        FakeProvider(List<List<ChatEvent>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public EventStream chat(ChatRequest request) {
            requests.add(request);
            int i = Math.min(callCount, scripts.size() - 1);
            callCount++;
            return new FakeStream(scripts.get(i));
        }

        @Override
        public String model() {
            return "fake";
        }

        @Override
        public String baseUrl() {
            return "http://fake";
        }

        int callCount() {
            return callCount;
        }
    }

    private static final class FakeTool implements Tool {
        private final String name;
        private final boolean readOnly;

        FakeTool(String name, boolean readOnly) {
            this.name = name;
            this.readOnly = readOnly;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "fake tool";
        }

        @Override
        public Map<String, Object> schema() {
            return Map.of("type", "object");
        }

        @Override
        public boolean readOnly() {
            return readOnly;
        }

        @Override
        public Result execute(Map<String, Object> args) {
            return Result.ok(name + " 结果");
        }
    }

    private static List<TurnEvent> drain(TurnStream stream) {
        List<TurnEvent> events = new ArrayList<>();
        TurnEvent event;
        while ((event = stream.next()) != null) {
            events.add(event);
        }
        return events;
    }

    private static List<Message> history() {
        List<Message> h = new ArrayList<>();
        h.add(Message.user("帮我做点事"));
        return h;
    }

    // ---------- 场景 A：多轮链路（AC1/AC2/AC7） ----------

    @Test
    void multiTurnLoopRunsUntilFinalText() {
        FakeProvider provider = new FakeProvider(List.of(
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id1", "Ro", "{}"))),
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id2", "Ro", "{}")),
                        new ChatEvent.Done(new Usage(10, 5))),
                List.of(new ChatEvent.TextDelta("最终答复"), new ChatEvent.Done(new Usage(20, 8)))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("Ro", true));
        List<Message> history = history();

        List<TurnEvent> events = drain(new Agent(provider, registry, "test").run(history, 4096, Mode.NORMAL, new CancelToken()));

        // 三轮迭代（2 轮工具 + 1 轮最终答复）+ Done
        assertEquals(3, events.stream().filter(e -> e instanceof TurnEvent.Iter).count());
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.Text t && t.delta().equals("最终答复")));
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.Done));
        // 用量事件已上抛（F8）
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.UsageReport u
                && u.usage().outputTokens() != null && u.usage().outputTokens() == 8));
        // 历史：user → assistant(tool) → tool → assistant(tool) → tool → assistant(text)
        assertEquals(Role.ASSISTANT, history.get(history.size() - 1).role());
        assertEquals("最终答复", history.get(history.size() - 1).content());
        assertEquals(2, history.stream().filter(m -> m.role() == Role.TOOL).count());
        assertEquals(3, provider.callCount());
    }

    // ---------- 场景 B：迭代上限（AC3） ----------

    @Test
    void iterationCapStopsLoop() {
        // 恒返回工具调用的脚本（耗尽后重复最后一条 → 永远有工具）
        FakeProvider provider = new FakeProvider(List.of(
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id", "Ro", "{}")))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("Ro", true));
        List<Message> history = history();

        List<TurnEvent> events = drain(new Agent(provider, registry, "test").run(history, 4096, Mode.NORMAL, new CancelToken()));

        assertEquals(AgentConstants.MAX_ITERATIONS, provider.callCount());
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.Notice n
                && n.message().equals(AgentConstants.NOTICE_MAX_ITER)));
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.Done));
        // 历史以 assistant 文本收尾（F6）
        assertEquals(Role.ASSISTANT, history.get(history.size() - 1).role());
    }

    // ---------- 场景 C：连续未知工具（AC4） ----------

    @Test
    void consecutiveUnknownToolsStopLoop() {
        FakeProvider provider = new FakeProvider(List.of(
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id", "幻觉工具", "{}")))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("Ro", true));
        List<Message> history = history();

        List<TurnEvent> events = drain(new Agent(provider, registry, "test").run(history, 4096, Mode.NORMAL, new CancelToken()));

        assertEquals(AgentConstants.MAX_UNKNOWN_RUN, provider.callCount());
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.Notice n
                && n.message().equals(AgentConstants.NOTICE_UNKNOWN_TOOLS)));
    }

    @Test
    void mixedKnownToolResetsUnknownCounter() {
        // 未知、未知、已知、未知、未知、未知 → 第 6 轮才停
        FakeProvider provider = new FakeProvider(List.of(
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id1", "幻觉", "{}"))),
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id2", "幻觉", "{}"))),
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id3", "Ro", "{}"))),
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id4", "幻觉", "{}"))),
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id5", "幻觉", "{}"))),
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id6", "幻觉", "{}")))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("Ro", true));
        List<Message> history = history();

        drain(new Agent(provider, registry, "test").run(history, 4096, Mode.NORMAL, new CancelToken()));

        assertEquals(6, provider.callCount());
    }

    // ---------- 场景 D：保序分批并发（AC8/N6） ----------

    @Test
    void readOnlyBatchRunsConcurrentlyInOrder() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicLong roEnd = new AtomicLong();
        AtomicLong rwStart = new AtomicLong();

        Tool slowRo = new Tool() {
            @Override
            public String name() {
                return "SlowRo";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            @Override
            public boolean readOnly() {
                return true;
            }

            @Override
            public Result execute(Map<String, Object> args) {
                int now = concurrent.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                roEnd.set(System.nanoTime());
                return Result.ok("ro 完成");
            }
        };
        Tool rw = new Tool() {
            @Override
            public String name() {
                return "Rw";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            @Override
            public boolean readOnly() {
                return false;
            }

            @Override
            public Result execute(Map<String, Object> args) {
                rwStart.set(System.nanoTime());
                return Result.ok("rw 完成");
            }
        };

        FakeProvider provider = new FakeProvider(List.of(
                List.of(
                        new ChatEvent.ToolCallComplete(new ToolCall("c1", "SlowRo", "{}")),
                        new ChatEvent.ToolCallComplete(new ToolCall("c2", "SlowRo", "{}")),
                        new ChatEvent.ToolCallComplete(new ToolCall("c3", "Rw", "{}"))),
                List.of(new ChatEvent.TextDelta("完成"), new ChatEvent.Done(Usage.UNKNOWN))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(slowRo);
        registry.register(rw);
        List<Message> history = history();

        List<TurnEvent> events = drain(new Agent(provider, registry, "test").run(history, 4096, Mode.NORMAL, new CancelToken()));

        // 两只读确实并发（峰值 ≥2）
        assertTrue(peak.get() >= 2, "只读批应并发，峰值=" + peak.get());
        // rw 在只读批完成之后开始（串行，保序）
        assertTrue(rwStart.get() > roEnd.get(), "有副作用工具应在只读批之后");
        // Start/End 事件按调用序（N3）
        List<String> startOrder = events.stream()
                .filter(e -> e instanceof TurnEvent.ToolStart)
                .map(e -> ((TurnEvent.ToolStart) e).name())
                .toList();
        assertEquals(List.of("SlowRo", "SlowRo", "Rw"), startOrder);
        List<String> endOrder = events.stream()
                .filter(e -> e instanceof TurnEvent.ToolEnd)
                .map(e -> ((TurnEvent.ToolEnd) e).name())
                .toList();
        assertEquals(List.of("SlowRo", "SlowRo", "Rw"), endOrder);
        // 结果按原始调用序回灌（F5）
        Message toolTurn = history.stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertEquals("c1", toolTurn.toolResults().get(0).toolCallId());
        assertEquals("c2", toolTurn.toolResults().get(1).toolCallId());
        assertEquals("c3", toolTurn.toolResults().get(2).toolCallId());
    }

    // ---------- 场景 E：取消历史一致（AC9） ----------

    @Test
    void cancelMidExecutionKeepsHistoryLegal() {
        Tool blocking = new Tool() {
            @Override
            public String name() {
                return "Blocking";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            @Override
            public boolean readOnly() {
                return false;
            }

            @Override
            public Result execute(Map<String, Object> args) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Result.ok("不该到这里");
            }
        };

        FakeProvider provider = new FakeProvider(List.of(
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id1", "Blocking", "{}")))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(blocking);
        List<Message> history = history();

        CancelToken cancel = new CancelToken();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300); // 等工具进入执行
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cancel.cancel();
        });

        List<TurnEvent> events = drain(new Agent(provider, registry, "test").run(history, 4096, Mode.NORMAL, cancel));

        // 历史：user → assistant(tool) → tool(含已取消占位) → assistant(取消文案) —— 配对合法
        assertEquals(Role.ASSISTANT, history.get(history.size() - 1).role());
        assertEquals(AgentConstants.NOTICE_CANCELLED, history.get(history.size() - 1).content());
        Message toolTurn = history.stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertEquals(1, toolTurn.toolResults().size());
        assertTrue(toolTurn.toolResults().get(0).isError());
        assertEquals(1, provider.callCount()); // 取消后不再发起下一轮
    }

    // ---------- 场景 F：Plan 工具集 + 按轮次 reminder（ch05 F7/AC9） ----------

    @Test
    void planModeSendsReadOnlyToolsAndFullReminder() {
        FakeProvider provider = new FakeProvider(List.of(
                List.of(new ChatEvent.TextDelta("计划内容"), new ChatEvent.Done(Usage.UNKNOWN))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("ReadFile", true));
        registry.register(new FakeTool("Bash", false));
        List<Message> history = history();

        List<TurnEvent> events = drain(new Agent(provider, registry, "test")
                .run(history, 4096, Mode.PLAN, new CancelToken()));

        assertEquals(1, provider.callCount());
        ChatRequest req = provider.requests.get(0);
        assertEquals(1, req.tools().size());
        assertEquals("ReadFile", req.tools().get(0).name());
        // 首轮完整提醒，带 system-reminder 标签（AC8/AC9）
        assertEquals(Reminder.plan(true), req.reminder());
        assertTrue(req.reminder().contains("<system-reminder>"));
        assertTrue(req.reminder().contains("计划模式"));
        // reminder 不写入持久历史（F6/N3）
        assertTrue(history.stream().noneMatch(m -> m.content().contains("<system-reminder>")));
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.Text t && t.delta().equals("计划内容")));
    }

    @Test
    void normalModeSendsAllToolsAndNoReminder() {
        FakeProvider provider = new FakeProvider(List.of(
                List.of(new ChatEvent.TextDelta("完成"), new ChatEvent.Done(Usage.UNKNOWN))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("ReadFile", true));
        registry.register(new FakeTool("Bash", false));
        List<Message> history = history();

        List<Message> planHistory = history();
        FakeProvider planProvider = new FakeProvider(List.of(
                List.of(new ChatEvent.TextDelta("计划"), new ChatEvent.Done(Usage.UNKNOWN))));

        drain(new Agent(provider, registry, "test").run(history, 4096, Mode.NORMAL, new CancelToken()));
        drain(new Agent(planProvider, registry, "test").run(planHistory, 4096, Mode.PLAN, new CancelToken()));

        // 普通模式全量工具、无 reminder；规划模式只读工具
        assertEquals(2, provider.requests.get(0).tools().size());
        assertEquals("", provider.requests.get(0).reminder());
        assertEquals(1, planProvider.requests.get(0).tools().size());
        // 稳定系统提示跨模式一致（F7/N1）；环境段非空（F2）
        assertEquals(provider.requests.get(0).systemStable(), planProvider.requests.get(0).systemStable());
        assertFalse(provider.requests.get(0).systemStable().isEmpty());
        assertFalse(provider.requests.get(0).systemEnvironment().isEmpty());
    }

    @Test
    void planReminderCadenceFullThenConcise() {
        // 6 轮都返回工具调用 → 触达迭代上限；观察 iter1..6 的 reminder 详略
        FakeProvider provider = new FakeProvider(List.of(
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id", "ReadFile", "{}")))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("ReadFile", true));
        List<Message> history = history();

        drain(new Agent(provider, registry, "test").run(history, 4096, Mode.PLAN, new CancelToken()));

        assertEquals(AgentConstants.MAX_ITERATIONS, provider.callCount());
        for (int i = 0; i < provider.callCount(); i++) {
            String reminder = provider.requests.get(i).reminder();
            boolean expectFull = (i == 0) || (i % Agent.PLAN_REMINDER_INTERVAL == 0);
            assertEquals(Reminder.plan(expectFull), reminder, "iter=" + (i + 1));
        }
    }

    // ---------- 缓存用量透传（ch05 F4/AC6） ----------

    @Test
    void cacheUsagePassesThroughToUsageReport() {
        FakeProvider provider = new FakeProvider(List.of(
                List.of(new ChatEvent.TextDelta("答复"),
                        new ChatEvent.Done(new Usage(100, 20, 1500, 800)))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("Ro", true));
        List<Message> history = history();

        List<TurnEvent> events = drain(new Agent(provider, registry, "test")
                .run(history, 4096, Mode.NORMAL, new CancelToken()));

        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.UsageReport u
                && Integer.valueOf(1500).equals(u.usage().cacheWrite())
                && Integer.valueOf(800).equals(u.usage().cacheRead())));
        // 稳定块跨轮不变（N1）：同一 run 内两次请求 stable 逐字节相等
        if (provider.requests.size() > 1) {
            assertEquals(provider.requests.get(0).systemStable(), provider.requests.get(1).systemStable());
        }
    }

    // ---------- 流出错（AC5） ----------

    @Test
    void streamFailureStopsLoopAndKeepsSession() {
        FakeProvider provider = new FakeProvider(List.of(
                List.of(new ChatEvent.Failure(dinocode.core.ErrorKind.NETWORK, "网络异常"))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("Ro", true));
        List<Message> history = history();

        List<TurnEvent> events = drain(new Agent(provider, registry, "test").run(history, 4096, Mode.NORMAL, new CancelToken()));

        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.Error));
        assertEquals(1, provider.callCount());
    }
}
