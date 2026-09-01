package dinocode.subagent;

import dinocode.tool.Tool;
import dinocode.tool.ToolRegistry;

import java.util.List;
import java.util.Set;

/**
 * 子 Agent 工具过滤（ch13 F6/T4）：六层顺序——
 * MCP 豁免 → 全局禁（防递归 N1）→ custom 额外禁 → async 白名单 → spec 黑名单 → spec 白名单交集。
 */
public final class ToolFilter {

    /** 全局禁用（任意层级子 Agent 都不可用，防无限递归/上下文爆炸，N1）。 */
    public static final Set<String> ALWAYS_DISALLOWED = Set.of(
            "Agent", "TaskOutput", "TaskStop",
            "EnterPlanMode", "ExitPlanMode", "AskUserQuestion", "Workflow");

    /** 自定义（Markdown 定义）子 Agent 额外禁用。 */
    public static final Set<String> CUSTOM_AGENT_DISALLOWED = Set.of(
            "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
            "SendMessage", "TeamCreate", "CronDelete");

    /** 异步子 Agent 的白名单（仅基础工具）。 */
    public static final Set<String> ASYNC_ALLOWED = Set.of(
            "ReadFile", "WriteFile", "EditFile", "Bash", "Glob", "Grep",
            "InstallSkill", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
            "WebFetch", "WebSearch", "TodoWrite", "NotebookEdit");

    /** in-process teammate 在 async 白名单层额外放行（F10）。 */
    public static final Set<String> IN_PROCESS_TEAMMATE_ALLOWED = Set.of(
            "Agent", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
            "SendMessage", "CronCreate", "CronDelete", "CronList");

    private ToolFilter() {
    }

    /** 同步路径过滤（无 async 语义）。 */
    public static ToolRegistry filterForAgent(ToolRegistry parent, SubAgentSpec spec) {
        return filterForAgent(parent, spec, false, false, false);
    }

    /**
     * 六层过滤（F6）：返回一个仅含放行工具的新 registry（不污染父 registry，N7）。
     *
     * @param isAsync            后台异步模式（白名单收紧）
     * @param isCustom           自定义 Markdown 定义（额外禁用集）
     * @param isInProcessTeammate in-process teammate（async 层额外放行）
     */
    public static ToolRegistry filterForAgent(ToolRegistry parent, SubAgentSpec spec,
                                              boolean isAsync, boolean isCustom, boolean isInProcessTeammate) {
        ToolRegistry filtered = new ToolRegistry();
        List<String> whitelist = spec.tools();
        boolean hasWhitelist = !whitelist.isEmpty() && !(whitelist.size() == 1 && "*".equals(whitelist.get(0)));

        for (Tool tool : parent.toolsAll()) {
            String name = tool.name();
            // ① MCP 豁免
            if (isMcpTool(name)) {
                filtered.register(tool);
                continue;
            }
            // ② 全局禁（N1 防递归）
            if (ALWAYS_DISALLOWED.contains(name)) {
                continue;
            }
            // ③ custom agent 额外禁
            if (isCustom && CUSTOM_AGENT_DISALLOWED.contains(name)) {
                continue;
            }
            // ④ async 白名单（in-process teammate 额外放行）
            if (isAsync && !ASYNC_ALLOWED.contains(name)) {
                if (!(isInProcessTeammate && (name.equals("Agent") || IN_PROCESS_TEAMMATE_ALLOWED.contains(name)))) {
                    continue;
                }
            }
            // ⑤ spec 级黑名单
            if (spec.disallowedTools().contains(name)) {
                continue;
            }
            // ⑥ spec 级白名单交集
            if (hasWhitelist && !whitelist.contains(name)) {
                continue;
            }
            filtered.register(tool);
        }
        return filtered;
    }

    /** MCP 工具识别（ch07 命名空间前缀）。 */
    static boolean isMcpTool(String name) {
        return name != null && name.startsWith("mcp__");
    }
}
