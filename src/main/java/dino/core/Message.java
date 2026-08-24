package dino.core;

/**
 * 一条对话消息。thinking 内容不进消息，只渲染到终端（见 spec 设计骨架）。
 */
public record Message(Role role, String content) {

    public Message {
        if (role == null) {
            throw new IllegalArgumentException("role 不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
    }
}
