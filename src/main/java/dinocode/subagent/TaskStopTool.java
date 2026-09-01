package dinocode.subagent;

import dinocode.tool.Result;
import dinocode.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TaskStop 工具（ch13 F11 补全）：停止后台子 Agent 任务。
 * 之前只在 ToolFilter.ALWAYS_DISALLOWED 里被引用、从未注册——模型与用户都无从取消后台任务。
 */
public final class TaskStopTool implements Tool {

    private final SubAgentTaskManager taskManager;

    public TaskStopTool(SubAgentTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public String name() {
        return "TaskStop";
    }

    @Override
    public String description() {
        return "停止一个后台运行中的子 Agent 任务（按 task_id，如 task_3）。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("task_id", Map.of("type", "string", "description", "要停止的任务 ID"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("task_id"));
        return schema;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(Map<String, Object> args) {
        Object id = args.get("task_id");
        if (id == null || String.valueOf(id).isBlank()) {
            return Result.error("Error: task_id is required");
        }
        boolean stopped = taskManager.cancelTask(String.valueOf(id));
        if (!stopped) {
            return Result.error("Error: task '" + id + "' not found");
        }
        return Result.ok("Task " + id + " stopped.");
    }
}
