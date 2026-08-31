package dinocode.agent;

import dinocode.core.Usage;
import dinocode.permission.Outcome;

import java.util.concurrent.BlockingQueue;

/**
 * Agent Loop 对外事件流（sealed，TUI 按变体渲染）。比 core.ChatEvent 高一层，含工具行与循环进度语义。
 */
public sealed interface TurnEvent {

    /** 正文增量（每轮 preamble 或最终答复）。 */
    record Text(String delta) implements TurnEvent {
    }

    /** 思考增量。 */
    record Thinking(String delta) implements TurnEvent {
    }

    /** 工具行：开始执行（携带参数预览）。 */
    record ToolStart(String name, String argsPreview) implements TurnEvent {
    }

    /** 工具行：执行结束（携带结果摘要）。 */
    record ToolEnd(String name, String summary, boolean isError) implements TurnEvent {
    }

    /** 本轮 token 用量（每轮 stream 结束后一次，ch04 F8）。 */
    record UsageReport(Usage usage) implements TurnEvent {
    }

    /** 进入第 iter 轮迭代（ch04 F9）。 */
    record Iter(int iter) implements TurnEvent {
    }

    /** 系统提示（停止原因等）；仅 UI 展示，不入历史（ch04 F2）。 */
    record Notice(String message) implements TurnEvent {
    }

    /** 人在回路：待用户批准的工具调用（ch06 F8）。消费者必须向 respond 回传决策，否则 agent 永远阻塞；取消路径由 TUI 兜底回传 DENY_ONCE 解阻塞。 */
    record Approval(ApprovalRequest request) implements TurnEvent {
    }

    /** 本轮（整个 Loop）结束。 */
    record Done(Usage usage) implements TurnEvent {
    }

    /** 出错（不中断会话）。 */
    record Error(String message) implements TurnEvent {
    }
}
