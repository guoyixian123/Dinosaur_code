package dinocode.prompt;

/**
 * 补充消息注入（ch05 F6/F7）：带特殊标签的运行中指令。
 * 此类消息每轮动态构造、不写入持久历史——不污染缓存、不破坏角色交替（N3）。
 */
public final class Reminder {

    /** 规划模式完整提醒：首轮与间隔轮注入（F7）。 */
    static final String PLAN_REMINDER_FULL = """
            你当前处于计划模式（PLAN MODE）。你只能使用只读工具（ReadFile、Glob、Grep）\
            来调研代码库并产出一份分步执行计划。你不得写文件、修改文件或执行命令。
            计划写完即停，等待用户用 /do 批准后再开始执行。""";

    /** 规划模式精简提醒：间隔轮之间的轮次注入（F7）。 */
    static final String PLAN_REMINDER_CONCISE = "仍在计划模式：只用只读工具调研，计划写完即停，等 /do 批准。";

    /** /do 注入的用户消息：指示模型按上文已确认的计划开始执行（ch04 F10 迁入）。 */
    public static final String EXECUTE_DIRECTIVE = "请按上面的计划开始执行。";

    private Reminder() {
    }

    /** 用标签包裹，让模型理解这是系统补充上下文而非用户提问（F6/AC8）。 */
    public static String systemReminder(String body) {
        return "<system-reminder>\n" + body + "\n</system-reminder>";
    }

    /** 规划模式提醒：full=完整版，否则精简版（F7）。 */
    public static String plan(boolean full) {
        return systemReminder(full ? PLAN_REMINDER_FULL : PLAN_REMINDER_CONCISE);
    }
}
