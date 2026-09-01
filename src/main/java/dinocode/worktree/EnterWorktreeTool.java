package dinocode.worktree;

import dinocode.tool.Result;
import dinocode.tool.Tool;
import dinocode.tool.ToolRegistry;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话级进入 worktree（ch14 F15/T8）：LLM 通过此工具让整个会话切进隔离工作区。
 */
public final class EnterWorktreeTool implements Tool {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorktreeManager worktreeManager;
    private final String sessionId;

    public EnterWorktreeTool(WorktreeManager worktreeManager, String sessionId) {
        this.worktreeManager = worktreeManager;
        this.sessionId = sessionId;
    }

    @Override
    public String name() {
        return "EnterWorktree";
    }

    @Override
    public String description() {
        return "进入一个隔离的 git worktree 工作（创建独立分支与工作目录，互不影响原目录）。"
                + "适合大规模重构、实验性修改。完成后用 ExitWorktree 退出。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of("type", "string",
                "description", "worktree 名称（可选，字母数字._-，≤64 字符；缺省自动生成）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        return schema;
    }

    @Override
    public boolean readOnly() {
        return false; // 会创建分支与目录
    }

    @Override
    public Result execute(Map<String, Object> args) {
        if (WorktreeSessionStore.getCurrentSession() != null) {
            return Result.error("Already in a worktree session");
        }
        String name = args.get("name") == null ? null : String.valueOf(args.get("name")).strip();
        if (name == null || name.isEmpty()) {
            name = "wt-" + Integer.toHexString(RANDOM.nextInt());
        }
        try {
            SlugValidator.validate(name);
            WorktreeManager.WorktreeInfo info = worktreeManager.create(
                    SlugValidator.branchName(name), null);
            // 记录创建时的 HEAD（变更保护 countChanges 的基准，F13）
            String originalHead = WorktreeManager.runGitQuiet(
                    worktreeManager.getProjectRoot(), "rev-parse", "HEAD");
            String originalBranch = WorktreeManager.runGitQuiet(
                    worktreeManager.getProjectRoot(), "rev-parse", "--abbrev-ref", "HEAD");
            WorktreeSession session = new WorktreeSession(
                    System.getProperty("user.dir"), info.path().toString(), name,
                    info.branch(),
                    originalBranch == null ? "" : originalBranch.strip(),
                    originalHead == null ? "" : originalHead.strip(),
                    sessionId, 0);
            WorktreeSessionStore.restoreSession(session);
            WorktreeSessionStore.save(worktreeManager.getProjectRoot(), session);
            return Result.ok("Created worktree at " + info.path() + " on branch " + info.branch()
                    + ". The session is now working in the worktree. "
                    + "Use ExitWorktree to leave mid-session.");
        } catch (IllegalArgumentException e) {
            return Result.error("worktree 名称非法: " + e.getMessage());
        } catch (Exception e) {
            return Result.error("创建 worktree 失败: " + e.getMessage());
        }
    }
}
