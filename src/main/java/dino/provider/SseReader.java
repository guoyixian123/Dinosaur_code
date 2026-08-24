package dino.provider;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * SSE（text/event-stream）行读取器，协议无关。
 * 规则遵循 SSE 规范：
 * - 空行分发一个事件；
 * - "data:" 多行以换行拼接；"event:" 记录事件类型；
 * - 冒号开头的行是注释（心跳），忽略；
 * - EOF 时未以空行收尾的半截事件丢弃（视为流被截断，由上层判定中断/网络错误）。
 */
public final class SseReader implements Closeable {

    /** @param event 事件类型，可能为 null；@param data 数据，可能为空串 */
    public record SseEvent(String event, String data) {
    }

    private final BufferedReader in;
    private final StringBuilder data = new StringBuilder();
    private boolean pendingData;
    private String eventType;

    public SseReader(InputStream body) {
        this.in = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    }

    /** 读下一个完整事件；流结束返回 null。 */
    public SseEvent next() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) {
                if (pendingData || eventType != null) {
                    SseEvent event = new SseEvent(eventType, data.toString());
                    reset();
                    return event;
                }
                continue;
            }
            if (line.startsWith(":")) {
                continue; // 注释 / 心跳
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1); // 规范：冒号后单个空格不属于值
            }
            switch (field) {
                case "data" -> {
                    if (pendingData) {
                        data.append('\n');
                    }
                    data.append(value);
                    pendingData = true;
                }
                case "event" -> eventType = value;
                default -> {
                    // id / retry / 未知字段：忽略
                }
            }
        }
        reset(); // EOF：丢弃半截事件
        return null;
    }

    private void reset() {
        data.setLength(0);
        pendingData = false;
        eventType = null;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
