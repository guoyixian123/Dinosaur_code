package dinocode.agent;

import dinocode.core.ChatEvent;
import dinocode.core.Message;
import dinocode.core.Role;
import dinocode.core.ToolCall;
import dinocode.core.Usage;
import dinocode.provider.ChatProvider;
import dinocode.provider.ChatRequest;
import dinocode.provider.EventStream;
import dinocode.tool.Result;
import dinocode.tool.Tool;
import dinocode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fake Provider 驱动单轮闭环（AC8/AC9），不碰网络。
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

    private static final class FakeProvider implements ChatProvider {
        private final List<List<ChatEvent>> responses;
        private int callCount;

        FakeProvider(List<List<ChatEvent>> responses) {
            this.responses = responses;
        }

        @Override
        public EventStream chat(ChatRequest request) {
            int i = Math.min(callCount, responses.size() - 1);
            callCount++;
            return new FakeStream(responses.get(i));
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
        @Override
        public String name() {
            return "Fake";
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
        public Result execute(Map<String, Object> args) {
            return Result.ok("工具结果");
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

    private static Agent agent(FakeProvider provider) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool());
        return new Agent(provider, registry);
    }

    private static List<Message> history() {
        List<Message> h = new ArrayList<>();
        h.add(Message.user("帮我做点事"));
        return h;
    }

    @Test
    void singleToolCallRunsThenContinuesToFinalText() {
        FakeProvider provider = new FakeProvider(List.of(
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id1", "Fake", "{}"))),
                List.of(new ChatEvent.TextDelta("最终答复"), new ChatEvent.Done(Usage.UNKNOWN))));

        List<Message> history = history();
        List<TurnEvent> events = drain(agent(provider).run(history, 4096));

        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolStart));
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolEnd t && !t.isError()));
        assertTrue(events.stream().anyMatch(
                e -> e instanceof TurnEvent.Text t && t.delta().equals("最终答复")));
        // 历史末尾为 assistant 文本
        Message last = history.get(history.size() - 1);
        assertEquals(Role.ASSISTANT, last.role());
        assertEquals("最终答复", last.content());
        assertEquals(2, provider.callCount());
    }

    @Test
    void secondToolCallIsIgnored() {
        FakeProvider provider = new FakeProvider(List.of(
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id1", "Fake", "{}"))),
                List.of(new ChatEvent.ToolCallComplete(new ToolCall("id2", "Fake", "{}"))),
                List.of(new ChatEvent.TextDelta("不该出现"))));

        List<Message> history = history();
        List<TurnEvent> events = drain(agent(provider).run(history, 4096));

        // 只执行一轮工具，不发起第三轮
        assertEquals(1, events.stream().filter(e -> e instanceof TurnEvent.ToolStart).count());
        assertFalse(events.stream().anyMatch(
                e -> e instanceof TurnEvent.Text t && t.delta().equals("不该出现")));
        assertEquals(2, provider.callCount()); // 请求#1 + 请求#2，无第三次
    }

    @Test
    void noToolCallIsPlainTurn() {
        FakeProvider provider = new FakeProvider(List.of(
                List.of(new ChatEvent.TextDelta("直接回答"), new ChatEvent.Done(Usage.UNKNOWN))));

        List<Message> history = history();
        List<TurnEvent> events = drain(agent(provider).run(history, 4096));

        assertFalse(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolStart));
        assertEquals("直接回答", history.get(history.size() - 1).content());
    }
}
