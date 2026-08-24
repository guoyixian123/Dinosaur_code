package dino.tui;

import dino.core.Usage;

import java.io.PrintWriter;

/**
 * 流式渲染：正文原色逐字、thinking 暗色带前缀、错误红字（checklist §B / §F / §G）。
 * 非线程安全，只由生成循环单线程调用。
 */
final class Renderer {

    private static final String THINKING_PREFIX = "· ";

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

    private void endThinking() {
        if (inThinking) {
            out.print(Ansi.RESET);
            out.println();
            inThinking = false;
            out.flush();
        }
    }
}
