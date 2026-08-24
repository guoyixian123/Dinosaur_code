package dino.provider;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * checklist §B 的 SSE 解析覆盖面：半截行、多事件、空行、注释、结束哨兵。
 */
class SseReaderTest {

    private SseReader reader(String sse) {
        InputStream in = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));
        return new SseReader(in);
    }

    @Test
    void simpleEventWithTypeAndData() throws Exception {
        SseReader r = reader("event: message_start\ndata: {\"a\":1}\n\n");
        SseReader.SseEvent event = r.next();
        assertEquals("message_start", event.event());
        assertEquals("{\"a\":1}", event.data());
        assertNull(r.next());
    }

    @Test
    void multipleDataLinesJoinedWithNewline() throws Exception {
        SseReader r = reader("data: line1\ndata: line2\n\n");
        assertEquals("line1\nline2", r.next().data());
    }

    @Test
    void commentsAreIgnored() throws Exception {
        SseReader r = reader(": keep-alive\ndata: hello\n\n");
        assertEquals("hello", r.next().data());
        assertNull(r.next());
    }

    @Test
    void extraBlankLinesDoNotProduceEvents() throws Exception {
        SseReader r = reader("\n\ndata: one\n\n\n\ndata: two\n\n");
        assertEquals("one", r.next().data());
        assertEquals("two", r.next().data());
        assertNull(r.next());
    }

    @Test
    void consecutiveEventsBackToBack() throws Exception {
        SseReader r = reader("data: a\n\ndata: b\n\ndata: c\n\n");
        assertEquals("a", r.next().data());
        assertEquals("b", r.next().data());
        assertEquals("c", r.next().data());
        assertNull(r.next());
    }

    @Test
    void crlfLineEndings() throws Exception {
        SseReader r = reader("event: ping\r\ndata: x\r\n\r\n");
        SseReader.SseEvent event = r.next();
        assertEquals("ping", event.event());
        assertEquals("x", event.data());
    }

    @Test
    void pendingEventWithoutTrailingBlankLineIsDropped() throws Exception {
        // 半截行：连接在事件未收尾时断开 → 丢弃，返回 null（上层判定网络异常）
        SseReader r = reader("data: half");
        assertNull(r.next());
    }

    @Test
    void spaceAfterColonIsNotPartOfValue() throws Exception {
        SseReader r = reader("data:  two-spaces\n\n");
        assertEquals(" two-spaces", r.next().data());
    }
}
