package dinocode.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 一条对话消息。thinking 内容不进消息，只渲染到终端（见 spec 设计骨架）。
 * 工具调用（assistant 回合）与工具结果（TOOL 回合）见 ch03 工具系统；二者不落盘。
 */
public record Message(
        Role role,
        String content,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ToolCall> toolCalls,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ToolResult> toolResults) {

    public Message {
        if (role == null) {
            throw new IllegalArgumentException("role 不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        toolCalls = toolCalls == null ? List.of() : toolCalls;
        toolResults = toolResults == null ? List.of() : toolResults;
    }

    /** 用户消息。 */
    public static Message user(String text) {
        return new Message(Role.USER, text, List.of(), List.of());
    }

    /** 助手消息（纯文本）。 */
    public static Message assistant(String text) {
        return new Message(Role.ASSISTANT, text, List.of(), List.of());
    }

    /** 助手消息（带工具调用）。 */
    public static Message assistantWithTools(String text, List<ToolCall> calls) {
        return new Message(Role.ASSISTANT, text, calls, List.of());
    }

    /** 工具结果消息（TOOL 回合）。 */
    public static Message tool(List<ToolResult> results) {
        return new Message(Role.TOOL, "", List.of(), results);
    }

    /** 旧会话 JSON 只有 role/content；工具字段缺省为空。 */
    @JsonCreator
    public static Message fromJson(
            @JsonProperty("role") Role role,
            @JsonProperty("content") String content,
            @JsonProperty("toolCalls") List<ToolCall> toolCalls,
            @JsonProperty("toolResults") List<ToolResult> toolResults) {
        return new Message(role, content, toolCalls, toolResults);
    }
}
