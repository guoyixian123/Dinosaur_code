package dinocode.session.archive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dinocode.compact.state.SessionContext;
import dinocode.core.Message;
import dinocode.core.Role;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 会话存档的读取侧（ch09 F17~F26）：列表扫描、JSONL 恢复加载、过期清理。
 */
public final class SessionArchive {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TITLE_MAX_CHARS = 50;

    private SessionArchive() {
    }

    /** 会话列表摘要项（F20）。 */
    public record SessionInfo(
            String id,
            String title,
            Instant modifiedAt,
            String model,
            long size,
            Path dir) {
    }

    // ---------- 列表扫描（F18/F20/AC12） ----------

    /** 扫描 sessionsDir，按最后修改时间倒序；旧格式 ID 与无 JSONL 的目录跳过（N3）。 */
    public static List<SessionInfo> list(Path sessionsDir) {
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        List<SessionInfo> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(sessionsDir)) {
            for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                SessionInfo info = describe(dir);
                if (info != null) {
                    out.add(info);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        out.sort(Comparator.comparing(SessionInfo::modifiedAt).reversed());
        return out;
    }

    private static SessionInfo describe(Path dir) {
        String id = dir.getFileName().toString();
        if (!SessionContext.isParsable(id)) {
            return null; // 旧格式不展示（N3/AC20）
        }
        Path jsonl = dir.resolve("conversation.jsonl");
        if (!Files.isRegularFile(jsonl)) {
            return null;
        }
        try {
            String title = "（空会话）";
            String model = "";
            // 读首条 user 消息作为标题；顺带取 model 标签
            try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Entry e = parseLine(line);
                    if (e == null) {
                        continue;
                    }
                    if (model.isEmpty() && e.model() != null) {
                        model = e.model();
                    }
                    if ("user".equals(e.role()) && e.content() != null && !e.content().isBlank()) {
                        title = truncate(e.content());
                        break;
                    }
                }
            }
            return new SessionInfo(id, title,
                    Files.getLastModifiedTime(jsonl).toInstant(),
                    model, Files.size(jsonl), dir);
        } catch (IOException e) {
            return null;
        }
    }

    private static String truncate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= TITLE_MAX_CHARS ? oneLine : oneLine.substring(0, TITLE_MAX_CHARS) + "…";
    }

    // ---------- 恢复加载（F21/AC14~AC17） ----------

    /** 从 JSONL 恢复消息列表：从最后一个 compact 标记之后加载、跳过坏行、截断孤立工具调用。 */
    public static List<Message> load(Path sessionDir) throws IOException {
        Path jsonl = sessionDir.resolve("conversation.jsonl");
        List<Message> all = new ArrayList<>();
        int lastCompactIdx = -1;
        int lineNo = 0;
        try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                Entry e = parseLine(line);
                if (e == null) {
                    continue; // 坏行静默跳过（F21/AC14）
                }
                if (e.isCompact()) {
                    lastCompactIdx = all.size(); // 从此标记之后开始（F12）
                    continue;
                }
                Message m = toMessage(e);
                if (m != null) {
                    all.add(m);
                }
            }
        }
        List<Message> fromCompact = lastCompactIdx >= 0 ? new ArrayList<>(all.subList(lastCompactIdx, all.size())) : all;
        return truncateOrphanedToolCall(fromCompact);
    }

    private static Message toMessage(Entry e) {
        if (e.role() == null) {
            return null;
        }
        return switch (e.role()) {
            case "user" -> Message.user(e.content() == null ? "" : e.content());
            case "assistant" -> Message.assistantWithTools(
                    e.content() == null ? "" : e.content(), e.toolCalls());
            case "tool" -> Message.tool(e.toolResults());
            default -> null;
        };
    }

    private static Entry parseLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(line, Entry.class);
        } catch (JsonProcessingException e) {
            return null; // 坏行
        }
    }

    /**
     * 孤立工具调用截断（F21/AC15）：最后一条 assistant 带 tool_calls 但其后没有 tool 消息
     * → 从该 assistant 起截掉。
     */
    public static List<Message> truncateOrphanedToolCall(List<Message> msgs) {
        if (msgs.isEmpty()) {
            return msgs;
        }
        int last = msgs.size() - 1;
        Message tail = msgs.get(last);
        if (tail.role() == Role.ASSISTANT && !tail.toolCalls().isEmpty()) {
            return new ArrayList<>(msgs.subList(0, last));
        }
        return msgs;
    }

    /** 会话最后一条消息的 ts 距当前是否超过给定跨度（F21-5/AC17）。 */
    public static Instant lastModifiedOf(Path sessionDir) {
        try {
            return Files.getLastModifiedTime(sessionDir.resolve("conversation.jsonl")).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    /** 相对时间文案（F20 列表展示）。 */
    public static String relativeTime(Instant then) {
        Duration d = Duration.between(then, Instant.now());
        if (d.isNegative()) {
            d = Duration.ZERO;
        }
        long minutes = d.toMinutes();
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = d.toHours();
        if (hours < 24) {
            return hours + " 小时前";
        }
        return d.toDays() + " 天前";
    }

    // ---------- 过期清理（F25/F26/AC19/AC20） ----------

    /** 删除超过 maxAge 的会话目录；旧格式 ID 跳过（N3/AC20）；单目录失败不影响其他。 */
    public static void cleanExpired(Path sessionsDir, Duration maxAge) {
        if (!Files.isDirectory(sessionsDir)) {
            return;
        }
        try (Stream<Path> dirs = Files.list(sessionsDir)) {
            for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                String id = dir.getFileName().toString();
                if (!SessionContext.isParsable(id)) {
                    continue; // 旧格式不清理（避免误删 ch08 遗留）
                }
                try {
                    LocalDateTime start = SessionContext.parseSessionTime(id);
                    Instant startInstant = start.atZone(java.time.ZoneId.systemDefault()).toInstant();
                    if (Duration.between(startInstant, Instant.now()).compareTo(maxAge) > 0) {
                        deleteRecursively(dir);
                    }
                } catch (Exception e) {
                    System.err.println("[session] warn: 清理跳过 " + id + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[session] warn: 清理扫描失败: " + e.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }
}
