package dinocode.command;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 5 条影响界面命令（ch10 F12/F13/F15~F17 / T6）：改 TUI 状态、不进 history。
 * idle 守护已在 dispatcher 按 Kind 统一处理（N3a），handler 内不重复检查。
 */
final class BuiltinUi {

    private BuiltinUi() {
    }

    /** /exit：退出进程（N12 由 Tui.quit 实现内处理）。 */
    static Handler exit() {
        return (AtomicBoolean cancelled, Ui ui) -> ui.quit();
    }

    /** /plan：切到计划模式（F13）。 */
    static Handler plan() {
        return (AtomicBoolean cancelled, Ui ui) -> {
            ui.setMode(dinocode.permission.Mode.PLAN);
            ui.println("已进入计划模式（只读工具）。产出计划后用 /do 执行。");
        };
    }

    /** /compact：手动压缩（F15，沿用 ch08 行为，N10）。 */
    static Handler compact() {
        return (AtomicBoolean cancelled, Ui ui) -> ui.forceCompact();
    }

    /** /resume：打开会话恢复列表（F16，沿用 ch09 行为，N10）。 */
    static Handler resume() {
        return (AtomicBoolean cancelled, Ui ui) -> ui.openResumeMenu();
    }

    /** /clear：清空当前会话并开新会话（F17/AC8）。 */
    static Handler clear() {
        return (AtomicBoolean cancelled, Ui ui) -> {
            ui.clearAndNewSession();
            ui.println("已结束当前会话，开启新会话。");
        };
    }
}
