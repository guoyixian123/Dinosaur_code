package dinocode.teams;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 基于文件锁的 Agent 邮箱（ch15 F4/F5/T2）：跨进程并发安全——
 * tmux 队员进程与 Lead 进程不共享 JVM 堆，靠 lock 文件保证写原子性（N1）。
 * 锁 10 秒过期自动清理防死锁。
 */
public final class FileMailBox {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(
            SerializationFeature.INDENT_OUTPUT);
    private static final int MAX_RETRIES = 10;
    private static final long MIN_SLEEP_MS = 5;
    private static final long MAX_SLEEP_MS = 100;
    private static final long LOCK_STALE_MS = 10_000;

    private final Path baseDir;

    /** 邮箱目录：<baseDir>/<agentId>.json。 */
    public FileMailBox(Path baseDir) {
        this.baseDir = baseDir;
    }

    /** 单条消息（F5）：timestamp 用 ISO 字符串（Jackson 默认配置可序列化）。 */
    public record MailMessage(
            String from,
            String text,
            String timestamp,
            boolean read,
            String color,
            String summary) {

        /** 便利构造器：自动填时间戳与默认值。 */
        public MailMessage(String from, String text) {
            this(from, text, java.time.Instant.now().toString(), false, "", "");
        }
    }

    private Path inboxPath(String agentId) {
        return baseDir.resolve(agentId + ".json");
    }

    private Path lockPath(String agentId) {
        return baseDir.resolve(agentId + ".json.lock");
    }

    /** 发送（F5）：read 强制置 false——收件人必须看到。 */
    public void send(String toAgentId, MailMessage message) {
        MailMessage toWrite = new MailMessage(message.from(), message.text(),
                message.timestamp(), false, message.color(), message.summary());
        withLock(toAgentId, () -> {
            List<MailMessage> inbox = readInbox(toAgentId);
            inbox.add(toWrite);
            writeInbox(toAgentId, inbox);
            return null;
        });
    }

    /** 读所有未读消息。 */
    public List<MailMessage> readUnread(String agentId) {
        return withLock(agentId, () -> readInbox(agentId).stream()
                .filter(m -> !m.read())
                .toList());
    }

    /** 全部标记已读。 */
    public void markAllRead(String agentId) {
        withLock(agentId, () -> {
            List<MailMessage> inbox = readInbox(agentId);
            List<MailMessage> marked = new ArrayList<>();
            for (MailMessage m : inbox) {
                marked.add(new MailMessage(m.from(), m.text(), m.timestamp(), true, m.color(), m.summary()));
            }
            if (!marked.equals(inbox)) {
                writeInbox(agentId, marked);
            }
            return null;
        });
    }

    /** 读收件箱；文件不存在返回空列表。 */
    private List<MailMessage> readInbox(String agentId) {
        Path file = inboxPath(agentId);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(file.toFile(), new TypeReference<List<MailMessage>>() {
            });
        } catch (IOException e) {
            return new ArrayList<>(); // 损坏降级为空
        }
    }

    private void writeInbox(String agentId, List<MailMessage> messages) throws IOException {
        Files.createDirectories(baseDir);
        Path file = inboxPath(agentId);
        Path tmp = baseDir.resolve(agentId + ".json.tmp");
        MAPPER.writeValue(tmp.toFile(), messages);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 带返回值的锁动作。 */
    @FunctionalInterface
    private interface LockSupplier<T> {
        T run() throws IOException;
    }

    /** 文件锁执行（F4/N1）：createFile 原子抢锁；冲突重试 10 次（5-100ms 随机退避）；>10s 过期强清。 */
    private <T> T withLock(String agentId, LockSupplier<T> action) {
        Path lock = lockPath(agentId);
        long waitStart = System.currentTimeMillis();
        int retries = 0;
        while (true) {
            try {
                Files.createDirectories(baseDir);
                Files.createFile(lock); // 原子抢锁
                break;
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("FileAlreadyExistsException")
                        || e instanceof java.nio.file.FileAlreadyExistsException) {
                    // 过期锁清理（>10s）
                    try {
                        if (Files.exists(lock) && Files.getLastModifiedTime(lock).toMillis()
                                < System.currentTimeMillis() - LOCK_STALE_MS) {
                            Files.deleteIfExists(lock);
                            continue; // 立即重抢
                        }
                    } catch (IOException ignored) {
                        //
                    }
                    if (++retries > MAX_RETRIES) {
                        throw new MailBoxException("mailbox lock timeout: " + lock);
                    }
                    long backoff = ThreadLocalRandom.current().nextLong(MIN_SLEEP_MS, MAX_SLEEP_MS);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new MailBoxException("等待 mailbox 锁被中断", ie);
                    }
                    // 重新检查总等待时间是否已超 10s（过期清理触发点）
                    if (System.currentTimeMillis() - waitStart > LOCK_STALE_MS) {
                        try {
                            Files.deleteIfExists(lock); // 过期强清
                        } catch (IOException ignored) {
                            //
                        }
                    }
                    continue;
                }
                throw new MailBoxException("mailbox 锁操作失败: " + e.getMessage(), e);
            }
        }
        try {
            return action.run();
        } catch (IOException e) {
            throw new MailBoxException("mailbox 操作失败: " + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(lock);
            } catch (IOException ignored) {
                // finally 清锁失败：靠 10s 过期机制兜底
            }
        }
    }

    /** 邮箱操作失败。 */
    public static final class MailBoxException extends RuntimeException {
        public MailBoxException(String message) {
            super(message);
        }

        public MailBoxException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
