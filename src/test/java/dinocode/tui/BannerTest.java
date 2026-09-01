package dinocode.tui;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ch16：Banner 测试——大字标等宽、窄终端降级、面板渲染。
 */
class BannerTest {

    @SuppressWarnings("unused")
    private final dinocode.config.AppConfig config = new dinocode.config.AppConfig(
            "anthropic", "glm-test", "http://localhost", "test-key", 4096, null);

    private static String render(int width) {
        StringWriter buffer = new StringWriter();
        Banner.print(new PrintWriter(buffer, true), new dinocode.config.AppConfig(
                "anthropic", "glm-test", "http://localhost", "k", 4096, null), "新会话", width);
        return buffer.toString();
    }

    @Test
    void logoLinesAreUniformWidth() {
        // 通过渲染宽终端输出验证 6 行等宽：剥离 ANSI 后每行 64 列
        String[] lines = render(100).split("\n", -1);
        int logoLines = 0;
        int expected = -1;
        for (String line : lines) {
            String plain = line.replaceAll("\033\\[[0-9;]*m", "");
            if (plain.contains("█") || plain.contains("╗") || plain.contains("╝") || plain.contains("║")) {
                logoLines++;
                int w = plain.codePointCount(0, plain.length());
                if (expected < 0) {
                    expected = w;
                }
                assertEquals(Banner.LOGO_WIDTH, w, "字标行应 " + Banner.LOGO_WIDTH + " 列: " + plain);
            }
        }
        assertEquals(6, logoLines);
    }

    @Test
    void wideTerminalShowsPanelAndHelp() {
        String out = render(100);
        assertTrue(out.contains("╭")); // 状态面板
        assertTrue(out.contains("模型"));
        assertTrue(out.contains("glm-test"));
        assertTrue(out.contains("Shift+Tab"));
        assertTrue(out.contains("模式 DEFAULT"));
    }

    @Test
    void narrowTerminalFallsBackToSingleLine() {
        String out = render(40);
        assertTrue(out.contains("Dino Code"));
        for (String line : out.split("\n", -1)) {
            String plain = line.replaceAll("\033\\[[0-9;]*m", "");
            if (plain.contains("█")) {
                throw new AssertionError("窄终端不应输出大字标");
            }
        }
    }

    @Test
    void noEmojiAnywhere() {
        String out = render(100);
        assertTrue(out.codePoints().noneMatch(cp -> cp >= 0x1F300 && cp <= 0x1FAFF), "启动画面不得含 emoji");
    }
}
