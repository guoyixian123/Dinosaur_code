package dinocode.core;

/**
 * 一次工具执行结果（message 级，关联 toolCallId 供两协议回灌）。
 */
public record ToolResult(String toolCallId, String content, boolean isError) {
}
