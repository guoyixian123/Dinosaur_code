package dino.provider;

import dino.core.ChatEvent;
import dino.core.ErrorKind;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP 响应体上的事件流基类：逐个读 SSE 事件并交给子类翻译。
 * - close() 置位后，读错误 / 流结束都静默终止（返回 null），由调用方按"自主中断"处理；
 * - 未 close 却提前读到 EOF（无结束标志）或 IOException，视为网络异常，发 Failure。
 */
abstract class HttpEventStream implements EventStream {

    private final SseReader sse;
    private final InputStream body;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean finished;

    protected HttpEventStream(InputStream body) {
        this.body = body;
        this.sse = new SseReader(body);
    }

    /** 把一个 SSE 事件翻译为统一事件；返回 null 表示忽略（如 ping、未知事件、坏 JSON）。 */
    protected abstract ChatEvent map(SseReader.SseEvent event);

    @Override
    public ChatEvent next() {
        while (!finished) {
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
            ChatEvent mapped;
            try {
                mapped = map(raw);
            } catch (RuntimeException e) {
                continue; // 单条坏事件跳过，容错第三方端点
            }
            if (mapped == null) {
                continue;
            }
            if (mapped instanceof ChatEvent.Done || mapped instanceof ChatEvent.Failure) {
                finished = true;
            }
            return mapped;
        }
        return null;
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
