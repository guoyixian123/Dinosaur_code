package dino.provider;

import dino.core.ChatEvent;

/**
 * 统一事件流：阻塞式逐个拉取。
 * 返回 Done / Failure 后流即终止，其后 next() 恒为 null。
 * close() 用于中断：关闭底层连接后，next() 静默返回 null（调用方知道是自己发起的中断）。
 */
public interface EventStream extends AutoCloseable {

    /** 阻塞等待下一个事件；流结束返回 null。 */
    ChatEvent next();

    @Override
    void close();
}
