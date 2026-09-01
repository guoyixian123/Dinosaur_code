package dinocode.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 孤儿 worktree 后台过期清理（ch14 F16/F17/T11）：三层过滤，fail-closed。
 * 用户手动命名的 worktree 永不清理（L1）。
 */
public final class StaleCleanup {

    private static final Logger LOG = Logger.getLogger(StaleCleanup.class.getName());

    /** 五类临时命名模式（F16）；不匹配 = 用户手动命名 = 永不清理。 */
    private static final List<Pattern> EPHEMERAL_PATTERNS = List.of(
            Pattern.compile("^agent-a[0-9a-f]{7}$"),
            Pattern.compile("^wf_[0-9a-f]{8}-[0-9a-f]{3}-\\d+$"),
            Pattern.compile("^wf-\\d+$"),
            Pattern.compile("^bridge-[A-Za-z0-9_]+(-[A-Za-z0-9_]+)*$"),
            Pattern.compile("^job-[a-zA-Z0-9._-]{1,55}-[0-9a-f]{8}$"));

    private StaleCleanup() {
    }

    static boolean isEphemeral(String slug) {
        return EPHEMERAL_PATTERNS.stream().anyMatch(p -> p.matcher(slug).matches());
    }

    /** 清理孤儿 worktree；返回删除数（三层过滤：命名 → 时态 → git 状态 fail-closed，N5）。 */
    public static int cleanup(Path repoRoot, Instant cutoff) {
        Path dir = repoRoot.toAbsolutePath().resolve(".dino").resolve("worktrees");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        WorktreeSession current = WorktreeSessionStore.getCurrentSession();
        String currentPath = current == null ? null : current.worktreePath();
        int removed = 0;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : (Iterable<Path>) entries.filter(Files::isDirectory)::iterator) {
                String slug = entry.getFileName().toString();
                // L1 命名：用户命名永不删（F16）
                if (!isEphemeral(slug)) {
                    continue;
                }
                // L2 时态：当前会话占用 / mtime 在 cutoff 之后 → 保留
                if (currentPath != null && entry.resolve(".").toAbsolutePath().normalize().toString()
                        .equals(currentPath)) {
                    continue;
                }
                try {
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    if (attrs.lastModifiedTime().toInstant().isAfter(cutoff)) {
                        continue;
                    }
                } catch (IOException e) {
                    continue; // 读属性失败：保守保留
                }
                // L3 git 状态 fail-closed：有未提交变更或未推送 commit → 保留
                String status = WorktreeManager.runGitQuiet(entry,
                        "--no-optional-locks", "status", "--porcelain", "-uno");
                if (status == null || !status.isBlank()) {
                    continue;
                }
                // 无 remote 的仓库不存在「未推送」概念——该子层视为通过
                if (WorktreeManager.runGitQuiet(entry, "remote") == null
                        || WorktreeManager.runGitQuiet(entry, "remote").isBlank()) {
                    // 无 remote：跳过 rev-list 检查
                } else {
                    String ahead = WorktreeManager.runGitQuiet(entry,
                            "rev-list", "--max-count=1", "HEAD", "--not", "--remotes");
                    if (ahead == null || !ahead.isBlank()) {
                        continue;
                    }
                }
                // 三层全过 → 删除
                if (AgentWorktree.remove(entry, SlugValidator.branchName(slug), repoRoot)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            LOG.fine(() -> "[worktree] cleanup 扫描失败: " + e.getMessage());
            return removed;
        }
        if (removed > 0) {
            WorktreeManager.runGitQuiet(repoRoot, "worktree", "prune"); // 同步 git 内部表
        }
        return removed;
    }

    /** 周期清理循环（T11/T12）：intervalSeconds ≤ 0 不启动。 */
    public static void startCleanupLoop(ScheduledExecutorService executor, Path repoRoot,
                                        int intervalSeconds, int cutoffHours) {
        if (intervalSeconds <= 0) {
            return;
        }
        executor.scheduleAtFixedRate(() -> {
            try {
                Instant cutoff = Instant.now().minusSeconds((long) cutoffHours * 3600L);
                cleanup(repoRoot, cutoff);
            } catch (RuntimeException e) {
                LOG.fine(() -> "[worktree] cleanup loop failed: " + e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }
}
