package dinocode.session;

import dinocode.core.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条会话：标识、最后活跃时间、消息列表、会话级设置。
 * 序列化为 ~/.dino/sessions/&lt;id&gt;.json（字段名 role / content 是 checklist §C 的断言依据）。
 */
public final class Session {

    private String id;
    private long lastActive;
    private List<Message> messages = new ArrayList<>();
    private SessionSettings settings = SessionSettings.EMPTY;

    /** Jackson 反序列化用 */
    Session() {
    }

    public Session(String id, long lastActive, List<Message> messages, SessionSettings settings) {
        this.id = id;
        this.lastActive = lastActive;
        this.messages = messages == null ? new ArrayList<>() : messages;
        this.settings = settings == null ? SessionSettings.EMPTY : settings;
    }

    public String getId() {
        return id;
    }

    public long getLastActive() {
        return lastActive;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public SessionSettings getSettings() {
        return settings;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setLastActive(long lastActive) {
        this.lastActive = lastActive;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public void setSettings(SessionSettings settings) {
        this.settings = settings;
    }
}
