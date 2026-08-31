package dinocode.permission;

import dinocode.core.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎流水线单测（ch06 AC1/AC2/AC5/AC7/AC8/AC15/AC18）。
 */
class PermissionEngineTest {

    @TempDir
    Path root;

    private static ToolCall bash(String command) {
        return new ToolCall("t", "Bash", "{\"command\":\"" + command + "\"}");
    }

    private static ToolCall writeFile(String path) {
        return new ToolCall("t", "WriteFile", "{\"path\":\"" + path + "\"}");
    }

    private static ToolCall readFile(String path) {
        return new ToolCall("t", "ReadFile", "{\"path\":\"" + path + "\"}");
    }

    // ---------- 黑名单层（AC1/N1） ----------

    @Test
    void blacklistDeniesEvenInBypassMode() {
        PermissionEngine engine = PermissionEngine.create(root);

        PermissionEngine.CheckResult r = engine.check(Mode.BYPASS, bash("rm -rf /"), false);
        assertEquals(Decision.DENY, r.decision());
        assertTrue(r.reason().contains("黑名单"));
    }

    @Test
    void benignCommandNotBlockedByBlacklist() {
        PermissionEngine engine = PermissionEngine.create(root);
        assertEquals(Decision.ASK, engine.check(Mode.DEFAULT, bash("git status"), false).decision());
    }

    // ---------- 沙箱层（AC2/N2） ----------

    @Test
    void sandboxDeniesOutsidePathsForFileTools() throws IOException {
        PermissionEngine engine = PermissionEngine.create(root);

        assertEquals(Decision.DENY, engine.check(Mode.BYPASS, writeFile("/etc/passwd"), false).decision());
        assertEquals(Decision.DENY, engine.check(Mode.BYPASS, writeFile("../outside.txt"), false).decision());
        assertEquals(Decision.DENY, engine.check(Mode.BYPASS, readFile("/etc/passwd"), true).decision());

        // 项目内新文件（含未创建中间目录）放行（bypass 模式）
        assertEquals(Decision.ALLOW,
                engine.check(Mode.BYPASS, writeFile("new/dir/file.txt"), false).decision());
    }

    // ---------- 规则引擎（AC3/AC5） ----------

    @Test
    void rulesTakePrecedenceOverModeFallback() {
        PermissionEngine engine = PermissionEngine.forTest(Sandbox.rootUnchecked(root),
                new RuleSet(), new RuleSet(), // user 空
                new RuleSet(
                        List.of(Rule.parse("Bash(git status)", true).orElseThrow()),
                        List.of()));
        // allow 规则命中 → 直接放行，不进模式兜底（default 下 EXEC 应 Ask）
        assertEquals(Decision.ALLOW, engine.check(Mode.DEFAULT, bash("git status"), false).decision());
        assertEquals(Decision.ASK, engine.check(Mode.DEFAULT, bash("git push"), false).decision());
    }

    @Test
    void denyRuleBeatsAllowRuleAndMode() {
        PermissionEngine engine = PermissionEngine.forTest(Sandbox.rootUnchecked(root),
                new RuleSet(),
                new RuleSet(),
                new RuleSet(
                        List.of(Rule.parse("Bash(git *)", true).orElseThrow()),
                        List.of(Rule.parse("Bash(git push --force)", false).orElseThrow())));

        assertEquals(Decision.DENY, engine.check(Mode.BYPASS, bash("git push --force"), false).decision());
        assertEquals(Decision.ALLOW, engine.check(Mode.DEFAULT, bash("git pull"), false).decision());
    }

    @Test
    void localBeatsProjectBeatsUser() {
        // 本地 deny 盖项目 allow；项目 deny 盖用户 allow
        PermissionEngine engine = PermissionEngine.forTest(Sandbox.rootUnchecked(root),
                new RuleSet(List.of(Rule.parse("Bash(git *)", true).orElseThrow()), List.of()),
                new RuleSet(List.of(), List.of(Rule.parse("Bash(git status)", false).orElseThrow())),
                new RuleSet(List.of(), List.of(Rule.parse("Bash(git push)", false).orElseThrow())));

        // 本地 deny(git push) 命中 → DENY（即使用户层 allow git *）
        assertEquals(Decision.DENY, engine.check(Mode.BYPASS, bash("git push"), false).decision());
        // 项目 deny(git status) 命中 → DENY
        assertEquals(Decision.DENY, engine.check(Mode.BYPASS, bash("git status"), false).decision());
        // 都未命中（git log 不在本地/项目 deny 中）→ 用户层 allow(git *) → ALLOW
        assertEquals(Decision.ALLOW, engine.check(Mode.DEFAULT, bash("git log"), false).decision());
    }

    // ---------- 模式矩阵（AC7） ----------

    @Test
    void modeFallbackMatrix() {
        assertEquals(Decision.ALLOW, PermissionEngine.modeFallback(Mode.DEFAULT, Category.READ));
        assertEquals(Decision.ASK, PermissionEngine.modeFallback(Mode.DEFAULT, Category.WRITE));
        assertEquals(Decision.ASK, PermissionEngine.modeFallback(Mode.DEFAULT, Category.EXEC));

        assertEquals(Decision.ALLOW, PermissionEngine.modeFallback(Mode.ACCEPT_EDITS, Category.WRITE));
        assertEquals(Decision.ASK, PermissionEngine.modeFallback(Mode.ACCEPT_EDITS, Category.EXEC));

        assertEquals(Decision.ASK, PermissionEngine.modeFallback(Mode.PLAN, Category.WRITE));
        assertEquals(Decision.ASK, PermissionEngine.modeFallback(Mode.PLAN, Category.EXEC));

        assertEquals(Decision.ALLOW, PermissionEngine.modeFallback(Mode.BYPASS, Category.WRITE));
        assertEquals(Decision.ALLOW, PermissionEngine.modeFallback(Mode.BYPASS, Category.EXEC));
    }

    // ---------- 安全默认（AC15） ----------

    @Test
    void unknownToolFallsToAskNotAllow() {
        PermissionEngine engine = PermissionEngine.create(root);
        // 未知工具按 EXEC 处理 → default 模式 Ask；bypass 下允许（N7 安全默认）
        ToolCall unknown = new ToolCall("t", "Mystery", "{\"x\":1}");
        assertEquals(Decision.ASK, engine.check(Mode.DEFAULT, unknown, false).decision());
        assertEquals(Decision.ALLOW, engine.check(Mode.BYPASS, unknown, false).decision());
    }

    @Test
    void unparseableFileArgsDeny() {
        PermissionEngine engine = PermissionEngine.create(root);
        ToolCall bad = new ToolCall("t", "WriteFile", "{bad json");
        PermissionEngine.CheckResult r = engine.check(Mode.BYPASS, bad, false);
        assertEquals(Decision.DENY, r.decision());
        assertTrue(r.reason().contains("无法解析"));
    }

    // ---------- create 降级（AC6/AC18） ----------

    @Test
    void createNeverReturnsNullAndStartModeDefaultsToDefault() {
        PermissionEngine engine = PermissionEngine.create(root);
        assertNotNull(engine);
        assertEquals(Mode.DEFAULT, engine.startMode());
    }

    @Test
    void startModeReadsLocalOverProjectOverUser(@TempDir Path dir2) throws IOException {
        // 构造三层目录：root/.dino/settings.yaml（项目）与 settings.local.yaml（本地）
        Path dino = root.resolve(".dino");
        Files.createDirectories(dino);
        Files.writeString(dino.resolve("settings.yaml"), "defaultMode: acceptEdits\n");
        Files.writeString(dino.resolve("settings.local.yaml"), "defaultMode: plan\n");
        assertEquals(Mode.PLAN, PermissionEngine.create(root).startMode()); // 本地盖项目

        Files.delete(dino.resolve("settings.local.yaml"));
        assertEquals(Mode.ACCEPT_EDITS, PermissionEngine.create(root).startMode());
    }

    // ---------- 永久放行（AC10 部分） ----------

    @Test
    void persistLocalAllowWritesRuleAndRecheckAllows() throws IOException {
        PermissionEngine engine = PermissionEngine.create(root);
        ToolCall call = bash("git status");
        assertEquals(Decision.ASK, engine.check(Mode.DEFAULT, call, false).decision());

        engine.persistLocalAllow(call);

        Path local = root.resolve(".dino").resolve("settings.local.yaml");
        assertTrue(Files.exists(local));
        String yaml = Files.readString(local);
        assertTrue(yaml.contains("Bash(git status)"));
        // 内存规则集同步生效
        assertEquals(Decision.ALLOW, engine.check(Mode.DEFAULT, call, false).decision());
        // 重载引擎后仍生效（跨会话）
        assertEquals(Decision.ALLOW, PermissionEngine.create(root).check(Mode.DEFAULT, call, false).decision());
    }

    @Test
    void persistLocalAllowIsIdempotent() throws IOException {
        PermissionEngine engine = PermissionEngine.create(root);
        ToolCall call = bash("git status");
        engine.persistLocalAllow(call);
        engine.persistLocalAllow(call); // 二次不抛错
        Path local = root.resolve(".dino").resolve("settings.local.yaml");
        String yaml = Files.readString(local);
        assertEquals(1, yaml.split("Bash\\(git status\\)", -1).length - 1);
    }

    @Test
    void bashCommandWithGlobCharsIsEscaped() {
        String escaped = dinocode.permission.Persister.escapeGlob("echo a*b?c[d]");
        assertFalse(Rule.matchPattern(escaped, "echo ANYTHING"));
        assertTrue(Rule.matchPattern(escaped, "echo a*b?c[d]"));
    }

    @Test
    void readOnlyBatchDenyDoesNotAsk() {
        PermissionEngine engine = PermissionEngine.create(root);
        // 只读工具在项目外 → DENY（永不 Ask，N3/AC13）
        PermissionEngine.CheckResult r = engine.check(Mode.DEFAULT, readFile("/etc/passwd"), true);
        assertEquals(Decision.DENY, r.decision());
        // 只读工具在项目内 → ALLOW
        assertEquals(Decision.ALLOW, engine.check(Mode.DEFAULT, readFile("a.txt"), true).decision());
    }
}
