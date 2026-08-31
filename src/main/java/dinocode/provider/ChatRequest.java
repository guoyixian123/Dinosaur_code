package dinocode.provider;

import dinocode.core.Message;
import dinocode.core.ToolDefinition;

import java.util.List;

/**
 * 一轮对话的请求参数。模型/地址/密钥在 Provider 构造时已定（来自配置），
 * 这里携带会话历史、本轮最大输出与工具定义（tools 为空 = 本次不带工具）。
 */
public record ChatRequest(List<Message> history, int maxTokens, List<ToolDefinition> tools) {
}
