package dino.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    /** 存盘。目录不存在则创建（见 checklist §C）。失败抛 IOException，由调用方友好提示。 */
    public void save(Session session) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(session.getId() + ".json");
        Files.writeString(target, JSON.writeValueAsString(session));
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
