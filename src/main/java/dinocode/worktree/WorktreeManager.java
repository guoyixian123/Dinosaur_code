package dinocode.worktree;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * worktree 管理器（ch14 F4/F5/T2~T3）：git worktree add/remove 的统一安全壳。
 * 所有 git 子进程禁终端交互 + 超时保护（F4）；公开方法 synchronized（N1）。
 */
public final class WorktreeManager {

    /** 底层创建结果（F5）。 */
    public record WorktreeInfo(Path path, String branch, Instant createdAt) {
    }

    private static final int GIT_TIMEOUT_SECONDS = 60;

    private final Path projectRoot;
    private final List<String> symlinkDirs;
    private final int staleCutoffHours;
    private final Map<String, WorktreeInfo> worktrees = new LinkedHashMap<>();

    public WorktreeManager(Path projectRoot, List<String> symlinkDirs, int staleCutoffHours) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.symlinkDirs = symlinkDirs == null ? List.of() : symlinkDirs;
        this.staleCutoffHours = staleCutoffHours > 0 ? staleCutoffHours : 24;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public List<String> getSymlinkDirs() {
        return symlinkDirs;
    }

    /**
     * 创建 worktree（F5）：branch 为唯一 key；targetDir 缺省 {@code <root>/.mewcode/worktrees/<branch>}；
     * 用大写 -B 容忍上次未清干净的孤儿分支；成功后跑创建后四项设置（F6）。
     */
    public synchronized WorktreeInfo create(String branch, Path targetDir) throws IOException {
        Path wtDir = targetDir != null
                ? targetDir.toAbsolutePath().normalize()
                : projectRoot.resolve(".dino").resolve("worktrees").resolve(branch);
        Files.createDirectories(wtDir.getParent());
        runGit(projectRoot, "worktree", "add", "-B", branch, wtDir.toString(), "HEAD");
        WorktreeInfo info = new WorktreeInfo(wtDir, branch, Instant.now());
        worktrees.put(branch, info);
        PostCreationSetup.perform(projectRoot, wtDir, symlinkDirs);
        return info;
    }

    /** 删除 worktree（不在 worktree 内跑 git，N2）。 */
    public synchronized boolean remove(String branch) {
        WorktreeInfo info = worktrees.remove(branch);
        if (info == null) {
            return false;
        }
        try {
            runGit(projectRoot, "worktree", "remove", info.path().toString(), "--force");
            Thread.sleep(100); // N3：等 git lockfile 释放
            runGit(projectRoot, "branch", "-D", branch);
            return true;
        } catch (IOException e) {
            System.err.println("[worktree] remove failed: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 优先解析 git worktree list --porcelain，失败回退内存 map（T3）。 */
    public synchronized List<WorktreeInfo> list() {
        String out = runGitQuiet(projectRoot, "worktree", "list", "--porcelain");
        if (out != null) {
            return parsePorcelain(out);
        }
        return List.copyOf(worktrees.values());
    }

    /** porcelain 输出按空行分块解析（T3）。 */
    static List<WorktreeInfo> parsePorcelain(String out) {
        List<WorktreeInfo> result = new java.util.ArrayList<>();
        Path path = null;
        String branch = null;
        for (String line : out.split("\n")) {
            if (line.isBlank()) {
                if (path != null && branch != null) {
                    result.add(new WorktreeInfo(path, branch, Instant.EPOCH));
                }
                path = null;
                branch = null;
                continue;
            }
            if (line.startsWith("worktree ")) {
                path = Path.of(line.substring("worktree ".length()));
            } else if (line.startsWith("branch ")) {
                branch = line.substring("branch ".length()).strip();
            }
        }
        if (path != null && branch != null) {
            result.add(new WorktreeInfo(path, branch, Instant.EPOCH));
        }
        return result;
    }

    // ---------- git 进程安全壳（F4/T2） ----------

    /** 同步跑 git 并返回 stdout；非 0 退出或失败抛 IOException。 */
    static String runGit(Path dir, String... args) throws IOException {
        String out = runGitQuiet(dir, args);
        if (out == null) {
            throw new IOException("git 命令失败: " + String.join(" ", args));
        }
        return out;
    }

    /** 静默版：失败返回 null（调用方按需降级）。 */
    static String runGitQuiet(Path dir, String... args) {
        try {
            java.util.List<String> argv = new java.util.ArrayList<>(args.length + 1);
            argv.add("git");
            argv.addAll(java.util.Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(dir.toFile());
            pb.environment().put("GIT_TERMINAL_PROMPT", "0"); // F4：绝不挂起等输入
            pb.environment().put("GIT_ASKPASS", "");
            // ProcessRunner 边读边等：git ls-files 等输出超 64KB 管道缓冲时不会挂满超时
            dinocode.tool.ProcessRunner.Output out =
                    dinocode.tool.ProcessRunner.run(pb, GIT_TIMEOUT_SECONDS);
            if (out.timedOut() || out.exitCode() != 0) {
                return null;
            }
            return out.stdout();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
