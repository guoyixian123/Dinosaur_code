package dinocode.provider;

import dinocode.config.ThinkingConfig;
import dinocode.core.ChatEvent;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Anthropic fixture 回放（不碰网络）：思考/正文/工具调用解析（AC7/F4）。
 */
class AnthropicProviderTest {

    private InputStream fixture(String name) {
        return getClass().getResourceAsStream("/fixtures/" + name);
    }

    private List<ChatEvent> drain(EventStream stream) {
        List<ChatEvent> events = new ArrayList<>();
        ChatEvent event;
        while ((event = stream.next()) != null) {
            events.add(event);
        }
        return events;
    }

    private AnthropicProvider provider() {
        return new AnthropicProvider("http://unused", "k", "claude-test", ThinkingConfig.DISABLED);
    }

    @Test
    void replaysPlainStreamIntoThinkingAndText() {
        List<ChatEvent> events = drain(provider().streamFrom(fixture("anthropic-stream.txt")));

        assertEquals(5, events.size());
        assertEquals(new ChatEvent.ThinkingDelta("让我想想"), events.get(0));
        assertEquals(new ChatEvent.ThinkingDelta("……"), events.get(1));
        assertEquals(new ChatEvent.TextDelta("你好 "), events.get(2));
        assertEquals(new ChatEvent.TextDelta("！"), events.get(3));
        ChatEvent.Done done = assertInstanceOf(ChatEvent.Done.class, events.get(4));
        assertEquals(Integer.valueOf(10), done.usage().inputTokens());
        assertEquals(Integer.valueOf(25), done.usage().outputTokens());
        // 缓存字段解析（ch05 F4）：message_start 带回的缓存写入被透传
        assertEquals(Integer.valueOf(100), done.usage().cacheWrite());
        assertEquals(Integer.valueOf(0), done.usage().cacheRead());
    }

    @Test
    void replaysToolUseIntoTextAndToolCall() {
        List<ChatEvent> events = drain(provider().streamFrom(fixture("anthropic-tool.txt")));

        assertEquals(3, events.size());
        assertEquals(new ChatEvent.TextDelta("我来读文件"), events.get(0));
        ChatEvent.ToolCallComplete call = assertInstanceOf(ChatEvent.ToolCallComplete.class, events.get(1));
        assertEquals("toolu_1", call.call().id());
        assertEquals("ReadFile", call.call().name());
        assertEquals("{\"path\":\"/tmp/x\"}", call.call().arguments());
        assertInstanceOf(ChatEvent.Done.class, events.get(2));
    }
}
