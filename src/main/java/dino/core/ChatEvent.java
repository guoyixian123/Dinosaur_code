package dino.core;

/**
 * 统一流式事件：所有协议收敛为这四种（见 spec 设计骨架）。
 * 终端层只依赖本类型，不感知协议差异。
 */
public sealed interface ChatEvent {

    /** 思考内容增量（Claude extended thinking） */
    record ThinkingDelta(String text) implements ChatEvent {
    }

    /** 正文增量 */
    record TextDelta(String text) implements ChatEvent {
    }

    /** 本轮生成结束，usage 可能为空（取决于协议是否回传） */
    record Done(Usage usage) implements ChatEvent {
    }

    /** 带分类的错误；出现后流即终止 */
    record Failure(ErrorKind kind, String message) implements ChatEvent {
    }
}
