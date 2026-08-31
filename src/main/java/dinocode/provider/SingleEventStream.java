package dinocode.provider;

import dinocode.core.ChatEvent;

/**
 * 只有一个事件的流：用于请求发出前就失败的场景（连接拒绝、序列化失败等）。
 */
final class SingleEventStream implements EventStream {

    private ChatEvent event;

    private SingleEventStream(ChatEvent event) {
        this.event = event;
    }

    static EventStream of(ChatEvent event) {
        return new SingleEventStream(event);
    }

    @Override
    public ChatEvent next() {
        ChatEvent result = event;
        event = null;
        return result;
    }

    @Override
    public void close() {
        event = null;
    }
}
