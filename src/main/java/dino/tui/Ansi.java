package dino.tui;

/**
 * ANSI 颜色。主题基调见 checklist §H：主色绿（32）、思考暗色（2）、错误红（31）。
 */
final class Ansi {

    static final String ESC = "\033";

    static final String RESET = ESC + "[0m";
    static final String BOLD = ESC + "[1m";
    static final String DIM = ESC + "[2m";
    static final String GREEN = ESC + "[32m";
    static final String RED = ESC + "[31m";

    /** 回车并清空当前行（用于清除等待指示器）。 */
    static final String CLEAR_LINE = "\r" + ESC + "[2K";

    private Ansi() {
    }
}
