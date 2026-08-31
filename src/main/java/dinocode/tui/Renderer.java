package dinocode.tui;

import dinocode.core.Usage;

import java.io.PrintWriter;

/**
 * 流式渲染：正文原色逐字、thinking 暗色带前缀、错误红字、工具行（checklist §B / §F / §G / §H）。
 * 非线程安全，只由生成循环单线程调用。
 */
final class Renderer {

    private static final String THINKING_PREFIX = "· ";
    private static final int TOOL_SUMMARY_LINES = 8;

    private final PrintWriter out;
    private boolean inThinking;
    private boolean atLineStart;

    Renderer(PrintWriter out) {
        this.out = out;
    }

    void thinking(String text) {
        if (!inThinking) {
            out.print(Ansi.DIM);
            inThinking = true;
            atLineStart = true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (atLineStart) {
                out.print(THINKING_PREFIX);
                atLineStart = false;
            }
            out.print(c);
            if (c == '\n') {
                atLineStart = true;
            }
        }
        out.flush();
    }

    void text(String text) {
        endThinking();
        out.print(text);
        out.flush();
    }

    void done(Usage usage) {
        endThinking();
        out.println();
        if (usage != null && usage.inputTokens() != null && usage.outputTokens() != null) {
            out.println(Ansi.DIM + "↑" + usage.inputTokens() + " ↓" + usage.outputTokens() + " tokens" + Ansi.RESET);
        }
        out.flush();
    }

    void failure(String message) {
        endThinking();
        out.println(Ansi.RED + message + Ansi.RESET);
        out.flush();
    }

    void notice(String message) {
        out.println(Ansi.DIM + message + Ansi.RESET);
        out.flush();
    }

    /** 工具行：绿色 ● name(args)（AC11/F8）。 */
    void toolLine(String name, String argsPreview) {
        endThinking();
        String args = argsPreview == null || argsPreview.isEmpty() ? "" : "(" + argsPreview + ")";
        out.println(Ansi.GREEN + "● " + name + args + Ansi.RESET);
        out.flush();
    }

    /** 工具结果摘要：缩进 ⎿，灰/红，截断 ~8 行（AC11/F9）。 */
    void toolSummary(String summary, boolean isError) {
        String color = isError ? Ansi.RED : Ansi.DIM;
        String text = summary == null ? "" : summary.trim();
        if (text.isEmpty()) {
            out.println(color + "  ⎿ （无输出）" + Ansi.RESET);
        } else {
            for (String line : truncateLines(text, TOOL_SUMMARY_LINES).split("\n", -1)) {
                out.println(color + "  ⎿ " + line + Ansi.RESET);
            }
        }
        out.flush();
    }

    private static String truncateLines(String s, int maxLines) {
        String[] lines = s.split("\n", -1);
        if (lines.length <= maxLines) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.append("…").toString();
    }

    private void endThinking() {
        if (inThinking) {
            out.print(Ansi.RESET);
            out.println();
            inThinking = false;
            out.flush();
        }
    }
}
