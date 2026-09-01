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
    /** ch16：DiffView 摘要的机器可读标记前缀（tool 包产生，本类消费）。 */
    private static final String DIFF_MARK = "@@DIF";

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

    /** 迭代进度提示（ch04 F9）：仅在多轮时可见。 */
    void iter(int iter) {
        if (iter > 1) {
            out.println(Ansi.DIM + "── 第 " + iter + " 轮 ──" + Ansi.RESET);
            out.flush();
        }
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
        String text = summary == null ? "" : summary.trim();
        if (text.isEmpty()) {
            out.println(Ansi.DIM + "  ⎿ （无输出）" + Ansi.RESET);
        } else if (text.startsWith(DIFF_MARK)) {
            renderDiff(text, isError); // ch16：Edit/Write 的 @@DIF 摘要走红绿 diff 渲染
        } else {
            String color = isError ? Ansi.RED : Ansi.DIM;
            for (String line : truncateLines(text, TOOL_SUMMARY_LINES).split("\n", -1)) {
                out.println(color + "  ⎿ " + line + Ansi.RESET);
            }
        }
        out.flush();
    }

    /**
     * ch16 diff 渲染：解析 DiffView 的 @@DIF 标记，统计行 + 红删绿增蓝行号。
     * 畸形行按纯文本 DIM 降级，不抛异常（spec §4）。
     */
    private void renderDiff(String text, boolean isError) {
        String[] lines = text.split("\n", -1);
        boolean first = true;
        for (String raw : lines) {
            if (!raw.startsWith(DIFF_MARK)) {
                out.println(Ansi.DIM + "  ⎿ " + raw + Ansi.RESET); // 尾部省略行等
                continue;
            }
            String body = raw.substring(DIFF_MARK.length()); // " +1 -1" 或 " -  2|xxx"
            if (first) {
                // 统计行：@@DIF +2 -1 [（新文件）]——判据：第 2 个字符是数字前的 +/- 且无 '|'
                String tag = isError ? " " + Ansi.RED + "失败" : "";
                String extra = trailingTag(body);
                out.println(Ansi.DIM + "  ⎿ " + Ansi.RESET + Ansi.GREEN + "+" + addedOf(body)
                        + Ansi.RESET + Ansi.DIM + " " + Ansi.RESET + Ansi.RED + "−" + removedOf(body)
                        + Ansi.RESET + Ansi.DIM + " 已应用" + extra + tag + Ansi.RESET);
                first = false;
                continue;
            }
            // diff 行："@@DIF -  13|内容" → kind + lineno + content。
            // 格式定死："@@DIF " + kind + "  " + lineno + "|"，按位置切而非 stripLeading
            String kind = body.length() > 4 ? body.substring(1, 2) : "";
            int bar = body.indexOf('|');
            if (bar < 0 || body.charAt(2) != ' ' || body.charAt(3) != ' '
                    || (!kind.equals("-") && !kind.equals("+") && !kind.equals("="))) {
                // 畸形行降级：剥标记前缀按纯文本渲染，用户不感知 @@DIF
                out.println(Ansi.DIM + "  ⎿ " + body.stripLeading() + Ansi.RESET);
                continue;
            }
            String lineno = body.substring(4, bar);
            String content = body.substring(bar + 1);
            String prefix = Ansi.DIM + "  ⎿ ";
            switch (kind) {
                case "-" -> out.println(prefix + Ansi.RED + Ansi.BOLD + "− " + lineno + Ansi.DIM + " │ "
                        + Ansi.RED + content + Ansi.RESET);
                case "+" -> out.println(prefix + Ansi.GREEN + Ansi.BOLD + "+ " + lineno + Ansi.DIM + " │ "
                        + Ansi.GREEN + content + Ansi.RESET);
                default -> out.println(prefix + "  " + Ansi.BLUE + lineno + Ansi.DIM + " │ " + content + Ansi.RESET);
            }
        }
        out.flush();
    }

    /** 统计行尾部的 "(新文件)" 等非数字后缀原样带出。 */
    private static String trailingTag(String statBody) {
        int i = 0;
        while (i < statBody.length()
                && (statBody.charAt(i) == ' ' || statBody.charAt(i) == '+'
                || statBody.charAt(i) == '-' || Character.isDigit(statBody.charAt(i)))) {
            i++;
        }
        return statBody.substring(i);
    }

    /** 从统计行提取新增数（+ 后的数字；解析失败记 0）。 */
    private static int addedOf(String statBody) {
        return numberAfter(statBody, '+');
    }

    /** 从统计行提取删除数（- 后的数字；解析失败记 0）。 */
    private static int removedOf(String statBody) {
        return numberAfter(statBody, '-');
    }

    private static int numberAfter(String s, char sign) {
        int i = s.indexOf(sign);
        if (i < 0) {
            return 0;
        }
        int j = i + 1;
        while (j < s.length() && Character.isDigit(s.charAt(j))) {
            j++;
        }
        try {
            return Integer.parseInt(s.substring(i + 1, j));
        } catch (NumberFormatException e) {
            return 0;
        }
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
