package dinocode.tool;

import dinocode.core.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 集中登记工具、按名查找、导出定义、按名执行（F1/F3/F5/F9）。
 */
public final class ToolRegistry {

    /** 单个工具执行的默认超时（N1，不可配）。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** ch13：Agent 工具专用超时——子 Agent 需多轮 LLM 请求，30s 会腰斩正常任务。 */
    public static final Duration AGENT_TOOL_TIMEOUT = Duration.ofMinutes(10);

    private final List<String> order = new ArrayList<>();
    private final Map<String, Tool> tools = new HashMap<>();

    public void register(Tool tool) {
        if (tools.containsKey(tool.name())) {
            throw new IllegalArgumentException("工具名重复: " + tool.name());
        }
        tools.put(tool.name(), tool);
        order.add(tool.name());
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 按注册顺序导出工具定义（F3/AC1）。 */
    public List<ToolDefinition> definitions() {
        List<ToolDefinition> defs = new ArrayList<>();
        for (String name : order) {
            Tool tool = tools.get(name);
            defs.add(new ToolDefinition(tool.name(), tool.description(), tool.schema()));
        }
        return defs;
    }

    /** 已注册工具数量（ch10 /status 数据源）。 */
    public int count() {
        return tools.size();
    }

    /** 按注册顺序枚举全部工具实例（ch13 子 Agent 过滤复制用，N7）。 */
    public List<Tool> toolsAll() {
        List<Tool> out = new ArrayList<>();
        for (String name : order) {
            out.add(tools.get(name));
        }
        return out;
    }

    /** 仅导出只读工具定义（ch04 Plan Mode，F10）。 */
    public List<ToolDefinition> readOnlyDefinitions() {
        List<ToolDefinition> defs = new ArrayList<>();
        for (String name : order) {
            Tool tool = tools.get(name);
            if (tool.readOnly()) {
                defs.add(new ToolDefinition(tool.name(), tool.description(), tool.schema()));
            }
        }
        return defs;
    }

    /** 分批判定；未知工具返回 false（按有副作用串行处理）。 */
    public boolean isReadOnly(String name) {
        Tool tool = tools.get(name);
        return tool != null && tool.readOnly();
    }

    /** 按名执行工具；未知工具兜底为 error；args 为 null 时按空参数处理。 */
    public Result execute(String name, Map<String, Object> args) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return Result.error("未知工具: " + name);
        }
        return tool.execute(args == null ? Map.of() : args);
    }

    /** 注册 6 个核心工具。 */
    public static ToolRegistry createDefault() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        registry.register(new BashTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());
        return registry;
    }
}
