package dinocode.command;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 命令处理函数（ch10 F1/F33）：通过 Ui 抽象操作 TUI，不持有具体实现引用。
 * cancelled 沿用取消传播口径（Agent/Memory 体系一致）；本期 handler 几乎用不到。
 */
@FunctionalInterface
public interface Handler {
    void handle(AtomicBoolean cancelled, Ui ui) throws Exception;
}
