package dino.tui;

import dino.config.AppConfig;

import java.io.PrintWriter;

/**
 * 启动画面：绿色恐龙 ASCII + 名称 + 状态行（checklist §H）。
 * 用 ASCII 艺术而非 emoji，保证任意终端可渲染。
 */
final class Banner {

    private static final String DINO = """
                          __
                         / _)
                _.----._/ /
               /         /
            __/ (  | (  |
           /__.-'|_|--|_|""";

    private Banner() {
    }

    static void print(PrintWriter out, AppConfig config, String sessionInfo) {
        out.println(Ansi.GREEN + DINO + Ansi.RESET);
        out.println(Ansi.BOLD + Ansi.GREEN + "Dino Code" + Ansi.RESET + Ansi.DIM + " v0.1" + Ansi.RESET);
        out.println(Ansi.DIM + "model=" + config.model() + Ansi.RESET);
        out.println(Ansi.DIM + sessionInfo + Ansi.RESET);
        out.println();
        out.flush();
    }
}
