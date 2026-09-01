package dinocode.subagent;

import java.util.List;

/**
 * 子 Agent 定义（ch13 F2）：名称、描述、工具约束与执行参数。
 * 三档内建：general-purpose / plan / explore（静态实例）。
 */
public record SubAgentSpec(
        String name,
        String description,
        List<String> tools,               // 白名单；["*"] 或空 = 不过滤
        List<String> disallowedTools,     // 黑名单（在白名单之上再排除）
        String systemPromptOverride,      // null = 用主 Agent 默认系统提示
        int maxTurns,
        String model) {                   // "" / inherit = 复用父模型；haiku/sonnet/opus 别名

    public SubAgentSpec {
        tools = tools == null ? List.of() : List.copyOf(tools);
        disallowedTools = disallowedTools == null ? List.of() : List.copyOf(disallowedTools);
    }

    /** plan 角色系统提示（只调研产计划，不做写操作）。 */
    public static final String PLAN_AGENT_SYSTEM_PROMPT = """
            你是一个规划专家子 Agent。你的唯一职责是调研代码库并产出一份分步执行计划。
            你只能使用只读工具（ReadFile、Glob、Grep）。
            你不得写文件、修改文件或执行命令。
            计划要具体到文件与步骤，写完即停。""";

    /** general-purpose：全工具、高轮数上限。 */
    public static final SubAgentSpec GENERAL_PURPOSE = new SubAgentSpec(
            "general-purpose",
            "通用子 Agent：研究复杂问题、搜索代码、执行多步任务。使用时请提供详细任务描述",
            List.of(), List.of(), null, 200, "");

    /** plan：只读调研、产出计划。 */
    public static final SubAgentSpec PLAN = new SubAgentSpec(
            "plan",
            "规划专家：调研代码库并产出分步执行计划，不做任何写操作",
            List.of(), List.of("EditFile", "WriteFile"), PLAN_AGENT_SYSTEM_PROMPT, 15, "");

    /** explore：快速只读搜索，用小模型。 */
    public static final SubAgentSpec EXPLORE = new SubAgentSpec(
            "explore",
            "探索专家：快速搜索代码库定位相关文件与代码片段",
            List.of(), List.of("EditFile", "WriteFile"), null, 30, "haiku");
}
