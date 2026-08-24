package dino.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话历史。每轮全量发送给模型，不做裁剪（v0.1 决策，见 spec）。
 */
public final class Conversation {

    private final List<Message> messages = new ArrayList<>();

    public void add(Message message) {
        messages.add(message);
    }

    public List<Message> messages() {
        return Collections.unmodifiableList(messages);
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public void clear() {
        messages.clear();
    }

    public int size() {
        return messages.size();
    }
}
