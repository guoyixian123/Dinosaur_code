package dinocode.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dinocode.core.ChatEvent;
import dinocode.core.ErrorKind;
import dinocode.core.Message;
import dinocode.core.Role;
import dinocode.provider.ChatProvider;
import dinocode.provider.ChatRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * 自动笔记（ch09 F27~F42 / 第 4 层记忆）：
 * 四类笔记、两级目录（项目级/用户级）、MEMORY.md 索引、异步 LLM 更新（失败静默，F42）。
 */
public final class Memory {

    private static final Logger LOG = Logger.getLogger(Memory.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int INDEX_MAX_BYTES = 25 * 1024;
    private static final int UPDATE_TIMEOUT_SECONDS = 120;

    private Memory() {
    }

    /** 笔记四类（F27）。 */
    public enum NoteType {
        USER_PREFERENCE("user_preference"),
        CORRECTION_FEEDBACK("correction_feedback"),
        PROJECT_KNOWLEDGE("project_knowledge"),
        REFERENCE_MATERIAL("reference_material");

        private final String wire;

        NoteType(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** LLM 返回的单条操作（F39）。 */
    public record UpdateAction(
            String action,   // create / update / delete
            String level,    // project / user
            String type,     // 笔记类型 wire（create）
            String title,
            String slug,     // 文件名 slug（create）
            String content,  // 正文（create/update）
            String filename) { // 已有文件名（update/delete）
    }

    // ==================== Store：单级笔记文件与索引（F28~F31/F40） ====================

    /** 管理一级（项目级或用户级）的笔记文件和 MEMORY.md 索引。 */
    public static final class Store {
        private final Path dir;
        private final ReentrantLock lock = new ReentrantLock();

        public Store(Path dir) {
            this.dir = dir;
        }

        public Path dir() {
            return dir;
        }

        void ensureDir() throws IOException {
            Files.createDirectories(dir);
        }

        /** 读 MEMORY.md 索引；缺失返回空串（F30）。 */
        public String loadIndex() {
            Path index = dir.resolve("MEMORY.md");
            if (!Files.isRegularFile(index)) {
                return "";
            }
            try {
                return Files.readString(index, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }

        /** 执行一批操作（F40）；文件写与索引更新在同一锁内（N2）。 */
        public void apply(String level, List<UpdateAction> actions) {
            lock.lock();
            try {
                ensureDir();
                for (UpdateAction a : actions) {
                    if (!level.equals(a.level())) {
                        continue; // 该操作归属另一级
                    }
                    try {
                        switch (a.action()) {
                            case "create" -> createNote(a);
                            case "update" -> updateNote(a);
                            case "delete" -> deleteNote(a);
                            default -> LOG.warning("[memory] 未知操作: " + a.action());
                        }
                    } catch (IOException e) {
                        LOG.warning("[memory] 操作失败 " + a.action() + ": " + e.getMessage()); // F42 静默
                    }
                }
            } catch (IOException e) {
                LOG.warning("[memory] 目录创建失败: " + e.getMessage());
            } finally {
                lock.unlock();
            }
        }

        private void createNote(UpdateAction a) throws IOException {
            if (a.slug() == null || a.slug().isBlank() || a.type() == null) {
                return;
            }
            String filename = a.type() + "_" + sanitizeSlug(a.slug()) + ".md";
            Path file = dir.resolve(filename);
            if (Files.exists(file)) {
                return; // 同名已存在：交给 LLM 的 update 去处理（F41 去重）
            }
            OffsetDateTime now = OffsetDateTime.now();
            String body = "---\ntype: " + a.type() + "\ntitle: " + safe(a.title())
                    + "\ncreated: " + now + "\nupdated: " + now + "\n---\n"
                    + (a.content() == null ? "" : a.content());
            Files.writeString(file, body, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            appendIndex("- [" + a.type() + "] " + safe(a.title()) + " — " + filename);
        }

        private void updateNote(UpdateAction a) throws IOException {
            if (a.filename() == null || a.filename().isBlank()) {
                return;
            }
            Path file = dir.resolve(a.filename());
            if (!Files.isRegularFile(file)) {
                return;
            }
            String old = Files.readString(file, StandardCharsets.UTF_8);
            String created = extractFrontmatter(old, "created", OffsetDateTime.now().toString());
            String type = extractFrontmatter(old, "type", "user_preference");
            String body = "---\ntype: " + type + "\ntitle: " + safe(a.title())
                    + "\ncreated: " + created + "\nupdated: " + OffsetDateTime.now() + "\n---\n"
                    + (a.content() == null ? "" : a.content());
            Files.writeString(file, body, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            // 索引行按 filename 匹配替换
            replaceIndexLine(a.filename(), "- [" + type + "] " + safe(a.title()) + " — " + a.filename());
        }

        private void deleteNote(UpdateAction a) throws IOException {
            if (a.filename() == null || a.filename().isBlank()) {
                return;
            }
            Files.deleteIfExists(dir.resolve(a.filename()));
            removeIndexLine(a.filename());
        }

        private void appendIndex(String line) throws IOException {
            Path index = dir.resolve("MEMORY.md");
            Files.writeString(index, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        private void replaceIndexLine(String filename, String newLine) throws IOException {
            List<String> lines = readIndexLines();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(filename)) {
                    lines.set(i, newLine);
                }
            }
            Files.write(dir.resolve("MEMORY.md"), lines, StandardCharsets.UTF_8);
        }

        private void removeIndexLine(String filename) throws IOException {
            List<String> lines = readIndexLines();
            lines.removeIf(l -> l.contains(filename));
            Files.write(dir.resolve("MEMORY.md"), lines, StandardCharsets.UTF_8);
        }

        private List<String> readIndexLines() throws IOException {
            Path index = dir.resolve("MEMORY.md");
            return Files.isRegularFile(index)
                    ? new ArrayList<>(Files.readAllLines(index, StandardCharsets.UTF_8))
                    : new ArrayList<>();
        }

        private static String safe(String s) {
            return s == null ? "" : s.replace("\n", " ").strip();
        }

        private static String sanitizeSlug(String slug) {
            String s = slug.toLowerCase().replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_");
            return s.isBlank() ? "note" : s;
        }

        private static String extractFrontmatter(String text, String key, String fallback) {
            for (String line : text.split("\n")) {
                if (line.startsWith(key + ":")) {
                    return line.substring(key.length() + 1).strip();
                }
                if (!line.isBlank() && !line.startsWith("---") && line.contains(":")) {
                    break; // 出 frontmatter
                }
            }
            return fallback;
        }
    }

    // ==================== Manager：两级编排 + 异步 LLM 更新（F32~F42） ====================

    /** 编排项目级与用户级笔记：索引注入（F32~F34）+ 异步更新（F35~F42）。 */
    public static final class Manager {
        private final Store projectStore;
        private final Store userStore;
        private final ReentrantLock updateLock = new ReentrantLock();
        private volatile ChatProvider provider;

        public Manager(Path projectDir, Path userDir) {
            this.projectStore = new Store(projectDir);
            this.userStore = new Store(userDir);
        }

        /** 延迟设置 provider（Main 在 provider 构造后调用）。 */
        public void setProvider(ChatProvider provider) {
            this.provider = provider;
        }

        /**
         * ch10 T0a：列出两级 memory 目录下的 .md 文件名（含 MEMORY.md），
         * 各自按文件名字典序排序；目录不存在视为空（/memory 命令数据源）。
         */
        public FilesList listFiles() {
            return new FilesList(listMdFiles(projectStore.dir()), listMdFiles(userStore.dir()));
        }

        private static List<String> listMdFiles(Path dir) {
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            try (var stream = Files.list(dir)) {
                return stream.map(f -> f.getFileName().toString())
                        .filter(n -> n.endsWith(".md"))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                LOG.warning("[memory] 列出文件失败: " + e.getMessage());
                return List.of();
            }
        }

        /** 两级记忆文件名清单（ch10 /memory 命令数据源）。 */
        public record FilesList(List<String> project, List<String> user) {
        }

        /** 合并两级索引（项目级在前），截断到 25KB（F32~F34/AC23/AC26）。 */
        public String loadIndex() {
            String merged = projectStore.loadIndex() + userStore.loadIndex();
            if (merged.isEmpty()) {
                return "";
            }
            byte[] bytes = merged.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > INDEX_MAX_BYTES) {
                return new String(bytes, 0, INDEX_MAX_BYTES, StandardCharsets.UTF_8) + "\n(index truncated)";
            }
            return merged;
        }

        /** 显式记忆请求关键词检测（F35-②）。 */
        public static boolean hasMemorySignal(String userText) {
            if (userText == null) {
                return false;
            }
            String lower = userText.toLowerCase();
            return lower.contains("记住") || lower.contains("记忆") || lower.contains("别忘")
                    || lower.contains("remember") || lower.contains("memo");
        }

        /** 异步记忆更新（F36 virtual thread；失败静默，F42/AC25）。 */
        public void updateAsync(List<Message> recentMsgs) {
            ChatProvider p = provider;
            if (p == null || recentMsgs == null || recentMsgs.isEmpty()) {
                return;
            }
            Thread.ofVirtual().start(() -> {
                if (!updateLock.tryLock()) {
                    return; // 已有更新在跑：跳过本轮（N2）
                }
                try {
                    update(p, recentMsgs);
                } catch (Exception e) {
                    LOG.warning("[memory] 更新失败: " + e.getMessage()); // F42 静默
                } finally {
                    updateLock.unlock();
                }
            });
        }

        private void update(ChatProvider p, List<Message> recentMsgs) throws Exception {
            String projectIndex = projectStore.loadIndex();
            String userIndex = userStore.loadIndex();
            String prompt = MEMORY_UPDATE_PROMPT
                    + "\n\n[项目级索引]\n" + (projectIndex.isBlank() ? "（空）" : projectIndex)
                    + "\n\n[用户级索引]\n" + (userIndex.isBlank() ? "（空）" : userIndex)
                    + "\n\n[本轮对话]\n" + serialize(recentMsgs);
            String reply = callLlm(p, prompt);
            reply = stripFence(reply);
            int start = reply.indexOf('[');
            int end = reply.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return; // 无 JSON 数组：无需更新
            }
            List<UpdateAction> actions = JSON.readValue(
                    reply.substring(start, end + 1), new TypeReference<List<UpdateAction>>() {
                    });
            if (actions.isEmpty()) {
                return;
            }
            projectStore.apply("project", actions);
            userStore.apply("user", actions);
        }

        private static String stripFence(String s) {
            String t = s.strip();
            if (t.startsWith("```")) {
                int firstNl = t.indexOf('\n');
                int lastFence = t.lastIndexOf("```");
                if (firstNl > 0 && lastFence > firstNl) {
                    return t.substring(firstNl + 1, lastFence);
                }
            }
            return t;
        }

        private static String serialize(List<Message> msgs) {
            StringBuilder sb = new StringBuilder();
            for (Message m : msgs) {
                sb.append(m.role() == Role.USER ? "user: " : "assistant: ")
                        .append(m.content()).append('\n');
            }
            return sb.toString();
        }

        /** 同步 LLM 调用（无工具，F38）；非 2xx/流错转 IOException。 */
        private static String callLlm(ChatProvider p, String prompt) throws IOException {
            ChatRequest req = new ChatRequest(List.of(Message.user(prompt)), 4096, List.of());
            StringBuilder text = new StringBuilder();
            try (var stream = p.chat(req)) {
                ChatEvent event;
                while ((event = stream.next()) != null) {
                    switch (event) {
                        case ChatEvent.TextDelta t -> text.append(t.text());
                        case ChatEvent.Failure f -> throw new IOException(
                                f.kind() == ErrorKind.CONTEXT_OVERFLOW ? "记忆更新请求超长" : f.message());
                        default -> {
                        }
                    }
                }
            }
            return text.toString();
        }
    }

    /** 记忆更新 prompt 模板（F39 结构化 JSON 输出，中文）。 */
    static final String MEMORY_UPDATE_PROMPT = """
            你是记忆管理助手。分析下面的对话片段，判断是否包含值得长期记住的信息（用户偏好、纠正反馈、\
            项目知识、参考资料）。已提供现有两级索引，由你判断去重：已有相似笔记时返回 update 或直接跳过。

            只输出一个 JSON 数组，不要输出其他内容。每个元素：
            {"action":"create","level":"project|user","type":"user_preference|correction_feedback|project_knowledge|reference_material","title":"...","slug":"小写_下划线","content":"笔记正文"}
            {"action":"update","level":"project|user","filename":"已有文件名.md","title":"...","content":"更新后的完整正文"}
            {"action":"delete","level":"project|user","filename":"要删除的文件名.md"}

            分级规则：跨项目通用（偏好、反馈）→ user 级；项目相关（知识、资料）→ project 级。
            无值得记忆的信息时输出 []。""";
}
