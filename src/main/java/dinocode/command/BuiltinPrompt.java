package dinocode.command;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 2 条提示词命令（ch10 F11/F14/F23 / T7）：注入固定文本 user 消息并立即触发回合。
 * 消息按真实用户消息相同路径持久化（N3），对 LLM 不可区分。
 */
final class BuiltinPrompt {

    /** /review 注入的审查请求固定文案（F23：不读 git diff、不收集外部上下文）。 */
    static final String REVIEW_DIRECTIVE =
            "请审查当前上下文中出现过的代码变更与文件内容，指出潜在 bug、可读性问题和可简化处。"
                    + "如需查看最新文件内容请先用工具读取。";

    private BuiltinPrompt() {
    }

    /** /do：切回默认模式 + 注入执行指令（F14，外部行为与实施前一致，N10）。 */
    static Handler doRun() {
        return (AtomicBoolean cancelled, Ui ui) -> {
            ui.setMode(dinocode.permission.Mode.DEFAULT);
            ui.injectAndSend("/do", dinocode.prompt.Reminder.EXECUTE_DIRECTIVE);
        };
    }

    /** /review：注入审查请求（F23/AC9）。 */
    static Handler review() {
        return (AtomicBoolean cancelled, Ui ui) -> ui.injectAndSend("/review", REVIEW_DIRECTIVE);
    }
}
