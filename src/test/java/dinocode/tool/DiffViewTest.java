package dinocode.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ch16：DiffView 摘要测试——Edit 行对齐与行号、Write 新建/覆盖统计、畸形输入回退。
 */
class DiffViewTest {

    // ==================== Edit 摘要 ====================

    @Test
    void editSingleLineReplace() {
        String old = "aaa\nbbb\nccc\nddd\neee";
        String s = DiffView.editSummary("f.java", old, "ccc", "CXC");
        String[] lines = s.split("\n", -1);
        assertEquals("@@DIF +1 -1", lines[0]); // 统计行
        // 上下文 2 行 + 删 1 + 增 1 + 上下文 2 行 = 6 行 diff 体
        assertEquals(7, lines.length);
        assertTrue(lines[1].startsWith("@@DIF =  1|"));
        assertTrue(lines[1].endsWith("aaa"));
        assertTrue(lines[2].endsWith("bbb"));
        assertTrue(lines[3].startsWith("@@DIF -  3|ccc"));
        assertTrue(lines[4].startsWith("@@DIF +  3|CXC"));
        assertTrue(lines[5].endsWith("ddd"));
        assertTrue(lines[6].endsWith("eee"));
    }

    @Test
    void editMultiLineReplace() {
        String old = "a\nb\nc\nd\ne\nf";
        // 替换 c+d 两行为 X
        String s = DiffView.editSummary("f", old, "c\nd", "x");
        String[] lines = s.split("\n", -1);
        assertEquals("@@DIF +1 -2", lines[0]);
        assertTrue(containsLine(lines, "@@DIF -  3|c"));
        assertTrue(containsLine(lines, "@@DIF -  4|d"));
        assertTrue(containsLine(lines, "@@DIF +  3|x"));
    }

    @Test
    void editUnchangedLinesAreContext() {
        String s = DiffView.editSummary("f", "a\nb", "a", "a"); // new == old
        String[] lines = s.split("\n", -1);
        assertEquals("@@DIF +0 -0", lines[0]);
        assertTrue(containsLine(lines, "@@DIF =  1|a"));
    }

    @Test
    void editFallbackWhenNotFound() {
        // old_string 不在内容中：不抛异常，回退旧文案
        assertEquals("已修改 f.java", DiffView.editSummary("f.java", "a\nb", "zzz", "x"));
    }

    // ==================== Write 摘要 ====================

    @Test
    void writeNewFileStatAndPreview() {
        String s = DiffView.writeSummary("new.md", null, "l1\nl2\nl3", 9);
        String[] lines = s.split("\n", -1);
        assertEquals("@@DIF +3 -0 (新文件)", lines[0]);
        assertTrue(containsLine(lines, "@@DIF +  1|l1"));
        assertTrue(containsLine(lines, "@@DIF +  3|l3"));
    }

    @Test
    void writeOverwriteDiff() {
        String s = DiffView.writeSummary("f.txt", "a\nb\nc", "a\nX\nc", 3);
        String[] lines = s.split("\n", -1);
        assertEquals("@@DIF +1 -1", lines[0]);
        assertTrue(containsLine(lines, "@@DIF -  2|b"));
        assertTrue(containsLine(lines, "@@DIF +  2|X"));
        assertTrue(containsLine(lines, "@@DIF =  1|a")); // 公共前缀作上下文
    }

    @Test
    void writeIdenticalContentZeroChange() {
        String s = DiffView.writeSummary("f.txt", "a\nb", "a\nb", 3);
        assertTrue(s.startsWith("@@DIF +0 -0"));
    }

    // ==================== 与工具集成 ====================

    @TempDir
    Path dir;

    @Test
    void editFileToolProducesDiffSummary() throws Exception {
        Path f = dir.resolve("a.txt");
        Files.writeString(f, "one\ntwo\nthree");
        var tool = new EditFileTool();
        var result = tool.execute(java.util.Map.of(
                "path", f.toString(), "old_string", "two", "new_string", "TWO"));
        assertTrue(result.content().contains("@@DIF +1 -1"));
        assertTrue(result.content().contains("@@DIF -  2|two"));
        assertTrue(result.content().contains("@@DIF +  2|TWO"));
    }

    @Test
    void writeFileToolNewFileSummary() throws Exception {
        Path f = dir.resolve("sub").resolve("b.txt");
        var tool = new WriteFileTool();
        var result = tool.execute(java.util.Map.of("path", f.toString(), "content", "x\ny"));
        assertTrue(result.content().startsWith("@@DIF +2 -0 (新文件)"));
        assertTrue(Files.readString(f).equals("x\ny"));
    }

    @Test
    void writeFileToolOverwriteSummary() throws Exception {
        Path f = dir.resolve("c.txt");
        Files.writeString(f, "old1\nold2\nold3");
        var tool = new WriteFileTool();
        var result = tool.execute(java.util.Map.of("path", f.toString(), "content", "old1\nNEW\nold3"));
        assertTrue(result.content().contains("@@DIF +1 -1"));
        assertTrue(result.content().contains("@@DIF -  2|old2"));
    }

    // ==================== 大 diff 截断 ====================

    @Test
    void largeEditIsTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("line").append(i).append('\n');
        }
        String old = sb.toString();
        StringBuilder nb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            nb.append("LINE").append(i).append('\n');
        }
        String s = DiffView.editSummary("big.txt", old,
                old.strip(), nb.toString().strip());
        List<String> lines = List.of(s.split("\n", -1));
        assertEquals("@@DIF +40 -40", lines.get(0)); // 统计行完整
        assertTrue(lines.get(lines.size() - 1).startsWith("… 还有")); // 尾部省略提示
        assertEquals(DiffView.MAX_DIFF_LINES + 1, lines.size()); // 统计 + 截断后的 diff 体
    }

    private static boolean containsLine(String[] lines, String expected) {
        for (String line : lines) {
            if (line.equals(expected)) {
                return true;
            }
        }
        return false;
    }
}
