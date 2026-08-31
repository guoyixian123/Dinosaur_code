package dinocode.session.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import dinocode.core.Message;
import dinocode.core.Role;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSONL 会话写入器（ch09 F13~F16）：追加写 + 锁保证多线程原子 + 每条刷盘（崩溃最多丢最后一行）。
 * 实现 Session 的 onAppend / onReplace 回调契约（F44）。
 */
public final class Writer implements Closeable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BufferedWriter out;
    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean firstMessage = true;

    private Writer(Path file, BufferedWriter out) {
        this.file = file;
        this.out = out;
    }

    /** 新会话：创建目录并打开（追加模式）。 */
    public static Writer create(Path sessionDir) throws IOException {
        Files.createDirectories(sessionDir);
        return open(sessionDir);
    }

    /** 恢复会话：不创建目录，直接追加打开（F22/F18）。 */
    public static Writer open(Path sessionDir) throws IOException {
        Path file = sessionDir.resolve("conversation.jsonl");
        BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        return new Writer(file, out);
    }

    /** 追加单条消息；isFirst 时携带 model 标签（F11/F20）。 */
    public void append(Message msg, String model, boolean isFirst) throws IOException {
        String role = switch (msg.role()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
        Entry entry = new Entry(null, role, msg.content(),
                msg.role() == Role.ASSISTANT ? msg.toolCalls() : null,
                msg.role() == Role.TOOL ? msg.toolResults() : null,
                Instant.now().getEpochSecond(),
                isFirst ? model : null);
        writeLine(JSON.writeValueAsString(entry));
    }

    /** 压缩标记行（F12）：恢复时从此标记之后加载。 */
    public void writeCompactMarker() throws IOException {
        writeLine("{\"type\":\"" + Entry.TYPE_COMPACT + "\",\"ts\":"
                + Instant.now().getEpochSecond() + "}");
    }

    /** 逐条追加（压缩后的新消息，F12）。 */
    public void appendAll(List<Message> msgs) throws IOException {
        for (Message m : msgs) {
            append(m, null, false);
        }
    }

    private void writeLine(String json) throws IOException {
        lock.lock();
        try {
            out.write(json);
            out.write('\n');
            out.flush(); // F15：每条刷盘，崩溃最多丢最后一行
            firstMessage = false;
        } finally {
            lock.unlock();
        }
    }

    /** 是否已写入过消息（首条携带 model 的判断辅助）。 */
    public boolean isFirstMessage() {
        return firstMessage;
    }

    // ---------- Session 回调契约（ch09 F44）：吞掉 IO 异常（写入失败不中断对话，N5） ----------

    /** Session.onAppend 回调入口。 */
    public void archiveAppend(Message msg) {
        try {
            append(msg, null, false);
        } catch (IOException e) {
            System.err.println("[session] warn: 存档写入失败: " + e.getMessage());
        }
    }

    /** Session.onReplace 回调入口：先写 compact 标记再逐条追加新消息（F12/AC9）。 */
    public void archiveReplace(List<Message> msgs) {
        try {
            writeCompactMarker();
            appendAll(msgs);
        } catch (IOException e) {
            System.err.println("[session] warn: 存档替换失败: " + e.getMessage());
        }
    }

    public Path file() {
        return file;
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            out.close();
        } finally {
            lock.unlock();
        }
    }
}
