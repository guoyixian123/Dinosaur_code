package dinocode.core;

/**
 * 模型发起的一次工具调用（流式拼接完成后）。
 * arguments 为完整 JSON 参数字符串（两协议均以字符串传递，回灌时按原样返回）。
 */
public record ToolCall(String id, String name, String arguments) {
}
