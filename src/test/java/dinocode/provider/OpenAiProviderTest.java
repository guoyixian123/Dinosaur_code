package dinocode.provider;

import dinocode.core.ChatEvent;
import dinocode.core.ErrorKind;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * fixture 回放（不碰网络）。HTTP 层验证在 T10 离线环境完成。
 */
class OpenAiProviderTest {

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

    @Test
    void replaysFixtureIntoUnifiedEvents() {
        var provider = new OpenAiProvider("http://unused", "k", "gpt-test");
        List<ChatEvent> events = drain(provider.streamFrom(fixture("openai-stream.txt")));

        assertEquals(3, events.size());
        assertEquals(new ChatEvent.TextDelta("Hello"), events.get(0));
        assertEquals(new ChatEvent.TextDelta(" world"), events.get(1));
        ChatEvent.Done done = assertInstanceOf(ChatEvent.Done.class, events.get(2));
        assertEquals(Integer.valueOf(7), done.usage().inputTokens());
        assertEquals(Integer.valueOf(2), done.usage().outputTokens());
        // 缓存命中解析（ch05 F4）：cached_tokens → cacheRead，cacheWrite 恒无
        assertEquals(Integer.valueOf(4), done.usage().cacheRead());
        assertEquals(null, done.usage().cacheWrite());
    }

    @Test
    void replaysReasoningFixtureIntoThinkingAndText() {
        var provider = new OpenAiProvider("http://unused", "k", "gpt-test");
        List<ChatEvent> events = drain(provider.streamFrom(fixture("openai-reasoning.txt")));

        assertEquals(4, events.size());
        assertEquals(new ChatEvent.ThinkingDelta("让我先推理"), events.get(0));
        assertEquals(new ChatEvent.ThinkingDelta("一下"), events.get(1));
        assertEquals(new ChatEvent.TextDelta("答案是 42"), events.get(2));
        assertInstanceOf(ChatEvent.Done.class, events.get(3));
    }

    @Test
    void truncatedStreamBecomesNetworkFailure() {
        var provider = new OpenAiProvider("http://unused", "k", "gpt-test");
        List<ChatEvent> events = drain(provider.streamFrom(fixture("openai-cut.txt")));

        assertEquals(3, events.size());
        assertEquals(new ChatEvent.TextDelta("一半"), events.get(0));
        assertEquals(new ChatEvent.TextDelta("而已"), events.get(1));
        ChatEvent.Failure failure = assertInstanceOf(ChatEvent.Failure.class, events.get(2));
        assertEquals(ErrorKind.NETWORK, failure.kind());
    }

    @Test
    void closeSilentlyEndsStream() {
        var provider = new OpenAiProvider("http://unused", "k", "gpt-test");
        EventStream stream = provider.streamFrom(fixture("openai-stream.txt"));
        assertEquals(new ChatEvent.TextDelta("Hello"), stream.next());
        stream.close(); // 模拟用户中断：之后静默结束
        assertEquals(null, stream.next());
    }

    @Test
    void replaysToolCallsIntoToolCallComplete() {
        var provider = new OpenAiProvider("http://unused", "k", "gpt-test");
        List<ChatEvent> events = drain(provider.streamFrom(fixture("openai-tool.txt")));

        assertEquals(3, events.size());
        assertEquals(new ChatEvent.TextDelta("我来读"), events.get(0));
        ChatEvent.ToolCallComplete call = assertInstanceOf(ChatEvent.ToolCallComplete.class, events.get(1));
        assertEquals("call_1", call.call().id());
        assertEquals("ReadFile", call.call().name());
        assertEquals("{\"path\":\"/tmp/x\"}", call.call().arguments());
        assertInstanceOf(ChatEvent.Done.class, events.get(2));
    }
}
