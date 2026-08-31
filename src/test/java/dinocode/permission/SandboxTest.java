package dinocode.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径沙箱单测（ch06 F2/N2/AC2）：符号链接解析、祖先回退、前缀按段比对。
 */
class SandboxTest {

    @TempDir
    Path root;

    @Test
    void allowsPathsWithinRoot() throws IOException {
        Files.writeString(root.resolve("a.txt"), "x");
        Files.createDirectories(root.resolve("sub/dir"));
        Files.writeString(root.resolve("sub/dir/b.txt"), "x");

        assertTrue(Sandbox.ok(root.toString().isEmpty() ? root : Sandbox.resolveRoot(root), "a.txt"));
        assertTrue(Sandbox.ok(Sandbox.resolveRoot(root), "sub/dir/b.txt"));
        assertTrue(Sandbox.ok(Sandbox.resolveRoot(root), ""));
        assertTrue(Sandbox.ok(Sandbox.resolveRoot(root), "."));
    }

    @Test
    void allowsNewFilesWithMissingParentDirs() throws IOException {
        // root 内含尚未创建的多级中间目录的新建文件（祖先回退分支，AC2）
        Path resolved = Sandbox.resolveRoot(root);
        assertTrue(Sandbox.ok(resolved, "new/dir/deep/file.txt"));
    }

    @Test
    void deniesPathsOutsideRoot() throws IOException {
        Path resolved = Sandbox.resolveRoot(root);
        assertFalse(Sandbox.ok(resolved, "/etc/passwd"));
        assertFalse(Sandbox.ok(resolved, "../outside.txt"));
        assertFalse(Sandbox.ok(resolved, "../../etc/passwd"));
    }

    @Test
    void deniesSymlinkPointingOutside() throws IOException {
        Path resolved = Sandbox.resolveRoot(root);
        Path outside = Files.createTempDirectory("dino-outside");
        Path link = root.resolve("escape-link");
        Files.createSymbolicLink(link, outside);

        assertFalse(Sandbox.ok(resolved, "escape-link/file.txt"), "软链接指向项目外应被拒（先解析再比对）");
    }

    @Test
    void resolvesSymlinkInsideRoot() throws IOException {
        Path resolved = Sandbox.resolveRoot(root);
        Files.createDirectories(root.resolve("real"));
        Files.createSymbolicLink(root.resolve("alias"), root.resolve("real"));

        assertTrue(Sandbox.ok(resolved, "alias/file.txt"), "指向项目内的软链接放行");
    }

    @Test
    void prefixComparisonIsSegmentBased() {
        // Path.startsWith 按段比对：<root>ab 不应命中 <root>/a 前缀
        Path a = Path.of("/data/proj");
        assertFalse(a.startsWith(Path.of("/data/pro")));
    }
}
