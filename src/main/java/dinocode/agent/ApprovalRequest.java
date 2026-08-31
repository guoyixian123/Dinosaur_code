package dinocode.agent;

import dinocode.permission.Outcome;

import java.util.concurrent.BlockingQueue;

/**
 * 人在回路待批准请求（ch06 F8）。agent 在 Ask 时发出，阻塞等 TUI 回传决策。
 *
 * @param name    工具名（展示用）
 * @param args    参数预览
 * @param reason  触发 Ask 的原因（模式 + 类别）
 * @param respond 容量=1：TUI 回传用户选择，agent 单次接收
 */
public record ApprovalRequest(String name, String args, String reason, BlockingQueue<Outcome> respond) {
}
