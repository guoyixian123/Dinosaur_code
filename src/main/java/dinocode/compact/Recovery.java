package dinocode.compact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 摘要后恢复段（ch08 F15/F16/F18）：最近读过的文件追踪 + 三段恢复内容构造。
 * recordFile 由 Agent 主循环在 ReadFile 成功后写（F19/F19a），runSummary 时读快照。
 */
public final class Recovery {

    /** 单个文件的读取记录；content 为不带行号前缀的纯净内容。 */
    public record FileReadRecord(String path, String content, Instant timestamp) {
    }

    /** 文件追踪状态（F20 并发安全）：Agent 写、compact 读，键为绝对路径。 */
    public static final class RecoveryState {
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<String, FileReadRecord> files = new HashMap<>();

        public void recordFile(String path, String content) {
            if (path == null || path.isBlank()) {
                return;
            }
            String abs = java.nio.file.Path.of(path).toAbsolutePath().normalize().toString();
            lock.lock();
            try {
                files.put(abs, new FileReadRecord(abs, content == null ? "" : content, Instant.now()));
            } finally {
                lock.unlock();
            }
        }

        /** 已按时间戳倒序的快照拷贝（不暴露内部 map）。 */
        public List<FileReadRecord> snapshot() {
            lock.lock();
            try {
                List<FileReadRecord> out = new ArrayList<>(files.values());
                out.sort((a, b) -> b.timestamp().compareTo(a.timestamp()));
                return List.copyOf(out);
            } finally {
                lock.unlock();
            }
        }
    }

    /** 边界提示固定文案（F18/AC11）。 */
    public static final String BOUNDARY_NOTICE = """
            摘要无法精确还原全部细节。需要文件原文、错误原文或用户原话时，\
            请使用文件读取工具重新读取对应路径，不要依据摘要内容做猜测。""";

    private Recovery() {
    }

    /**
     * 构造恢复三段（AC9/AC10/AC11）：最近文件快照（≤5 个、时间戳倒序、单文件 5000 token 截断）
     * + 当前可用工具列表（与 Stream 请求同一 defs 引用）+ 边界提示。
     */
    public static String buildRecoveryAttachment(List<FileReadRecord> snapshot,
                                                 List<dinocode.core.ToolDefinition> toolDefs) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 最近读过的文件\n");
        if (snapshot.isEmpty()) {
            sb.append("(无)\n");
        } else {
            int limit = Math.min(CompactConstants.RECOVERY_FILE_LIMIT, snapshot.size());
            for (int i = 0; i < limit; i++) {
                sb.append(renderFileBlock(snapshot.get(i)));
            }
        }
        sb.append("\n## 当前可用工具\n");
        for (dinocode.core.ToolDefinition def : toolDefs) {
            sb.append("- ").append(def.name()).append(": ")
                    .append(def.description() == null ? "" : def.description()).append('\n');
        }
        sb.append("\n## 边界提示\n").append(BOUNDARY_NOTICE).append('\n');
        return sb.toString();
    }

    /** 单文件渲染：路径 + 时间戳 + 内容片段（超 5000 token 保留头部并标注截断）。 */
    static String renderFileBlock(FileReadRecord rec) {
        int charLimit = (int) (CompactConstants.RECOVERY_TOKENS_PER_FILE
                * CompactConstants.ESTIMATE_CHARS_PER_TOKEN);
        String content = rec.content() == null ? "" : rec.content();
        boolean truncated = content.length() > charLimit;
        if (truncated) {
            content = content.substring(0, charLimit);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(rec.path()).append('\n');
        sb.append("[read at] ").append(rec.timestamp()).append('\n');
        sb.append(content).append('\n');
        if (truncated) {
            sb.append("(content truncated)\n");
        }
        return sb.toString();
    }
}
