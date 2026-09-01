package dinocode.worktree;

import dinocode.tool.Result;
import dinocode.tool.Tool;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话级退出 worktree（ch14 F13/F15/T9）：keep 保留 / remove 删除（带变更保护）。
 */
public final class ExitWorktreeTool implements Tool {

    private final WorktreeManager worktreeManager;
    private final java.nio.file.Path repoRoot;

    public ExitWorktreeTool(WorktreeManager worktreeManager, java.nio.file.Path repoRoot) {
        this.worktreeManager = worktreeManager;
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "ExitWorktree";
    }

    @Override
    public String description() {
        return "退出当前 worktree 会话。action=keep 保留 worktree 与分支；action=remove 删除它们"
                + "（有未提交变更时需 discard_changes=true 强制删除）。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", Map.of(
                "type", "string",
                "description", "keep = 保留 worktree；remove = 删除 worktree 与分支",
                "enum", List.of("keep", "remove")));
        props.put("discard_changes", Map.of("type", "boolean",
                "description", "remove 时若有未提交变更，必须显式传 true 才能强删"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("action"));
        return schema;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(Map<String, Object> args) {
        WorktreeSession session = WorktreeSessionStore.getCurrentSession();
        if (session == null) {
            return Result.error("No-op: there is no active EnterWorktree session to exit. "
                    + "This tool only operates on worktrees created by EnterWorktree in the current session.");
        }
        String action = args.get("action") == null ? "" : String.valueOf(args.get("action")).strip().toLowerCase();
        boolean discard = Boolean.TRUE.equals(args.get("discard_changes"))
                || "true".equalsIgnoreCase(String.valueOf(args.get("discard_changes")));

        // F13 变更保护：remove 且未显式丢弃时检查
        if ("remove".equals(action) && !discard) {
            WorktreeChanges.ChangeSummary summary = WorktreeChanges.countChanges(
                    Path.of(session.worktreePath()), session.originalHeadCommit());
            if (summary == null) {
                return Result.error("Could not verify worktree state. "
                        + "Refusing to remove without explicit confirmation. "
                        + "Re-invoke with discard_changes: true, or use action: \"keep\".");
            }
            if (summary.changedFiles() > 0 || summary.commits() > 0) {
                String msg = "Worktree has "
                        + summary.changedFiles() + (summary.changedFiles() == 1 ? " file" : " files")
                        + " and "
                        + summary.commits() + (summary.commits() == 1 ? " commit" : " commits")
                        + " not in the original HEAD. "
                        + "Re-invoke with discard_changes: true to delete anyway, or use action: \"keep\".";
                return Result.error(msg); // N7：具体数字 + 单复数
            }
        }

        String originalCwd = session.originalCwd();
        String path = session.worktreePath();
        // 清单例 + 删持久化（save 失败 swallow）
        WorktreeSessionStore.restoreSession(null);
        try {
            WorktreeSessionStore.save(repoRoot, null);
        } catch (Exception ignored) {
            // 持久化清理失败不影响退出（N8：关键信息已通过返回文本传达）
        }

        if ("remove".equals(action)) {
            boolean removed = worktreeManager.remove(session.worktreeBranch());
            if (!removed) {
                return Result.error("移除 worktree 失败: " + path);
            }
            return Result.ok("Exited and removed worktree at " + path
                    + ". Session is now back in " + originalCwd + ".");
        }
        // keep（含未知 action 的缺省行为）
        return Result.ok("Exited worktree. Your work is preserved at " + path
                + ". Session is now back in " + originalCwd + ".");
    }
}
