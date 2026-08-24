package dino.provider;

import dino.core.Message;

import java.util.List;

/**
 * 一轮对话的请求参数。模型/地址/密钥在 Provider 构造时已定（来自配置），
 * 这里只携带会话历史与本轮生成参数。
 */
public record ChatRequest(List<Message> history, int maxTokens, boolean thinkingEnabled, int thinkingBudget) {
}
