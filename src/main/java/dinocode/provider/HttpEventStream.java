package dinocode.provider;

import dinocode.core.ChatEvent;
import dinocode.core.ErrorKind;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP 响应体上的事件流基类：逐个读 SSE 事件并交给子类翻译。
 * - map() 可把一个 SSE 事件翻译成 0..n 个统一事件（如 [DONE] → 工具调用 + Done）；
 * - close() 置位后，读错误 / 流结束都静默终止（返回 null），由调用方按"自主中断"处理；
 * - 未 close 却提前读到 EOF（无结束标志）或 IOException，视为网络异常，发 Failure。
 */
abstract class HttpEventStream implements EventStream {

    private final SseReader sse;
    private final InputStream body;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Deque<ChatEvent> pending = new ArrayDeque<>();
    private boolean finished;

    protected HttpEventStream(InputStream body) {
        this.body = body;
        this.sse = new SseReader(body);
    }

    /** 把一个 SSE 事件翻译为 0..n 个统一事件；返回空列表/null 表示忽略（如 ping、未知事件、坏 JSON）。 */
    protected abstract List<ChatEvent> map(SseReader.SseEvent event);

    @Override
    public ChatEvent next() {
        while (true) {
            if (!pending.isEmpty()) {
                return pending.pollFirst();
            }
            if (finished) {
                return null;
            }
            if (closed.get()) {
                finished = true;
                return null;
            }
            SseReader.SseEvent raw;
            try {
                raw = sse.next();
            } catch (IOException e) {
                finished = true;
                return closed.get() ? null
                        : new ChatEvent.Failure(ErrorKind.NETWORK, "网络异常: " + brief(e));
            }
            if (raw == null) {
                finished = true;
                return closed.get() ? null
                        : new ChatEvent.Failure(ErrorKind.NETWORK, "网络异常: 连接被中断");
            }
            List<ChatEvent> mapped;
            try {
                mapped = map(raw);
            } catch (RuntimeException e) {
                continue; // 单条坏事件跳过，容错第三方端点
            }
            if (mapped == null || mapped.isEmpty()) {
                continue;
            }
            for (ChatEvent ev : mapped) {
                if (ev instanceof ChatEvent.Done || ev instanceof ChatEvent.Failure) {
                    finished = true;
                }
            }
            pending.addAll(mapped);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                sse.close();
            } catch (IOException ignored) {
                // 中断路径，忽略关闭错误
            }
            try {
                body.close();
            } catch (IOException ignored) {
                // 同上
            }
        }
    }

    protected static String brief(IOException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }
}
