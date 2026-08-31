package dinocode.hook;

import java.util.Optional;

/**
 * Agent 生命周期事件（ch12 F9）：11 个固定时刻。
 * isBlocking：拦截类事件（同步执行、可通过约定信号拦截动作）。
 */
public enum Event {
    SESSION_START, SESSION_END, SESSION_RESUME,
    USER_PROMPT_SUBMIT, STOP, PRE_USER_MESSAGE,
    PRE_TOOL_USE, POST_TOOL_USE,
    PRE_COMPACT, POST_COMPACT,
    NOTIFICATION;

    /** 拦截类事件：shell exit 2 / http decision=block 生效（G6/F19/F25）。 */
    public boolean isBlocking() {
        return this == PRE_TOOL_USE || this == USER_PROMPT_SUBMIT;
    }

    /** 大小写不敏感、连接符宽松解析（F9）：接受 "SessionStart" / "session_start" / "session-start"；未知返回 empty。 */
    public static Optional<Event> parse(String s) {
        if (s == null || s.isBlank()) {
            return Optional.empty();
        }
        String trimmed = s.strip();
        for (Event e : values()) {
            if (e.name().equalsIgnoreCase(trimmed)
                    || e.wireName().equalsIgnoreCase(trimmed)
                    || e.name().toLowerCase().replace("_", "-").equalsIgnoreCase(trimmed.replace("_", "-"))) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** JSON payload 中事件名的驼峰形式（N6）：SESSION_START → "SessionStart"，STOP → "Stop"。 */
    public String wireName() {
        String[] parts = name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
