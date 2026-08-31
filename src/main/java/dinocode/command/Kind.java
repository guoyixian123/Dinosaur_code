package dinocode.command;

/**
 * 命令执行类型（ch10 F8~F11）。
 * LOCAL：纯本地，任何状态可执行（N3a）；UI：改 TUI 状态，不进 history；
 * PROMPT：注入 user 消息触发回合，进 history。
 */
public enum Kind {
    LOCAL,
    UI,
    PROMPT
}
