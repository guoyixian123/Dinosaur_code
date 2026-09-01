package dinocode.teams;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 团队共享任务表（ch15 F21/T12）：JSON 持久化 <teamDir>/tasks.json。
 * update 的 addBlocks/addBlockedBy 为追加语义（不替换）。
 */
public final class SharedTaskStore {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(
            SerializationFeature.INDENT_OUTPUT);

    /** 共享任务记录（F21）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SharedTask(
            int id,
            String title,
            String description,
            String status,
            String assignee,
            List<Integer> blocks,
            List<Integer> blockedBy,
            String createdBy) {

        /** 不可变更新（wither 模式）。 */
        public SharedTask withStatus(String newStatus) {
            return new SharedTask(id, title, description, newStatus, assignee, blocks, blockedBy, createdBy);
        }

        public SharedTask withAssignee(String newAssignee) {
            return new SharedTask(id, title, description, status, newAssignee, blocks, blockedBy, createdBy);
        }
    }

    private final Path filePath;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final List<SharedTask> tasks = new ArrayList<>();

    /** 构造时自动 load 已有 tasks.json。 */
    public SharedTaskStore(Path teamDir) {
        this.filePath = teamDir.resolve("tasks.json");
        load();
    }

    /** 创建任务（id 自增）。 */
    public synchronized SharedTask create(String title, String description, String createdBy) {
        SharedTask task = new SharedTask(nextId.getAndIncrement(), title, description,
                "pending", "", List.of(), List.of(), createdBy);
        tasks.add(task);
        save();
        return task;
    }

    public synchronized SharedTask get(int id) {
        return tasks.stream().filter(t -> t.id() == id).findFirst().orElse(null);
    }

    /** 按状态/assignee 过滤（null = 不过滤）。 */
    public synchronized List<SharedTask> listTasks(String status, String assignee) {
        return tasks.stream()
                .filter(t -> status == null || status.isBlank() || t.status().equals(status))
                .filter(t -> assignee == null || assignee.isBlank() || t.assignee().equals(assignee))
                .collect(Collectors.toList());
    }

    /**
     * 更新任务：status/assignee 覆盖；addBlocks/addBlockedBy 追加（不替换，F21）。
     */
    public synchronized SharedTask update(int id, String status, String assignee,
                                          List<Integer> addBlocks, List<Integer> addBlockedBy) {
        for (int i = 0; i < tasks.size(); i++) {
            SharedTask t = tasks.get(i);
            if (t.id() != id) {
                continue;
            }
            SharedTask updated = t;
            if (status != null && !status.isBlank()) {
                updated = updated.withStatus(status);
            }
            if (assignee != null && !assignee.isBlank()) {
                updated = updated.withAssignee(assignee);
            }
            if (addBlocks != null && !addBlocks.isEmpty()) {
                List<Integer> merged = new ArrayList<>(updated.blocks());
                merged.addAll(addBlocks);
                updated = new SharedTask(updated.id(), updated.title(), updated.description(),
                        updated.status(), updated.assignee(), merged, updated.blockedBy(), updated.createdBy());
            }
            if (addBlockedBy != null && !addBlockedBy.isEmpty()) {
                List<Integer> merged = new ArrayList<>(updated.blockedBy());
                merged.addAll(addBlockedBy);
                updated = new SharedTask(updated.id(), updated.title(), updated.description(),
                        updated.status(), updated.assignee(), updated.blocks(), merged, updated.createdBy());
            }
            tasks.set(i, updated);
            save();
            return updated;
        }
        return null;
    }

    private void load() {
        if (!Files.isRegularFile(filePath)) {
            return;
        }
        try {
            List<SharedTask> loaded = MAPPER.readValue(filePath.toFile(),
                    new TypeReference<List<SharedTask>>() {
                    });
            tasks.addAll(loaded);
            int max = tasks.stream().mapToInt(SharedTask::id).max().orElse(0);
            nextId.set(max + 1);
        } catch (IOException ignored) {
            // 损坏降级为空
        }
    }

    private void save() {
        try {
            Files.createDirectories(filePath.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), tasks);
        } catch (IOException ignored) {
            // 持久化失败不影响内存状态
        }
    }
}
