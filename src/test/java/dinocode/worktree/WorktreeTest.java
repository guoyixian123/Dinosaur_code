package dinocode.worktree;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worktree 系统单测（ch14 T13/AC 对应）：真实 git 仓库驱动（TempDir + git init）。
 */
class WorktreeTest {

    @TempDir
    Path repoRoot;

    @BeforeEach
    void initGitRepo() throws Exception {
        // 准备最小 git 仓库：需要至少一个 commit 才能 worktree add
        git(repoRoot, "init");
        git(repoRoot, "config", "user.email", "t@t");
        git(repoRoot, "config", "user.name", "t");
        Files.writeString(repoRoot.resolve("README.md"), "base");
        git(repoRoot, "add", ".");
        git(repoRoot, "commit", "-m", "init");
    }

    private static String git(Path dir, String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                prepend("git", args));
        pb.directory(dir.toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        p.waitFor();
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: "
                    + new String(p.getErrorStream().readAllBytes()));
        }
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String[] prepend(String head, String... rest) {
        String[] out = new String[rest.length + 1];
        out[0] = head;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    // ---------- T1：Slug 校验 ----------

    @Test
    void slugValidation() {
        SlugValidator.validate("demo"); // 合法
        SlugValidator.validate("a/b/c"); // 嵌套合法
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate(""));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("x".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("a/../b")); // 路径遍历
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("a/./b"));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("bad name!"));
        assertEquals("a+b+c", SlugValidator.flatten("a/b/c")); // F2
        assertEquals("worktree-demo", SlugValidator.branchName("demo"));
    }

    // ---------- T3/T6：创建与删除（真实 git） ----------

    @Test
    void agentWorktreeCreateAndRemove() throws Exception {
        var result = AgentWorktree.create("agent-atest123", repoRoot, List.of());
        assertTrue(Files.isDirectory(result.worktreePath()));
        assertTrue(Files.exists(result.worktreePath().resolve("README.md")), "worktree 里有仓库文件");
        assertNotNull(result.headCommit());
        assertEquals("worktree-agent-atest123", result.worktreeBranch());

        // 快速恢复：第二次 create 不重建，HEAD 相同（F3）
        var again = AgentWorktree.create("agent-atest123", repoRoot, List.of());
        assertEquals(result.headCommit(), again.headCommit());

        // 删除
        assertTrue(AgentWorktree.remove(result.worktreePath(), result.worktreeBranch(), repoRoot));
        assertFalse(Files.exists(result.worktreePath()));
    }

    @Test
    void createRejectsBadSlugBeforeGit() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentWorktree.create("../escape", repoRoot, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> AgentWorktree.create("bad slug", repoRoot, List.of()));
    }

    @Test
    void isolationIsRealFilesNotBranchSwitch() throws Exception {
        // 主目录写 witness
        Files.writeString(repoRoot.resolve("witness.txt"), "original");
        git(repoRoot, "add", ".");
        git(repoRoot, "commit", "-m", "with witness");

        var wt = AgentWorktree.create("agent-aiso1234", repoRoot, List.of());
        // 子 Agent 在 worktree 里改文件
        Files.writeString(wt.worktreePath().resolve("witness.txt"), "modified by subagent");

        // 主目录不受影响（路径 A 验证核心：空间隔离）
        assertEquals("original", Files.readString(repoRoot.resolve("witness.txt")));
        assertEquals("modified by subagent",
                Files.readString(wt.worktreePath().resolve("witness.txt")));

        // 变更检测：有变更
        assertTrue(WorktreeChanges.hasChanges(wt.worktreePath(), wt.headCommit()));
        WorktreeChanges.ChangeSummary summary =
                WorktreeChanges.countChanges(wt.worktreePath(), wt.headCommit());
        assertNotNull(summary);
        assertEquals(1, summary.changedFiles());
        assertEquals(0, summary.commits());
    }

    // ---------- T5：变更检测 fail-closed ----------

    @Test
    void cleanWorktreeReportsNoChanges() throws Exception {
        var wt = AgentWorktree.create("agent-aclean01", repoRoot, List.of());
        assertFalse(WorktreeChanges.hasChanges(wt.worktreePath(), wt.headCommit()));
        WorktreeChanges.ChangeSummary summary =
                WorktreeChanges.countChanges(wt.worktreePath(), wt.headCommit());
        assertNotNull(summary);
        assertEquals(0, summary.changedFiles());
        assertEquals(0, summary.commits());
    }

    @Test
    void countChangesReturnsNullWhenUnverifiable() {
        // 不存在的目录 → git 失败 → null（fail-closed，F14）
        assertNull(WorktreeChanges.countChanges(repoRoot.resolve("nonexistent-wt"), "abc123"));
        assertNull(WorktreeChanges.countChanges(repoRoot.resolve("nonexistent-wt"), null));
        // originalHeadCommit 为空 → null
        assertNull(WorktreeChanges.countChanges(repoRoot, ""));
    }

    // ---------- T4：PostCreationSetup ----------

    @Test
    void worktreeIncludeFilesCopied() throws Exception {
        // 写 .env（gitignore 忽略）+ .worktreeinclude 声明需要它
        Files.writeString(repoRoot.resolve(".gitignore"), ".env\n");
        Files.writeString(repoRoot.resolve(".env"), "SECRET=1");
        Files.writeString(repoRoot.resolve(".worktreeinclude"), "# 注释\n.env\n");
        git(repoRoot, "add", ".gitignore");
        git(repoRoot, "add", ".worktreeinclude");
        git(repoRoot, "commit", "-m", "include config");

        var wt = AgentWorktree.create("agent-ainc1234", repoRoot, List.of());
        // .env 被忽略但按 .worktreeinclude 复制进了 worktree（F6④）
        assertTrue(Files.exists(wt.worktreePath().resolve(".env")),
                ".worktreeinclude 声明的文件应被复制");
    }

    // ---------- T7：会话存储 ----------

    @Test
    void sessionStoreRoundTripAndDelete() throws Exception {
        WorktreeSession session = new WorktreeSession(
                "/origin", "/wt/path", "demo", "worktree-demo",
                "main", "abc123", "s-1", 100);
        WorktreeSessionStore.restoreSession(session);
        assertEquals(session, WorktreeSessionStore.getCurrentSession());

        WorktreeSessionStore.save(repoRoot, session);
        assertTrue(Files.exists(WorktreeSessionStore.sessionPath(repoRoot)));

        WorktreeSession loaded = WorktreeSessionStore.load(repoRoot);
        assertNotNull(loaded);
        assertEquals("/wt/path", loaded.worktreePath());
        assertEquals("worktree-demo", loaded.worktreeBranch());
        assertEquals(100, loaded.creationDurationMs());

        // save(null) 等价删文件（F8）
        WorktreeSessionStore.save(repoRoot, null);
        assertFalse(Files.exists(WorktreeSessionStore.sessionPath(repoRoot)));
        assertNull(WorktreeSessionStore.load(repoRoot));

        WorktreeSessionStore.restoreSession(null); // 清全局
        assertNull(WorktreeSessionStore.getCurrentSession());
    }

    @Test
    void sessionStoreLoadMissingReturnsNull() {
        assertNull(WorktreeSessionStore.load(repoRoot));
    }

    // ---------- T9：Enter/Exit 工具 ----------

    @Test
    void enterThenExitWithChangeProtection() throws Exception {
        WorktreeManager manager = new WorktreeManager(repoRoot, List.of(), 24);
        EnterWorktreeTool enter = new EnterWorktreeTool(manager, "s-1");
        ExitWorktreeTool exit = new ExitWorktreeTool(manager, repoRoot);

        // 进入
        var entered = enter.execute(Map.of("name", "demo"));
        assertFalse(entered.isError(), entered.content());
        var session = WorktreeSessionStore.getCurrentSession();
        assertNotNull(session);
        Path wtPath = Path.of(session.worktreePath());

        // 在 worktree 里改文件（未提交）
        Files.writeString(wtPath.resolve("new.txt"), "未提交内容");

        // remove 无 discard → 被变更保护拒绝（F13/AC 保护语义）
        var blocked = exit.execute(Map.of("action", "remove"));
        assertTrue(blocked.isError());
        assertTrue(blocked.content().contains("1 file") || blocked.content().contains("uncommitted"),
                "应报具体数字: " + blocked.content());
        assertTrue(Files.exists(wtPath)); // 未被删

        // discard_changes=true → 强删成功
        var removed = exit.execute(Map.of("action", "remove", "discard_changes", true));
        assertFalse(removed.isError(), removed.content());
        assertFalse(Files.exists(wtPath));
        assertNull(WorktreeSessionStore.getCurrentSession());
    }

    @Test
    void exitWithoutSessionIsNoop() {
        WorktreeSessionStore.restoreSession(null);
        ExitWorktreeTool exit = new ExitWorktreeTool(
                new WorktreeManager(repoRoot, List.of(), 24), repoRoot);
        var result = exit.execute(Map.of("action", "keep"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("no active EnterWorktree session"));
    }

    @Test
    void enterTwiceRejected() throws Exception {
        WorktreeManager manager = new WorktreeManager(repoRoot, List.of(), 24);
        EnterWorktreeTool enter = new EnterWorktreeTool(manager, "s-1");
        assertFalse(enter.execute(Map.of("name", "first")).isError());
        var second = enter.execute(Map.of("name", "second"));
        assertTrue(second.isError());
        assertTrue(second.content().contains("Already in a worktree session"));
        // 清理
        new ExitWorktreeTool(manager, repoRoot).execute(Map.of("action", "remove", "discard_changes", true));
    }

    // ---------- T11：StaleCleanup ----------

    @Test
    void staleCleanupRemovesOldEphemeralCleanWorktrees() throws Exception {
        WorktreeSessionStore.restoreSession(null); // 清静态单例（JUnit 共享 JVM）
        var wt = AgentWorktree.create("agent-a12cd345", repoRoot, List.of()); // 尾段纯 hex（F16 正则要求）
        // 把 mtime 拨到 48 小时前（超过 24h cutoff）
        Files.setLastModifiedTime(wt.worktreePath(),
                java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(48 * 3600)));

        int removed = StaleCleanup.cleanup(repoRoot, Instant.now().minusSeconds(24 * 3600));
        if (removed != 1) {
            // 诊断：三层过滤哪一层留住了它
            System.out.println("[diag] status=" + WorktreeManager.runGitQuiet(wt.worktreePath(),
                    "--no-optional-locks", "status", "--porcelain", "-uno"));
            System.out.println("[diag] ahead=" + WorktreeManager.runGitQuiet(wt.worktreePath(),
                    "rev-list", "--max-count=1", "HEAD", "--not", "--remotes"));
            System.out.println("[diag] exists=" + Files.exists(wt.worktreePath()));
        }
        assertEquals(1, removed);
        assertFalse(Files.exists(wt.worktreePath()));
    }

    @Test
    void staleCleanupSkipsRecentAndUserNamedAndDirty() throws Exception {
        // ① 近期创建的临时 worktree → 保留
        var recent = AgentWorktree.create("agent-anew1234", repoRoot, List.of());
        // ② 用户命名（不匹配临时正则）→ 保留
        var userNamed = AgentWorktree.create("my-feature", repoRoot, List.of());
        // ③ 老但有脏文件 → 保留（L3 fail-closed）
        var dirty = AgentWorktree.create("agent-adir1234", repoRoot, List.of());
        Files.writeString(dirty.worktreePath().resolve("wip.txt"), "未提交");
        Files.setLastModifiedTime(dirty.worktreePath(),
                java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(48 * 3600)));

        int removed = StaleCleanup.cleanup(repoRoot, Instant.now().minusSeconds(24 * 3600));
        assertEquals(0, removed);
        assertTrue(Files.exists(recent.worktreePath()));
        assertTrue(Files.exists(userNamed.worktreePath()));
        assertTrue(Files.exists(dirty.worktreePath()));
    }
}
