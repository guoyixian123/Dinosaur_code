package dinocode.tui;

import org.jline.terminal.Terminal;

import java.io.PrintWriter;

/**
 * ch16 N16：终端布局管理器——用 DECSTBM 滚动区域把输入区与内容区隔离。
 *
 * <pre>
 * ┌─ 滚动区（1 .. rows-2）──────────────┐
 * │ Banner / 回复 / 工具行 / diff …      │ ← 内容在这滚动（DECSTBM 保证只滚这里）
 * │ ❯ 用户消息（提交后落入滚动区）        │
 * ├─ 滚动区底 = 输入行（rows-1）─────────┤
 * │ ❯ 正在输入的行                       │ ← 光标在此；长内容换行触发区内滚动，
 * │ 状态行（rows）                       │   输入行"永远"被内容从上方推到同位置
 * └─────────────────────────────────────┘
 * </pre>
 *
 * 关键认知：DECSTBM 滚动区必须<b>包含输入行</b>——JLine 不知道滚动区，
 * 它在光标行输出换行时终端只会在滚动区内滚动；若输入行在区外，JLine 的
 * 折行/历史回调会把整屏滚乱。因此布局为：
 *   滚动区 = 1 .. rows-1（1-based），状态行 = 最后一行（区外，单独定位重绘）。
 * 内容输出统一走 scrollLine/scrollRaw（光标先跳到滚动区顶部追加，再跳回输入行）。
 *
 * 降级：终端尺寸取不到时不启用，全部按普通流式输出（行为同改造前）。
 */
final class LayoutManager {

    private static final String CSI = "\033[";

    private final Terminal terminal;
    private final PrintWriter out;
    /** 输入行上方横线（模式色）——footer 重绘用。 */
    private final java.util.function.Supplier<String> topBorder;
    /** 状态行内容（模式 · 模型 · tokens）——footer 重绘用。 */
    private final java.util.function.Supplier<String> statusLine;

    private boolean enabled;
    private int rows;     // 缓存的终端高度
    private int inputRow; // 输入行（1-based）

    LayoutManager(Terminal terminal,
                  java.util.function.Supplier<String> topBorder,
                  java.util.function.Supplier<String> statusLine) {
        this.terminal = terminal;
        this.out = terminal.writer();
        this.topBorder = topBorder;
        this.statusLine = statusLine;
    }

    /** 启动时调用：建立滚动区 + 首次绘制底部。 */
    void init() {
        try {
            rows = terminal.getHeight();
            enabled = rows >= 10 && terminal.getWidth() >= 30;
        } catch (RuntimeException e) {
            enabled = false;
        }
        if (enabled) {
            applyRegion();
            redrawTopBorder();
            redrawStatus();
        }
    }

    /** 退出时解除滚动区，恢复正常终端行为。 */
    void dispose() {
        if (!enabled) {
            return;
        }
        out.print(CSI + "r");          // DECSTBM 复位（全屏）
        out.print(CSI + rows + ";1H"); // 光标到最后一行
        out.println();
        out.flush();
    }

    /** SIGWINCH：尺寸变化后重建区域并重绘底部；输入行残留清掉。 */
    void resize() {
        if (!enabled) {
            init();
            return;
        }
        try {
            rows = terminal.getHeight();
            enabled = rows >= 10 && terminal.getWidth() >= 30;
            if (enabled) {
                // 先清旧输入行（可能在旧位置的半截内容）
                out.print("\0337");
                out.print(CSI + inputRow + ";1H\033[2K"); // 旧输入行清行
                out.print("\0338");
                applyRegion();
                redrawTopBorder();
                redrawStatus();
                focusInput();
            }
        } catch (RuntimeException ignored) {
            // 保持现状
        }
        out.flush();
    }

    private void applyRegion() {
        inputRow = rows - 1;           // 1-based：倒数第二行是输入行
        // 滚动区 = 第 1 行 .. rows-1（含输入行）；状态行在 rows 行（区外）
        out.print(CSI + "1;" + inputRow + "r");
        out.flush();
    }

    boolean isEnabled() {
        return enabled;
    }

    /**
     * 在滚动区追加一行内容（回复行/工具行/diff 行/通知）。
     * 原子序列：锚定输入行 → 输出 → 回车换行（区内滚动）→ 光标自然回到区底输入行。
     * 每次都先锚定，不依赖"光标恰好在输入行"——resize/菜单/中断后依然正确。
     */
    void scrollLine(String s) {
        if (!enabled) {
            out.println(s);
            out.flush();
            return;
        }
        out.print(CSI + inputRow + ";1H"); // 锚定输入行行首
        out.print(s + "\r\n");             // 输出并换行 → 触发区内滚动
        out.flush();
    }

    /** 在滚动区输出裸文本（流式正文/thinking；换行随内容）。输出前锚定输入行。 */
    void scrollRaw(String s) {
        if (!enabled) {
            out.print(s);
            out.flush();
            return;
        }
        out.print(CSI + inputRow + ";1H"); // 锚定（流式 delta 每次都重新锚定，行内位置丢给 \r 语义）
        out.print(s);
        out.flush();
    }

    /**
     * 重绘状态行（区外最后一行）：横线由调用方决定时机。
     * 状态行变化后调用（回合结束/模式切换/用量更新）。
     */
    void redrawStatus() {
        if (!enabled) {
            return;
        }
        out.print("\0337");                 // SCOSC 存光标
        out.print(CSI + rows + ";1H");      // 状态行行首
        out.print("\r\033[2K");             // 清状态行
        out.print(statusLine.get());        // 画状态行
        out.print("\0338");                 // 复位光标（输入行）
        out.flush();
    }

    /** 重绘输入行上方的横线（模式切换时变色用）。 */
    void redrawTopBorder() {
        if (!enabled) {
            return;
        }
        out.print("\0337");
        out.print(CSI + (inputRow - 1) + ";1H"); // 横线行 = 输入行上一行
        out.print("\r\033[2K");
        out.print(topBorder.get());
        out.print("\0338");
        out.flush();
    }

    /** 把光标放回输入行行首（readLine 前/Spinner 清行后调用）。 */
    void focusInput() {
        if (!enabled) {
            return;
        }
        out.print(CSI + inputRow + ";1H");
        out.flush();
    }

    /** 清空输入行内容（提交后调用；JLine 回显的文本随之消失）。 */
    void clearInputRow() {
        if (!enabled) {
            return;
        }
        out.print(CSI + inputRow + ";1H");
        out.print("\033[2K");
        out.flush();
    }
}
