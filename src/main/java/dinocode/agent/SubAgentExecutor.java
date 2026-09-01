package dinocode.agent;

import dinocode.core.Message;
import dinocode.permission.PermissionEngine;
import dinocode.provider.ChatProvider;
import dinocode.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * 子 Agent 执行器（ch13 N5/T9）：构造独立 Agent 实例，阻塞消费事件流直到结束。
 * 由 AgentTool 的 delegate 调用（sync 前台 / async 后台虚拟线程）。
 */
public final class SubAgentExecutor {

    private final ChatProvider provider;
    private final int maxTokens;
    private final PermissionEngine engine;
    private final String instructionText;
    private final String memoryText;
    private final dinocode.hook.HookEngine hookEngine;

    public SubAgentExecutor(ChatProvider provider, int maxTokens,
                            PermissionEngine engine, String instructionText, String memoryText,
                            dinocode.hook.HookEngine hookEngine) {
        this.provider = provider;
        this.maxTokens = maxTokens;
        this.engine = engine;
        this.instructionText = instructionText;
        this.memoryText = memoryText;
        this.hookEngine = hookEngine;
    }

    /**
     * 阻塞执行子 Agent。
     *
     * @param spec     子 Agent 定义（systemPromptOverride 优先于全局指令）
     * @param prompt   任务文本；fork 场景为 null（任务已在 history 末尾）
     * @param filtered 已过滤的工具集
     * @param history  初始对话（fork 场景为完整父对话拷贝；普通场景为空）
     */
    public String run(dinocode.subagent.SubAgentSpec spec, String prompt, ToolRegistry filtered,
                      List<Message> history) throws Exception {
        List<Message> messages = new ArrayList<>(history);
        if (prompt != null && !prompt.isBlank()) {
            messages.add(Message.user(prompt));
        }
        boolean readOnly = spec.disallowedTools().contains("EditFile")
                && spec.disallowedTools().contains("WriteFile");
        boolean hasOverride = spec.systemPromptOverride() != null && !spec.systemPromptOverride().isBlank();

        // 有 override 时把 override 传给 instructionText 槽（Agent.runWithSystemOverride 直接用字段做覆盖）
        Agent sub = new Agent(provider, filtered, "0.1.0", engine, null, null,
                hasOverride ? spec.systemPromptOverride() : instructionText, memoryText, hookEngine);
        // fork 场景不叠加环境段（历史已含上下文）
        String envText = history.isEmpty()
                ? dinocode.prompt.Environment.gather("", provider.model()).render()
                : "";

        CancelToken cancel = new CancelToken();
        try (TurnStream stream = sub.runWithSystemOverride(messages, maxTokens, readOnly, envText, cancel)) {
            StringBuilder output = new StringBuilder();
            long deadline = System.currentTimeMillis() + 600_000; // 10 分钟硬上限
            TurnEvent event;
            while ((event = stream.next()) != null) {
                switch (event) {
                    case TurnEvent.Text t -> output.append(t.delta());
                    case TurnEvent.Error err -> throw new IllegalStateException(err.message());
                    default -> {
                    }
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("子 Agent 执行超时");
                }
            }
            return output.length() > 0 ? output.toString() : "(子 Agent 未返回内容)";
        }
    }
}
