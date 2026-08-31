package dinocode.instructions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目指令加载单测（ch09 AC1~AC6）：三层合并、@include 展开/深度/环路/逃逸/二进制。
 */
class LoaderTest {

    @TempDir
    Path root;

    @TempDir
    Path fakeHome;

    private Loader loader() {
        return Loader.withUserHome(root, fakeHome);
    }

    private void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    @Test
    void threeLayersMergedProjectFirst() throws Exception {
        write(root.resolve("MEWCODE.md"), "项目根指令");
        write(root.resolve(".dino/MEWCODE.md"), "配置级指令");
        write(fakeHome.resolve(".dino/MEWCODE.md"), "用户级指令");

        String text = loader().load();
        // AC1：三份都在，项目根在最前
        assertTrue(text.indexOf("项目根指令") < text.indexOf("配置级指令"));
        assertTrue(text.indexOf("配置级指令") < text.indexOf("用户级指令"));
    }

    @Test
    void missingLayersSilentlySkipped() throws Exception {
        write(root.resolve("MEWCODE.md"), "只有项目根");
        String text = loader().load(); // AC2
        assertTrue(text.contains("只有项目根"));
        assertFalse(text.contains("用户级"));
    }

    @Test
    void allMissingGivesEmpty() {
        assertTrue(loader().load().isEmpty());
    }

    @Test
    void includeExpandsRelativePath() throws Exception {
        write(root.resolve("MEWCODE.md"), "顶部\n@include rules/style.md\n底部");
        write(root.resolve("rules/style.md"), "使用 4 空格缩进");

        String text = loader().load(); // AC3
        assertTrue(text.contains("使用 4 空格缩进"));
        assertTrue(text.contains("顶部"));
        assertFalse(text.contains("@include"));
    }

    @Test
    void includeBeyondFiveLevelsSkipped() throws Exception {
        // 构造 6 层链：1→2→3→4→5→6
        write(root.resolve("MEWCODE.md"), "@include l2.md");
        for (int i = 2; i <= 5; i++) {
            write(root.resolve("l" + i + ".md"), "@include l" + (i + 1) + ".md");
        }
        write(root.resolve("l6.md"), "第六层内容");

        String text = loader().load(); // AC4：第 6 层不展开
        assertFalse(text.contains("第六层内容"));
        assertTrue(text.contains("超过最大嵌套深度"));
    }

    @Test
    void includeCycleDetected() throws Exception {
        write(root.resolve("MEWCODE.md"), "@include a.md");
        write(root.resolve("a.md"), "A 内容\n@include b.md");
        write(root.resolve("b.md"), "B 内容\n@include a.md");

        String text = loader().load(); // AC5
        assertTrue(text.contains("A 内容"));
        assertTrue(text.contains("B 内容"));
        assertTrue(text.contains("检测到环路"));
    }

    @Test
    void includeEscapingBoundarySkipped() throws Exception {
        write(root.resolve("MEWCODE.md"), "@include ../../outside.md");
        write(root.getParent().getParent().resolve("outside.md"), "越界内容");

        String text = loader().load(); // AC6
        assertFalse(text.contains("越界内容"));
        assertTrue(text.contains("路径超出允许范围"));
    }

    @Test
    void binaryIncludeSkipped() throws Exception {
        write(root.resolve("MEWCODE.md"), "@include blob.bin");
        Files.write(root.resolve("blob.bin"), new byte[]{0x00, 0x01, 0x02});

        String text = loader().load();
        assertTrue(text.contains("二进制文件不可读"));
    }

    @Test
    void nonStandaloneIncludeKeptAsIs() throws Exception {
        write(root.resolve("MEWCODE.md"), "请参考 @include 语法说明");

        String text = loader().load(); // 非独占行不展开（F2）
        assertTrue(text.contains("@include 语法说明"));
    }

    @Test
    void missingIncludeTargetSilentlySkipped() throws Exception {
        write(root.resolve("MEWCODE.md"), "@include no-such-file.md\n正文");
        String text = loader().load();
        assertTrue(text.contains("正文"));
    }
}
