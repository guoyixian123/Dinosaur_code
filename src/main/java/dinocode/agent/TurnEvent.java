package dinocode.agent;

import dinocode.core.Usage;

/**
 * 单轮闭环对外事件流（sealed，TUI 按变体渲染）。比 core.ChatEvent 高一层，含工具行语义。
 */
public sealed interface TurnEvent {

    /** 正文增量（preamble 或最终答复）。 */
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

    /** 本轮结束。 */
    record Done(Usage usage) implements TurnEvent {
    }

    /** 出错（不中断会话）。 */
    record Error(String message) implements TurnEvent {
    }
}
