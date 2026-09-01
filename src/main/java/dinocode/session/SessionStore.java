package dinocode.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dinocode.core.Message;
import dinocode.core.Role;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 会话持久化。唯一接触会话文件的地方（见 spec 设计骨架）。
 */
public final class SessionStore {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final DateTimeFormatter ID_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dir;

    public SessionStore(Path dir) {
        this.dir = dir;
    }

    /** 默认目录：~/.dino/sessions */
    public static SessionStore defaultStore() {
        return new SessionStore(Path.of(System.getProperty("user.home"), ".dino", "sessions"));
    }

    /**
     * 存盘。目录不存在则创建（见 checklist §C）。失败抛 IOException，由调用方友好提示。
     * 写临时文件 + 原子移动——写一半崩溃不会留下损坏的会话 JSON（对比 JSONL 存档
     * 逐行 flush 的容忍策略，JSON 整文件覆盖写损坏即全丢，须原子）。
     */
    public void save(Session session) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(session.getId() + ".json");
        Path tmp = dir.resolve(session.getId() + ".json.tmp");
        Files.writeString(tmp, JSON.writeValueAsString(stripToolTurns(session)));
        try {
            java.nio.file.Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // 个别文件系统不支持原子移动：降级为普通覆盖（仍优于半截写入）
            java.nio.file.Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 工具回合（TOOL 消息、带工具调用的 assistant 回合）不落盘（ch03 spec「退出即丢」）。 */
    private static Session stripToolTurns(Session s) {
        List<Message> kept = new ArrayList<>();
        for (Message m : s.getMessages()) {
            if (m.role() == Role.TOOL || !m.toolCalls().isEmpty()) {
                continue;
            }
            kept.add(m);
        }
        return new Session(s.getId(), s.getLastActive(), kept, s.getSettings());
    }

    /**
     * 加载最后活跃时间最新的会话。
     * 损坏的文件跳过并标记（调用方提示一行，见 checklist §C）；目录不存在视为无会话。
     */
    public LoadResult loadLatest() {
        if (!Files.isDirectory(dir)) {
            return new LoadResult(null, false);
        }
        Session latest = null;
        boolean hadCorrupt = false;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                Session session;
                try {
                    session = JSON.readValue(file.toFile(), Session.class);
                } catch (IOException | RuntimeException e) {
                    hadCorrupt = true;
                    continue;
                }
                if (session.getId() == null || session.getMessages() == null) {
                    hadCorrupt = true;
                    continue;
                }
                if (latest == null || session.getLastActive() > latest.getLastActive()) {
                    latest = session;
                }
            }
        } catch (IOException e) {
            return new LoadResult(null, true);
        }
        return new LoadResult(latest, hadCorrupt);
    }

    /** 会话标识：时间戳 + 随机后缀，保证 /new 时不覆盖旧文件。 */
    public static String newSessionId() {
        String stamp = LocalDateTime.now().format(ID_STAMP);
        String suffix = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF));
        return "s-" + stamp + "-" + suffix;
    }

    public Path dir() {
        return dir;
    }

    /** @param session 恢复的会话，可能为 null；@param hadCorrupt 是否有损坏文件被跳过 */
    public record LoadResult(Session session, boolean hadCorrupt) {
    }
}
