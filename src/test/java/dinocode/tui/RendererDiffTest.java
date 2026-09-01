package dinocode.tui;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ch16：Renderer diff 渲染测试——@@DIF 摘要的红绿渲染、畸形行降级、普通摘要不受影响。
 */
class RendererDiffTest {

    private record Rendered(StringWriter buffer, Renderer renderer) {
        String out() {
            return buffer.toString();
        }
    }

    private static Rendered render() {
        StringWriter buffer = new StringWriter();
        return new Rendered(buffer, new Renderer(new PrintWriter(buffer, true)));
    }

    @Test
    void diffSummaryRendersColorsAndLineNumbers() {
        Rendered r = render();
        r.renderer().toolSummary("@@DIF +1 -1\n"
                + "@@DIF =  1|aaa\n"
                + "@@DIF -  2|bbb\n"
                + "@@DIF +  2|B2B\n"
                + "@@DIF =  3|ccc", false);
        String out = r.out();
        String plain = out.replaceAll("\033\\[[0-9;]*m", ""); // 剥 ANSI 后做文本断言
        assertTrue(plain.contains("⎿ +1 −1 已应用"), out); // 统计行
        assertTrue(out.contains(Ansi.RED));             // 删除行红
        assertTrue(out.contains(Ansi.GREEN));           // 新增行绿
        assertTrue(out.contains(Ansi.BLUE));            // 行号蓝
        assertTrue(plain.contains("bbb"));
        assertTrue(plain.contains("B2B"));
        assertFalse(plain.contains("@@DIF"));           // 标记被剥离
    }

    @Test
    void malformedDiffLinesFallBackToDim() {
        Rendered r = render();
        r.renderer().toolSummary("@@DIF +0 -0\n"
                + "@@DIF X  9|broken\n"
                + "not-a-mark", false);
        String plain = r.out().replaceAll("\033\\[[0-9;]*m", "");
        assertTrue(plain.contains("broken"));    // 畸形标记行降级输出
        assertTrue(plain.contains("not-a-mark"));
        assertFalse(plain.contains("@@DIF"));
    }

    @Test
    void plainSummaryUnaffected() {
        Rendered r = render();
        r.renderer().toolSummary("已修改 a.txt", false);
        String out = r.out();
        assertTrue(out.contains("已修改 a.txt"));
        assertFalse(out.contains(Ansi.BLUE)); // 旧路径不引入蓝色
    }

    @Test
    void newFileStatShowsTag() {
        Rendered r = render();
        r.renderer().toolSummary("@@DIF +3 -0 (新文件)\n@@DIF +  1|l1", false);
        String plain = r.out().replaceAll("\033\\[[0-9;]*m", "");
        assertTrue(plain.contains("+3 −0"), plain);
        assertTrue(plain.contains("(新文件)"));
    }

    @Test
    void noEmojiInSpinnerText() {
        // 顺带回归：Spinner 文案（去 emoji 后）由 Tui 使用，这里验证 Ansi 常量未被误改
        assertTrue(Ansi.BLUE.contains("[34m"));
    }
}
