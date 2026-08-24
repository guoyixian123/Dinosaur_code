package dino.provider;

import dino.core.ChatEvent;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * fixture 回放（含 thinking 增量与 ping 忽略）。
 */
class AnthropicProviderTest {

    private InputStream fixture(String name) {
        return getClass().getResourceAsStream("/fixtures/" + name);
    }

    @Test
    void replaysFixtureWithThinkingBeforeText() {
        var provider = new AnthropicProvider("http://unused", "k", "claude-test");
        List<ChatEvent> events = new ArrayList<>();
        try (EventStream stream = provider.streamFrom(fixture("anthropic-stream.txt"))) {
            ChatEvent event;
            while ((event = stream.next()) != null) {
                events.add(event);
            }
        }

        assertEquals(List.of(
                new ChatEvent.ThinkingDelta("让我想想"),
                new ChatEvent.ThinkingDelta("……"),
                new ChatEvent.TextDelta("你好 "),
                new ChatEvent.TextDelta("！")), events.subList(0, 4));
        ChatEvent.Done done = assertInstanceOf(ChatEvent.Done.class, events.get(4));
        assertEquals(Integer.valueOf(10), done.usage().inputTokens());
        assertEquals(Integer.valueOf(25), done.usage().outputTokens());
        assertEquals(5, events.size());
    }
}
