package dinocode.compact.state;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;

/**
 * 会话生命周期信息（ch08 F34/F35 + ch09 F9/F10）：sessionId 进程启动时一次性生成、不持久化。
 * ch09：ID 格式改为 {@code YYYYMMDD-HHMMSS-xxxx}（人类可读、可解析时间戳）；
 * sessionDir 下含 conversation.jsonl（工作记忆）与 tool-results/（ch08 落盘）。
 */
public final class SessionContext {

    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final String sessionId;
    private final Path sessionDir;
    private final Path spillDir;

    private SessionContext(String sessionId, Path sessionDir, Path spillDir) {
        this.sessionId = sessionId;
        this.sessionDir = sessionDir;
        this.spillDir = spillDir;
    }

    /** 生成新 sessionId 并创建目录结构。 */
    public static SessionContext create(Path workspace) {
        String sessionId = newSessionId();
        Path sessionDir = workspace.toAbsolutePath()
                .resolve(".dino").resolve("sessions").resolve(sessionId);
        Path spillDir = sessionDir.resolve("tool-results");
        try {
            Files.createDirectories(spillDir);
        } catch (IOException e) {
            // 目录创建失败：落盘不可用，但会话仍可运行（N6 错误隔离）
            throw new UncheckedIOException(e);
        }
        return new SessionContext(sessionId, sessionDir, spillDir);
    }

    /** 打开既有会话（ch09 /resume 恢复场景）：不创建目录，目录必须存在。 */
    public static SessionContext open(Path workspace, String sessionId) throws IOException {
        Path sessionDir = workspace.toAbsolutePath()
                .resolve(".dino").resolve("sessions").resolve(sessionId);
        if (!Files.isDirectory(sessionDir)) {
            throw new IOException("会话目录不存在: " + sessionDir);
        }
        return new SessionContext(sessionId, sessionDir, sessionDir.resolve("tool-results"));
    }

    /** 新格式 ID：{@code YYYYMMDD-HHMMSS-4hex}（F9）。 */
    static String newSessionId() {
        byte[] bytes = new byte[2];
        new SecureRandom().nextBytes(bytes);
        return ID_FORMAT.format(LocalDateTime.now()) + "-" + HexFormat.of().formatHex(bytes);
    }

    /** 测试可见的 ID 生成（跨包断言格式用）。 */
    public static String newSessionIdForTest() {
        return newSessionId();
    }

    /**
     * 从 ID 前 15 位解析启动时刻；旧格式（ch08 遗留）抛 {@link DateTimeParseException}，
     * 调用方据此跳过（不做清理、不在 /resume 列表展示，N3）。
     */
    public static LocalDateTime parseSessionTime(String sessionId) {
        return LocalDateTime.parse(sessionId.substring(0, 15), ID_FORMAT);
    }

    /** 该 ID 是否为新格式（能解析出时间戳）。 */
    public static boolean isParsable(String sessionId) {
        try {
            parseSessionTime(sessionId);
            return true;
        } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
            return false;
        }
    }

    /** 该会话的启动时间。 */
    public Instant startTime() {
        return parseSessionTime(sessionId).atZone(ZoneId.systemDefault()).toInstant();
    }

    public String sessionId() {
        return sessionId;
    }

    public Path sessionDir() {
        return sessionDir;
    }

    public Path spillDir() {
        return spillDir;
    }

    @Override
    public String toString() {
        return sessionId;
    }
}
