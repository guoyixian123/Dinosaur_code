package dinocode.tui;

import dinocode.config.AppConfig;

import java.io.PrintWriter;
import java.util.List;

/**
 * 启动画面（ch16 视觉优化）：ANSI Shadow 实心块字体的 DINO CODE 大字标 + 圆角状态面板。
 * 大字标 6 行 × 64 列（等宽由 BannerTest 断言）；窄终端（<68 列）降级为单行标题，防折行破碎。
 */
final class Banner {

    /** 大字标整体降级的终端宽度阈值（64 列字标 + 余量）。 */
    static final int MIN_WIDTH = 68;
    /** 状态面板最大内容宽度（超出截断）。 */
    private static final int PANEL_MAX = 45;

    /** ANSI Shadow 字体的 DINO CODE，6 行等宽 64 列。
     *  D 末行是 ╚═════╝（右下闭合）；R 末行是 ╚████╔╝（开口）——两者靠此区分。 */
    private static final List<String> LOGO = List.of(
            "██████╗ ██╗ ███╗   ██╗ ██████╗  ██████╗ ██████╗ ██████╗ ███████╗",
            "██╔══██╗██║ ████╗  ██║██╔══██╗██╔════╝██╔═══██╗██╔══██╗██╔════╝ ",
            "██║  ██║██║ ██╔██╗ ██║██║  ██║██║     ██║   ██║██║  ██║█████╗   ",
            "██║  ██║██║ ██║╚██╗██║██║  ██║██║     ██║   ██║██║  ██║██╔══╝   ",
            "██████╔╝██║ ██║ ╚████║██████╔╝╚██████╗╚██████╔╝██████╔╝███████╗ ",
            "╚═════╝ ╚═╝ ╚═╝  ╚═══╝╚═════╝  ╚═════╝  ╚═════╝ ╚═════╝ ╚══════╝");

    /** 大字标单行显示列数（全部为单列块字符）。 */
    static final int LOGO_WIDTH = 64;

    private Banner() {
    }

    static void print(PrintWriter out, AppConfig config, String sessionInfo, int terminalWidth) {
        if (terminalWidth >= MIN_WIDTH) {
            for (String line : LOGO) {
                out.println(Ansi.GREEN + line + Ansi.RESET);
            }
            out.println();
        } else {
            out.println(Ansi.BOLD + Ansi.GREEN + "Dino Code" + Ansi.RESET + Ansi.DIM + " v0.1" + Ansi.RESET);
        }
        printPanel(out, "模型 " + config.model(), "会话 " + sessionInfo, "模式 DEFAULT");
        out.println(Ansi.DIM + "  Shift+Tab 切换模式 · Tab 补全命令 · /help 帮助" + Ansi.RESET);
        out.println();
        out.flush();
    }

    /** 圆角状态面板：每项一行，宽度按最长项自适应（spec §3.1）。 */
    private static void printPanel(PrintWriter out, String... items) {
        int width = 0;
        for (String item : items) {
            width = Math.max(width, displayWidth(item));
        }
        width = Math.min(width, PANEL_MAX) + 2; // 左右各留 1 空格

        out.println(Ansi.GREEN + "╭" + "─".repeat(width) + "╮" + Ansi.RESET);
        for (String item : items) {
            String text = item.length() > PANEL_MAX ? item.substring(0, PANEL_MAX) : item;
            int pad = width - 2 - displayWidth(text);
            out.println(Ansi.GREEN + "│" + Ansi.RESET + " " + Ansi.DIM + text + " ".repeat(Math.max(0, pad))
                    + Ansi.RESET + Ansi.GREEN + " │" + Ansi.RESET);
        }
        out.println(Ansi.GREEN + "╰" + "─".repeat(width) + "╯" + Ansi.RESET);
    }

    /** 显示宽度：CJK 等宽字符按 2 列计，其余 1 列（面板对齐用）。 */
    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += (c >= 0x1100 && c <= 0x9FFF) || (c >= 0xAC00 && c <= 0xD7A3)
                    || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFF00 && c <= 0xFF60) ? 2 : 1;
        }
        return w;
    }
}
