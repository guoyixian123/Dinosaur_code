package dinocode.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Agent 级 worktree API（ch14 F10/T6）：无状态静态方法——不动会话单例、不切 cwd、不写持久化。
 * 快速恢复路径 bump mtime 防被 StaleCleanup 误删（N4）。
 */
public final class AgentWorktree {

    private static final Logger LOG = Logger.getLogger(AgentWorktree.class.getName());
    private static final int GIT_TIMEOUT_SECONDS = 60;

    /** 创建结果（F10）。 */
    public record Result(Path worktreePath, String worktreeBranch, String headCommit, Path gitRoot) {
    }

    private AgentWorktree() {
    }

    /**
     * 创建（或快速恢复）子 Agent 专属 worktree。
     * 目录已存在 → 跳过 git worktree add，bump mtime + 读 HEAD（F3/N4）；
     * 未存在 → git worktree add -B 创建 + PostCreationSetup。
     */
    public static Result create(String slug, Path repoRoot, java.util.List<String> symlinkDirs)
            throws IOException {
        SlugValidator.validate(slug);
        Path root = repoRoot.toAbsolutePath().normalize();
        Path wtPath = root.resolve(".dino").resolve("worktrees").resolve(SlugValidator.flatten(slug));
        String branch = SlugValidator.branchName(slug);

        if (Files.isDirectory(wtPath)) {
            // 快速恢复路径（F3）：不跑 git worktree add
            try {
                Files.setLastModifiedTime(wtPath, FileTime.from(Instant.now())); // N4
            } catch (IOException e) {
                LOG.fine(() -> "[worktree] bump mtime failed: " + e.getMessage());
            }
            String head = readHead(wtPath);
            if (head != null) {
                return new Result(wtPath, branch, head, root);
            }
            // 读 HEAD 失败 → 回退完整创建路径
        }

        Files.createDirectories(wtPath.getParent());
        ProcessBuilder pb = new ProcessBuilder("git", "worktree", "add", "-B", branch,
                wtPath.toString(), "HEAD");
        pb.directory(root.toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0"); // F4
        pb.environment().put("GIT_ASKPASS", "");
        Process process = pb.start();
        try {
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git worktree add 超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git worktree add 被中断", e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("git worktree add 失败: " + branch);
        }
        PostCreationSetup.perform(root, wtPath, symlinkDirs);
        String head = readHead(wtPath);
        return new Result(wtPath, branch, head, root);
    }

    /**
     * 删除 worktree + 分支（N2）：必须从 gitRoot 跑 git（在 worktree 内跑会因删自己失败）；
     * worktree remove 与 branch -D 之间 sleep 100ms 等 lockfile（N3）。
     */
    public static boolean remove(Path wtPath, String wtBranch, Path gitRoot) {
        if (gitRoot == null) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "remove",
                    wtPath.toAbsolutePath().toString(), "--force");
            pb.directory(gitRoot.toFile()); // N2：从 gitRoot 跑
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_ASKPASS", "");
            Process process = pb.start();
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            if (process.exitValue() != 0) {
                return false;
            }
            Thread.sleep(100); // N3
            if (wtBranch != null && !wtBranch.isBlank()) {
                ProcessBuilder delBranch = new ProcessBuilder("git", "branch", "-D", wtBranch);
                delBranch.directory(gitRoot.toFile());
                delBranch.environment().put("GIT_TERMINAL_PROMPT", "0");
                delBranch.environment().put("GIT_ASKPASS", "");
                Process branchProcess = delBranch.start();
                branchProcess.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            LOG.fine(() -> "[worktree] remove failed: " + e.getMessage());
            return false;
        }
    }

    /** 隔离 notice（F11）：注入子 Agent prompt 头部，告知文件视图已切换。 */
    public static String buildNotice(String parentCwd, Path worktreeCwd) {
        return """
                你现在工作在一个隔离的 git worktree 中：%s
                原工作目录是：%s
                两个目录的文件相互独立——你在 worktree 中的修改不会影响原目录的文件。
                相对路径请基于 worktree 目录解析；编辑文件前先重新读取 worktree 中的版本，\
                不要沿用原目录的缓存内容。"""
                .formatted(worktreeCwd, parentCwd);
    }

    /** 读 worktree 当前 HEAD SHA；失败返回 null。 */
    static String readHead(Path wtPath) {
        String out = WorktreeManager.runGitQuiet(wtPath, "rev-parse", "HEAD");
        return out == null ? null : out.strip();
    }
}
