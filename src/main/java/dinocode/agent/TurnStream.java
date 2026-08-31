package dinocode.agent;

/**
 * 单轮闭环的阻塞式事件流（对齐 provider.EventStream 风格）。
 * next() 阻塞拉取；流结束（Done/Error 之后）返回 null；close() 中断本轮（关底层流）。
 */
public interface TurnStream extends AutoCloseable {

    TurnEvent next();

    @Override
    void close();
}
