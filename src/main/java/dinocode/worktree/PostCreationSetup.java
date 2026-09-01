package dinocode.worktree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * worktree 创建后设置（ch14 F6/T4）：本地配置复制 / hooks 路径 / 软链接 / .worktreeinclude 复制。
 * 四项全部 best-effort——单项失败只记日志、不中断创建（N6）。
 */
public final class PostCreationSetup {

    private static final Logger LOG = Logger.getLogger(PostCreationSetup.class.getName());

    private PostCreationSetup() {
    }

    /** 依次执行四项设置；任何异常仅记录。 */
    public static void perform(Path repoRoot, Path worktreePath, List<String> symlinkDirs) {
        try {
            copySettingsLocal(repoRoot, worktreePath);
        } catch (Exception e) {
            LOG.fine(() -> "[worktree] copy settings.local failed: " + e.getMessage());
        }
        try {
            configureHooksPath(repoRoot, worktreePath);
        } catch (Exception e) {
            LOG.fine(() -> "[worktree] configure hooksPath failed: " + e.getMessage());
        }
        try {
            symlinkDirectories(repoRoot, worktreePath, symlinkDirs);
        } catch (Exception e) {
            LOG.fine(() -> "[worktree] symlink dirs failed: " + e.getMessage());
        }
        try {
            copyWorktreeIncludeFiles(repoRoot, worktreePath);
        } catch (Exception e) {
            LOG.fine(() -> "[worktree] copy .worktreeinclude files failed: " + e.getMessage());
        }
    }

    /** ① 复制 <repo>/.dino/settings.local.json（本地权限规则，gitignore 的）。 */
    private static void copySettingsLocal(Path repoRoot, Path worktreePath) throws IOException {
        Path src = repoRoot.resolve(".dino").resolve("settings.local.yaml");
        if (!Files.isRegularFile(src)) {
            return; // 不存在静默
        }
        Path dst = worktreePath.resolve(".dino").resolve("settings.local.yaml");
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    /** ② hooks 路径：.husky 优先，回退 .git/hooks；在 worktree 里 git config core.hooksPath。 */
    private static void configureHooksPath(Path repoRoot, Path worktreePath) {
        String hooks = Files.isDirectory(repoRoot.resolve(".husky")) ? ".husky"
                : Files.isDirectory(repoRoot.resolve(".git").resolve("hooks")) ? ".git/hooks" : null;
        if (hooks == null) {
            return;
        }
        String out = WorktreeManager.runGitQuiet(worktreePath, "config", "core.hooksPath", hooks);
        if (out == null) {
            LOG.fine(() -> "[worktree] git config core.hooksPath failed");
        }
    }

    /** ③ 软链接 node_modules 等大目录（跳过含 .. 项、src 不存在、dst 已存在的）。 */
    private static void symlinkDirectories(Path repoRoot, Path worktreePath, List<String> dirs) {
        if (dirs == null) {
            return;
        }
        for (String dir : dirs) {
            if (dir.contains("..")) {
                continue; // 防路径逃逸（F6）
            }
            Path src = repoRoot.resolve(dir);
            Path dst = worktreePath.resolve(dir);
            if (!Files.isDirectory(src) || Files.exists(dst)) {
                continue;
            }
            try {
                Files.createDirectories(dst.getParent());
                Files.createSymbolicLink(dst, src);
            } catch (IOException e) {
                LOG.fine(() -> "[worktree] symlink " + dir + " failed: " + e.getMessage());
            }
        }
    }

    /** ④ .worktreeinclude：gitignore 风格模式列表，复制被忽略但运行需要的文件（.env 等）。 */
    private static void copyWorktreeIncludeFiles(Path repoRoot, Path worktreePath) throws IOException {
        Path includeFile = repoRoot.resolve(".worktreeinclude");
        if (!Files.isRegularFile(includeFile)) {
            return;
        }
        List<String> patterns = Files.readAllLines(includeFile, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
        if (patterns.isEmpty()) {
            return;
        }
        // 列出被 .gitignore 忽略的文件
        String ignored = WorktreeManager.runGitQuiet(repoRoot,
                "ls-files", "--others", "--ignored", "--exclude-standard", "--directory");
        if (ignored == null) {
            return;
        }
        for (String line : ignored.split("\n")) {
            String rel = line.strip();
            if (rel.isEmpty() || rel.endsWith("/")) {
                continue; // 跳目录与空行
            }
            if (!matchesAnyPattern(rel, patterns)) {
                continue;
            }
            Path src = repoRoot.resolve(rel);
            Path dst = worktreePath.resolve(rel);
            if (Files.isRegularFile(src)) {
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 简化匹配：去前导 / 后支持 exact / basename / 目录前缀三种（T4，不引入 gitignore 库）。 */
    static boolean matchesAnyPattern(String relPath, List<String> patterns) {
        String normalized = relPath.startsWith("/") ? relPath.substring(1) : relPath;
        String basename = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        for (String pattern : patterns) {
            String p = pattern.startsWith("/") ? pattern.substring(1) : pattern;
            if (normalized.equals(p) || basename.equals(p) || normalized.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }
}
