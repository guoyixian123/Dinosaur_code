package dinocode.compact;

import dinocode.core.Message;
import dinocode.core.Role;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 摘要 Prompt 模板与解析（ch08 F9/F10）：两阶段输出（&lt;analysis&gt; 草稿丢弃、&lt;summary&gt; 保留）、
 * 9 部分固定结构。纯模板 + 字符串解析，无外部状态。
 */
public final class SummaryPrompt {

    private static final String SUMMARY_INSTRUCTION = """
            你在总结一个编码 Agent 的对话历史。分两个阶段输出：

            <analysis>
            （在这里写分析草稿，会被丢弃）
            </analysis>

            <summary>
            ## 1 主要请求和意图
            ## 2 关键技术概念
            ## 3 文件和代码段
            ## 4 错误和修复
            ## 5 问题解决过程
            ## 6 所有用户消息原文（按时间顺序逐条保留）
            ## 7 待办任务
            ## 8 当前工作（最详细：正在做什么、停在哪一步）
            ## 9 可能的下一步
            </summary>

            要求：不要调用任何工具，输出纯文本。第 6 节必须尽量逐条保留用户消息原文。""";

    private SummaryPrompt() {
    }

    /** 构造摘要请求消息：单条 user，无工具、无系统提示（F8）。 */
    public static List<Message> buildSummaryPrompt(List<Message> msgs) {
        return List.of(Message.user(SUMMARY_INSTRUCTION + "\n\n[conversation]\n" + serializeConversation(msgs)));
    }

    /** 把对话扁平化为可读文本（确定性输出，便于测试与缓存稳定）。 */
    static String serializeConversation(List<Message> msgs) {
        StringBuilder sb = new StringBuilder();
        for (Message m : msgs) {
            switch (m.role()) {
                case USER -> sb.append("user: ").append(m.content()).append('\n');
                case ASSISTANT -> {
                    sb.append("assistant: ").append(m.content()).append('\n');
                    for (dinocode.core.ToolCall c : m.toolCalls()) {
                        sb.append("[call ").append(c.name()).append(" id=").append(c.id())
                                .append(" args=").append(c.arguments()).append("]\n");
                    }
                }
                case TOOL -> {
                    for (dinocode.core.ToolResult r : m.toolResults()) {
                        sb.append("[result id=").append(r.toolCallId())
                                .append(" isError=").append(r.isError()).append("] ")
                                .append(r.content()).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 抠出最后一对 &lt;summary&gt;...&lt;/summary&gt; 之间的正文；找不到返回原文（降级不硬失败）。 */
    public static String extractSummary(String raw) {
        if (raw == null) {
            return "";
        }
        int start = raw.lastIndexOf("<summary>");
        int end = raw.lastIndexOf("</summary>");
        if (start >= 0 && end > start) {
            return raw.substring(start + "<summary>".length(), end).strip();
        }
        return raw.strip();
    }

    /** 供测试与调试：模板是否含 9 个小节标题。 */
    static boolean hasNineSections(String text) {
        for (int i = 1; i <= 9; i++) {
            if (!text.contains("## " + i)) {
                return false;
            }
        }
        return true;
    }

    /** 字节口径 helper（供 Layer1 共用）。 */
    static int utf8Length(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** role 展示名。 */
    static String roleName(Role role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }
}
