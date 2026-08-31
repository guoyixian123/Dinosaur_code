package dinocode.skill;

import dinocode.core.Message;
import dinocode.tool.Result;
import dinocode.tool.Tool;
import dinocode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill 系统单测（ch11 AC：两层加载/格式解析/热重载/执行器双模式/上下文注入）。
 */
class SkillTest {

    @TempDir
    Path root;

    private void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    // ---------- T1/T2：解析 ----------

    @Test
    void parsesSkillMdWithFrontmatter() throws Exception {
        write(root.resolve(".dino/skills/demo/SKILL.md"), """
                ---
                name: demo
                description: demo skill
                allowed_tools:
                  - ReadFile
                ---
                这是 SOP 正文。
                """);
        SkillCatalog catalog = new SkillCatalog();
        catalog.loadCatalog(root);

        var skill = catalog.get("demo");
        assertNotNull(skill);
        assertEquals("demo", skill.meta().name());
        assertEquals("demo skill", skill.meta().description());
        assertEquals("inline", skill.meta().mode()); // 缺省 inline（F5）
        assertEquals(List.of("ReadFile"), skill.meta().allowedTools());
        assertTrue(skill.bodyLoaded());
        assertTrue(skill.promptBody().contains("SOP 正文"));
    }

    @Test
    void nameDefaultsToDirNameWhenMissing() throws Exception {
        write(root.resolve(".dino/skills/My Skill/SKILL.md"), "正文一行");
        SkillCatalog catalog = new SkillCatalog();
        catalog.loadCatalog(root);
        // F5：name 缺省取目录名小写、空格换 -
        assertEquals("my-skill", catalog.get("my-skill").meta().name());
    }

    @Test
    void descriptionFallsBackToFirstNonHeadingLine() throws Exception {
        write(root.resolve(".dino/skills/fallback/SKILL.md"), "# 标题\n\n正文第一行做描述");
        SkillCatalog catalog = new SkillCatalog();
        catalog.loadCatalog(root);
        assertEquals("正文第一行做描述", catalog.get("fallback").meta().description());
    }

    @Test
    void badFrontmatterDegradesGracefully() throws Exception {
        write(root.resolve(".dino/skills/bad/SKILL.md"), """
                ---
                name: [broken yaml
                ---
                正文");
                """);
        SkillCatalog catalog = new SkillCatalog();
        catalog.loadCatalog(root); // N3：解析失败降级，不抛
        assertNotNull(catalog.get("bad"));
    }

    @Test
    void yamlAndPromptPairPreferred() throws Exception {
        write(root.resolve(".dino/skills/paired/skill.yaml"), """
                name: paired
                description: yaml 风格
                mode: fork
                fork_context: recent
                """);
        write(root.resolve(".dino/skills/paired/prompt.md"), "fork SOP 正文");
        SkillCatalog catalog = new SkillCatalog();
        catalog.loadCatalog(root);

        var meta = catalog.get("paired").meta();
        assertEquals("yaml 风格", meta.description());
        assertEquals("fork", meta.mode()); // F5
        assertEquals("recent", meta.forkContext());
        // phase-1 不读 body（N2）
        assertFalse(catalog.get("paired").bodyLoaded());
        // getFull 触发 phase-2
        var full = catalog.getFull("paired");
        assertTrue(full.bodyLoaded());
        assertTrue(full.promptBody().contains("fork SOP 正文"));
    }

    // ---------- T3：两层加载与覆盖 ----------

    @Test
    void projectOverridesUserTier() throws Exception {
        write(root.resolve("user-skills/demo/SKILL.md"), "---\nname: demo\ndescription: 用户级\n---\n旧正文");
        write(root.resolve(".dino/skills/demo/SKILL.md"), "---\nname: demo\ndescription: 项目级\n---\n新正文");

        // 模拟用户级：直接 loadFromDirectory 到临时目录再加载项目目录
        SkillCatalog catalog = new SkillCatalog();
        catalog.loadFromDirectory(root.resolve("user-skills")); // 技能目录本身
        catalog.loadFromDirectory(root.resolve(".dino").resolve("skills")); // 项目级后注册 → 覆盖（F2/N6）
        assertEquals("项目级", catalog.get("demo").meta().description());
        assertEquals(1, catalog.list().size());
    }

    @Test
    void getFullRereadsBodyOnDiskChange() throws Exception {
        Path dir = root.resolve(".dino/skills/hot");
        write(dir.resolve("SKILL.md"), "---\nname: hot\n---\n第一版");
        SkillCatalog catalog = new SkillCatalog();
        catalog.loadCatalog(root);
        assertTrue(catalog.getFull("hot").promptBody().contains("第一版"));

        write(dir.resolve("SKILL.md"), "---\nname: hot\n---\n第二版");
        assertTrue(catalog.getFull("hot").promptBody().contains("第二版")); // F4 热更新
    }

    @Test
    void brokenSkillSkippedOthersLoad() throws Exception {
        write(root.resolve(".dino/skills/broken-dir/skill.yaml"), "只有 yaml 没有 prompt.md");
        write(root.resolve(".dino/skills/good/SKILL.md"), "---\nname: good\n---\n正常");
        SkillCatalog catalog = new SkillCatalog();
        catalog.loadCatalog(root); // N1：坏的不中断
        assertNull(catalog.get("broken-dir"));
        assertNotNull(catalog.get("good"));
    }

    // ---------- T5：SkillExecutor ----------

    private static final class RecordingHost implements SkillHost {
        final ToolRegistry registry = new ToolRegistry();
        String activatedName;
        String activatedBody;
        Predicate<String> filter = n -> true;
        final List<Message> parent = List.of(Message.user("p1"), Message.assistant("p2"));

        RecordingHost() {
            registry.register(new Tool() {
                @Override
                public String name() {
                    return "ReadFile";
                }

                @Override
                public String description() {
                    return "";
                }

                @Override
                public Map<String, Object> schema() {
                    return Map.of("type", "object");
                }

                @Override
                public boolean readOnly() {
                    return true;
                }

                @Override
                public Result execute(Map<String, Object> args) {
                    return Result.ok("");
                }
            });
        }

        @Override
        public void activateSkill(String name, String body) {
            activatedName = name;
            activatedBody = body;
        }

        @Override
        public void setToolFilter(Predicate<String> filter) {
            this.filter = filter;
        }

        @Override
        public ToolRegistry toolRegistry() {
            return registry;
        }
    }

    @Test
    void executeInlineActivatesAndFilters() throws Exception {
        write(root.resolve(".dino/skills/inline-skill/SKILL.md"), """
                ---
                name: inline-skill
                allowed_tools:
                  - ReadFile
                ---
                SOP: $ARGUMENTS
                """);
        SkillCatalog catalog = new SkillCatalog();
        catalog.loadCatalog(root);
        RecordingHost host = new RecordingHost();

        String body = SkillExecutor.executeInline(catalog.getFull("inline-skill"), "帮我读文件", host);

        assertEquals("inline-skill", host.activatedName);
        assertEquals("SOP: 帮我读文件", body); // F8 占位替换
        assertEquals("SOP: 帮我读文件", host.activatedBody);
        assertTrue(host.filter.test("ReadFile"));
        assertFalse(host.filter.test("Bash")); // 白名单过滤生效
    }

    @Test
    void inlineWithoutPlaceholderAppendsUserRequest() {
        String body = "SOP 步骤";
        String out = SkillExecutor.substituteArguments(body, "额外请求");
        assertTrue(out.contains("SOP 步骤"));
        assertTrue(out.contains("## User Request"));
        assertTrue(out.contains("额外请求"));
        // args 空：原样
        assertEquals(body, SkillExecutor.substituteArguments(body, "  "));
    }

    @Test
    void assertAllowedToolsExistThrowsOnUnknownTool() throws Exception {
        write(root.resolve(".dino/skills/badtool/SKILL.md"), """
                ---
                name: badtool
                allowed_tools:
                  - NoSuchTool
                ---
                x
                """);
        SkillCatalog catalog = new SkillCatalog();
        catalog.loadCatalog(root);
        RecordingHost host = new RecordingHost();
        // N5：执行前暴露配置错误
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SkillExecutor.executeInline(catalog.getFull("badtool"), null, host));
        assertTrue(e.getMessage().contains("NoSuchTool"));
    }

    // ---------- F9：fork 种子 ----------

    @Test
    void forkSeedModes() {
        List<Message> parent = List.of(
                Message.user("1"), Message.user("2"), Message.user("3"),
                Message.user("4"), Message.user("5"), Message.user("6"));

        assertEquals(6, SkillExecutor.buildForkSeed("full", parent).size());
        assertEquals(5, SkillExecutor.buildForkSeed("recent", parent).size()); // 尾部最多 5
        assertEquals(0, SkillExecutor.buildForkSeed("none", parent).size());
        assertEquals(0, SkillExecutor.buildForkSeed(null, parent).size());
        assertEquals(0, SkillExecutor.buildForkSeed("unknown", parent).size()); // 未知按 none
    }

    @Test
    void executeForkRunsSubAgent() throws Exception {
        write(root.resolve(".dino/skills/forky/skill.yaml"), "name: forky\nmode: fork\nfork_context: recent\n");
        write(root.resolve(".dino/skills/forky/prompt.md"), "fork 任务: $ARGUMENTS");
        SkillCatalog catalog = new SkillCatalog();
        catalog.loadCatalog(root);

        RecordingHost base = new RecordingHost();
        List<Message> seedUsed = new java.util.ArrayList<>();
        String result = SkillExecutor.executeFork(catalog.getFull("forky"), "参数X", new SkillForkHost() {
            @Override
            public String runSubAgent(String body, List<Message> seed, List<String> allowedTools, String model) {
                seedUsed.addAll(seed);
                return body.contains("参数X") ? "fork 完成" : "fail";
            }

            @Override
            public List<Message> snapshotParentMessages() {
                return base.parent;
            }

            @Override
            public void activateSkill(String name, String body) {
            }

            @Override
            public void setToolFilter(Predicate<String> filter) {
            }

            @Override
            public ToolRegistry toolRegistry() {
                return base.registry;
            }
        });
        assertEquals("fork 完成", result);
        assertEquals(2, seedUsed.size()); // recent 种子来自父快照
    }

    // ---------- T6：buildActiveContext ----------

    @Test
    void buildActiveContextRendersSections() throws Exception {
        write(root.resolve(".dino/skills/ctx/SKILL.md"), "---\nname: ctx\n---\n技能正文");
        SkillCatalog catalog = new SkillCatalog();
        catalog.loadCatalog(root);

        assertEquals("", catalog.buildActiveContext(Set.of())); // 空集合空串
        String ctx = catalog.buildActiveContext(new HashSet<>(List.of("ctx")));
        assertTrue(ctx.contains("## Active Skills"));
        assertTrue(ctx.contains("### ctx"));
        assertTrue(ctx.contains("技能正文"));
    }
}
