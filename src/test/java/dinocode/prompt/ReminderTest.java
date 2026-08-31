package dinocode.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补充消息构造单测（ch05 F6/F7/AC8/AC9）：标签包裹、完整/精简两档。
 */
class ReminderTest {

    @Test
    void systemReminderWrapsWithTag() {
        String wrapped = Reminder.systemReminder("正文内容");

        assertEquals("<system-reminder>\n正文内容\n</system-reminder>", wrapped);
        assertTrue(wrapped.startsWith("<system-reminder>"));
        assertTrue(wrapped.endsWith("</system-reminder>"));
    }

    @Test
    void planFullContainsCompleteReminder() {
        String full = Reminder.plan(true);

        assertTrue(full.contains("<system-reminder>"));
        assertTrue(full.contains("计划模式"));
        assertTrue(full.contains("只读工具"));
        assertTrue(full.contains("/do"));
        assertTrue(full.contains("不得写文件"));
    }

    @Test
    void planConciseIsShorterAndTagged() {
        String concise = Reminder.plan(false);

        assertTrue(concise.contains("<system-reminder>"));
        assertTrue(concise.contains("计划模式"));
        // 精简版短于完整版、不展开完整约束文案
        assertTrue(concise.length() < Reminder.plan(true).length());
        assertFalse(concise.contains("调研代码库并产出一份分步执行计划"));
    }

    @Test
    void executeDirectiveMigratedFromPrompt() {
        assertEquals("请按上面的计划开始执行。", Reminder.EXECUTE_DIRECTIVE);
    }
}
