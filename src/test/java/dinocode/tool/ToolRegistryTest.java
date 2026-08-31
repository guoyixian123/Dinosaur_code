package dinocode.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 6 个工具 + 注册中心单测（AC1–AC6）。
 */
class ToolRegistryTest {

    @TempDir
    Path tmp;

    @Test
    void definitionsReturnsSixOrdered() {
        ToolRegistry registry = ToolRegistry.createDefault();
        assertEquals(6, registry.definitions().size());
        assertEquals(List.of("ReadFile", "WriteFile", "EditFile", "Bash", "Glob", "Grep"),
                registry.definitions().stream().map(d -> d.name()).toList());
        assertTrue(registry.get("ReadFile").isPresent());
        assertTrue(registry.get("不存在的工具").isEmpty());
    }

    @Test
    void readFileExists() throws IOException {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "第一行\n第二行");
        Result r = new ReadFileTool().execute(Map.of("path", f.toString()));
        assertFalse(r.isError(), r.content());
        assertTrue(r.content().contains("第一行"), r.content());
        assertTrue(r.content().contains("第二行"), r.content());
    }

    @Test
    void readFileMissing() {
        Result r = new ReadFileTool().execute(Map.of("path", tmp.resolve("不存在.txt").toString()));
        assertTrue(r.isError());
        assertTrue(r.content().contains("文件不存在"), r.content());
    }

    @Test
    void readFileDirectory() {
        Result r = new ReadFileTool().execute(Map.of("path", tmp.toString()));
        assertTrue(r.isError());
        assertTrue(r.content().contains("不是文件"), r.content());
    }

    @Test
    void writeFileNestedDir() throws IOException {
        Path target = tmp.resolve("a/b/c.txt");
        Result r = new WriteFileTool().execute(Map.of("path", target.toString(), "content", "你好"));
        assertFalse(r.isError(), r.content());
        assertEquals("你好", Files.readString(target));
    }

    @Test
    void editFileZero() throws IOException {
        Path f = tmp.resolve("e.txt");
        Files.writeString(f, "hello world");
        Result r = new EditFileTool().execute(Map.of(
                "path", f.toString(), "old_string", "不存在", "new_string", "x"));
        assertTrue(r.isError());
        assertTrue(r.content().contains("未找到匹配"), r.content());
    }

    @Test
    void editFileUnique() throws IOException {
        Path f = tmp.resolve("e.txt");
        Files.writeString(f, "hello world");
        Result r = new EditFileTool().execute(Map.of(
                "path", f.toString(), "old_string", "world", "new_string", "dino"));
        assertFalse(r.isError(), r.content());
        assertEquals("hello dino", Files.readString(f));
    }

    @Test
    void editFileMultiple() throws IOException {
        Path f = tmp.resolve("e.txt");
        Files.writeString(f, "a b a b a b");
        Result r = new EditFileTool().execute(Map.of(
                "path", f.toString(), "old_string", "b", "new_string", "x"));
        assertTrue(r.isError());
        assertTrue(r.content().contains("匹配到 3 处"), r.content());
    }

    @Test
    void bashEcho() {
        Result r = new BashTool().execute(Map.of("command", "echo hi"));
        assertFalse(r.isError(), r.content());
        assertTrue(r.content().contains("hi"), r.content());
    }

    @Test
    void bashTimeout() {
        BashTool tool = new BashTool(Duration.ofMillis(300));
        Result r = tool.execute(Map.of("command", "sleep 5"));
        assertTrue(r.isError());
        assertTrue(r.content().contains("命令超时"), r.content());
    }

    @Test
    void globStarStarJava() throws IOException {
        Path java = tmp.resolve("a/b/Foo.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "x");
        Path txt = tmp.resolve("c/Bar.txt");
        Files.createDirectories(txt.getParent());
        Files.writeString(txt, "x");

        Result r = new GlobTool().execute(Map.of("pattern", "**/*.java", "path", tmp.toString()));
        assertFalse(r.isError(), r.content());
        assertTrue(r.content().contains("a/b/Foo.java"), r.content());
        assertFalse(r.content().contains("Bar.txt"), r.content());
    }

    @Test
    void grepKeyword() throws IOException {
        Path f = tmp.resolve("src/X.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "line1\n目标词 here\nline3");

        Result r = new GrepTool().execute(Map.of("pattern", "目标词", "path", tmp.toString()));
        assertFalse(r.isError(), r.content());
        assertTrue(r.content().contains("src/X.java:2:目标词 here"), r.content());
    }

    @Test
    void truncateCapsLinesAndBytes() {
        String lines = Truncate.byLinesAndBytes("a\nb\nc\nd\ne\n", 3, 1024);
        assertTrue(lines.contains("[truncated]"), lines);
        assertTrue(lines.startsWith("a\nb\nc\n"), lines);

        String bytes = Truncate.byLinesAndBytes("abcdefghij", 100, 5);
        assertTrue(bytes.contains("[truncated]"), bytes);
        assertTrue(bytes.startsWith("abcde"), bytes);
    }
}
