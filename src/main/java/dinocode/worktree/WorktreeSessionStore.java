package dinocode.worktree;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 会话级 worktree 持久化（ch14 F8/F9/T7）：volatile 单例 + JSON 文件。
 * save(repo, null) 等价删除持久化文件。
 */
public final class WorktreeSessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** N1：volatile 保证并发可见。 */
    private static volatile WorktreeSession currentSession;

    private WorktreeSessionStore() {
    }

    public static WorktreeSession getCurrentSession() {
        return currentSession;
    }

    /** 写回全局单例（也接受 null 清除——/clear、Exit 后调用）。 */
    public static void restoreSession(WorktreeSession session) {
        currentSession = session;
    }

    /** 持久化；session=null 时删除持久化文件（F8）。 */
    public static void save(Path repoRoot, WorktreeSession session) throws IOException {
        Path file = sessionPath(repoRoot);
        if (session == null) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        MAPPER.writeValue(file.toFile(), session);
    }

    /** 启动恢复（F9）：文件不存在或损坏返回 null。 */
    public static WorktreeSession load(Path repoRoot) {
        Path file = sessionPath(repoRoot);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readValue(file.toFile(), WorktreeSession.class);
        } catch (IOException e) {
            return null; // 损坏降级
        }
    }

    public static Path sessionPath(Path repoRoot) {
        return repoRoot.toAbsolutePath().resolve(".dino").resolve("worktree_session.json");
    }
}
