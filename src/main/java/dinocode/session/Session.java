package dinocode.session;

import dinocode.core.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 一条会话：标识、最后活跃时间、消息列表、会话级设置。
 * 序列化为 ~/.dino/sessions/&lt;id&gt;.json（字段名 role / content 是 checklist §C 的断言依据）。
 *
 * <p>ch09：新增 onAppend / onReplace 回调——消息追加 / 整体替换后触发，
 * 由 session.Writer 实现回调实现 JSONL 实时存档；未设置回调时行为与 ch08 完全一致（F44）。
 */
public final class Session {

    private String id;
    private long lastActive;
    private List<Message> messages = new ArrayList<>();
    private SessionSettings settings = SessionSettings.EMPTY;
    private transient Consumer<Message> onAppend;
    private transient Consumer<List<Message>> onReplace;

    /** Jackson 反序列化用 */
    Session() {
    }

    public Session(String id, long lastActive, List<Message> messages, SessionSettings settings) {
        this.id = id;
        this.lastActive = lastActive;
        this.messages = messages == null ? new ArrayList<>() : messages;
        this.settings = settings == null ? SessionSettings.EMPTY : settings;
    }

    public Session(String id, long lastActive, List<Message> messages, SessionSettings settings,
                   Consumer<Message> onAppend, Consumer<List<Message>> onReplace) {
        this(id, lastActive, messages, settings);
        this.onAppend = onAppend;
        this.onReplace = onReplace;
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

    /** ch09：从已有消息列表创建会话（恢复场景），带存档回调。 */
    public static Session fromMessages(String id, List<Message> msgs,
                                       Consumer<Message> onAppend, Consumer<List<Message>> onReplace) {
        List<Message> copy = new ArrayList<>();
        for (Message m : (msgs == null ? List.<Message>of() : msgs)) {
            copy.add(new Message(m.role(), m.content(),
                    new ArrayList<>(m.toolCalls()), new ArrayList<>(m.toolResults())));
        }
        return new Session(id, System.currentTimeMillis(), copy, SessionSettings.EMPTY, onAppend, onReplace);
    }

    /** ch09：为已构造的会话补挂存档回调。 */
    public void setArchiveCallbacks(Consumer<Message> onAppend, Consumer<List<Message>> onReplace) {
        this.onAppend = onAppend;
        this.onReplace = onReplace;
    }

    /** 追加一条消息并触发 onAppend 回调（F44）。注意：此方法不写 lastActive。 */
    public void append(Message msg) {
        messages.add(msg);
        if (onAppend != null) {
            onAppend.accept(msg);
        }
    }

    /**
     * 整体替换消息列表（ch08 T21）：摘要后用新历史替换旧历史。
     * 深拷贝（含 toolCalls / toolResults），不暴露入参引用。
     * ch09：替换后触发 onReplace 回调（compact 标记 + 逐条追加新消息）。
     */
    public void replaceMessages(List<Message> msgs) {
        List<Message> copy = new ArrayList<>();
        for (Message m : (msgs == null ? List.<Message>of() : msgs)) {
            copy.add(new Message(m.role(), m.content(),
                    new ArrayList<>(m.toolCalls()), new ArrayList<>(m.toolResults())));
        }
        this.messages = copy;
        if (onReplace != null) {
            onReplace.accept(copy);
        }
    }
}
