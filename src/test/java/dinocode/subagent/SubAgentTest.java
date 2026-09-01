package dinocode.subagent;

import dinocode.core.Message;
import dinocode.core.Role;
import dinocode.core.ToolResult;
import dinocode.tool.Result;
import dinocode.tool.Tool;
import dinocode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SubAgent 系统单测（ch13 T1~T11）。
 */
class SubAgentTest {

    @TempDir
    Path root;

    // ---------- T1：三档 builtin ----------

    @Test
    void builtinSpecsMatchContract() {
        assertEquals(200, SubAgentSpec.GENERAL_PURPOSE.maxTurns());
        assertNull(SubAgentSpec.GENERAL_PURPOSE.systemPromptOverride());

        assertEquals(15, SubAgentSpec.PLAN.maxTurns());
        assertTrue(SubAgentSpec.PLAN.disallowedTools().contains("EditFile"));
        assertTrue(SubAgentSpec.PLAN.disallowedTools().contains("WriteFile"));
        assertNotNull(SubAgentSpec.PLAN.systemPromptOverride());

        assertEquals(30, SubAgentSpec.EXPLORE.maxTurns());
        assertEquals("haiku", SubAgentSpec.EXPLORE.model());
        assertTrue(SubAgentSpec.EXPLORE.disallowedTools().contains("WriteFile"));
    }

    // ---------- T2/T3：Loader ----------

    @Test
    void parseAgentFileFull() throws Exception {
        Path file = root.resolve("reviewer.md");
        Files.writeString(file, """
                ---
                name: reviewer
                description: 代码审查专家
                tools:
                  - ReadFile
                  - Grep
                disallowed_tools:
                  - Bash
                max_turns: 10
                model: sonnet
                ---
                你是审查专家，逐文件检查代码。
                """);
        SubAgentSpec spec = AgentLoader.parseAgentFile(file);
        assertEquals("reviewer", spec.name());
        assertEquals("代码审查专家", spec.description());
        assertEquals(List.of("ReadFile", "Grep"), spec.tools());
        assertEquals(List.of("Bash"), spec.disallowedTools());
        assertEquals(10, spec.maxTurns());
        assertEquals("sonnet", spec.model());
        assertTrue(spec.systemPromptOverride().contains("审查专家"));
    }

    @Test
    void parseAgentFileMissingNameThrows() throws Exception {
        Path file = root.resolve("noname.md");
        Files.writeString(file, "---\ndescription: x\n---\nbody");
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> AgentLoader.parseAgentFile(file));
        assertTrue(e.getMessage().contains("name"));
    }

    @Test
    void parseAgentFileInvalidModelThrows() throws Exception {
        Path file = root.resolve("badmodel.md");
        Files.writeString(file, "---\nname: x\ndescription: y\nmodel: gpt-99\n---\nbody");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AgentLoader.parseAgentFile(file));
    }

    @Test
    void emptyBodyMeansNullOverride() throws Exception {
        Path file = root.resolve("empty.md");
        Files.writeString(file, "---\nname: e\ndescription: d\n---\n");
        SubAgentSpec spec = AgentLoader.parseAgentFile(file);
        assertNull(spec.systemPromptOverride());
    }

    @Test
    void loadAllThreeTiersWithOverride() throws Exception {
        // 项目级覆盖 builtin 同名
        Files.createDirectories(root.resolve(".dino/agents"));
        Files.writeString(root.resolve(".dino/agents/explore.md"), """
                ---
                name: explore
                description: 自定义探索
                ---
                自定义 body
                """);
        AgentLoader loader = AgentLoader.loadAll(root);
        assertEquals("自定义探索", loader.get("explore").description()); // 项目覆盖 builtin
        assertEquals(3, loader.list().size()); // general-purpose / plan / explore
        assertTrue(loader.listNames().containsAll(List.of("general-purpose", "plan", "explore")));
    }

    @Test
    void loadAllMissingDirsSilent() {
        AgentLoader loader = AgentLoader.loadAll(root);
        assertEquals(3, loader.list().size()); // builtin 仍加载
    }

    // ---------- T4：ToolFilter ----------

    private ToolRegistry parentRegistry() {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new Tool() {
            public String name() {
                return "ReadFile";
            }

            public String description() {
                return "";
            }

            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            public boolean readOnly() {
                return true;
            }

            public Result execute(Map<String, Object> args) {
                return Result.ok("");
            }
        });
        reg.register(new Tool() {
            public String name() {
                return "Bash";
            }

            public String description() {
                return "";
            }

            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            public boolean readOnly() {
                return false;
            }

            public Result execute(Map<String, Object> args) {
                return Result.ok("");
            }
        });
        reg.register(new Tool() {
            public String name() {
                return "Agent";
            }

            public String description() {
                return "";
            }

            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            public boolean readOnly() {
                return false;
            }

            public Result execute(Map<String, Object> args) {
                return Result.ok("");
            }
        });
        reg.register(new Tool() {
            public String name() {
                return "mcp__demo__echo";
            }

            public String description() {
                return "";
            }

            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            public boolean readOnly() {
                return true;
            }

            public Result execute(Map<String, Object> args) {
                return Result.ok("");
            }
        });
        return reg;
    }

    @Test
    void filterAlwaysBlocksAgentTool() {
        ToolRegistry filtered = ToolFilter.filterForAgent(parentRegistry(), SubAgentSpec.GENERAL_PURPOSE);
        assertTrue(filtered.get("Agent").isEmpty()); // N1 防递归
        assertNotNull(filtered.get("ReadFile"));
        assertNotNull(filtered.get("Bash"));
        assertNotNull(filtered.get("mcp__demo__echo")); // ① MCP 豁免
    }

    @Test
    void filterRespectsSpecBlacklist() {
        ToolRegistry filtered = ToolFilter.filterForAgent(parentRegistry(), SubAgentSpec.PLAN);
        assertFalse(filtered.get("Bash").isEmpty()); // PLAN 只禁 EditFile/WriteFile，Bash 应在
    }

    @Test
    void filterWhitelistIntersection() {
        SubAgentSpec spec = new SubAgentSpec("w", "d", List.of("ReadFile"), List.of(), null, 10, "");
        ToolRegistry filtered = ToolFilter.filterForAgent(parentRegistry(), spec);
        assertNotNull(filtered.get("ReadFile"));
        assertTrue(filtered.get("Bash").isEmpty()); // 白名单交集排除
        assertTrue(filtered.get("Agent").isEmpty()); // 全局禁依然生效
    }

    @Test
    void filterStarMeansNoWhitelist() {
        SubAgentSpec spec = new SubAgentSpec("s", "d", List.of("*"), List.of(), null, 10, "");
        ToolRegistry filtered = ToolFilter.filterForAgent(parentRegistry(), spec);
        assertNotNull(filtered.get("Bash")); // ["*"] 视为无白名单
    }

    @Test
    void filterAsyncWhitelist() {
        ToolRegistry filtered = ToolFilter.filterForAgent(parentRegistry(), SubAgentSpec.GENERAL_PURPOSE,
                true, false, false);
        // Bash 在 ASYNC_ALLOWED 15 项内 → async 下保留；Agent 恒禁
        assertFalse(filtered.get("Bash").isEmpty());
        assertTrue(filtered.get("Agent").isEmpty());
    }

    // ---------- T5：TaskManager 状态机 ----------

    @Test
    void taskLifecycleAndNotifications() {
        AtomicReference<String> ran = new AtomicReference<>();
        SubAgentTaskManager mgr = new SubAgentTaskManager((spec, prompt, filtered, history) -> {
            ran.set(prompt);
            return "完成输出";
        });
        String id = mgr.createTask("demo");
        assertNull(mgr.drainNotifications().stream().findFirst().orElse(null));

        mgr.setRunning(id, Thread.currentThread());
        mgr.setCompleted(id, "完成输出");

        var notifications = mgr.drainNotifications();
        assertEquals(1, notifications.size());
        assertEquals("completed", notifications.get(0).status());
        assertEquals("完成输出", notifications.get(0).summary());
        assertEquals(0, mgr.drainNotifications().size()); // drain 清空
    }

    @Test
    void failedTaskNotifies() {
        SubAgentTaskManager mgr = new SubAgentTaskManager((spec, prompt, filtered, history) -> "x");
        String id = mgr.createTask("demo");
        mgr.setRunning(id, null);
        mgr.setFailed(id, " boom");
        var n = mgr.drainNotifications();
        assertEquals(1, n.size());
        assertEquals("failed", n.get(0).status());
    }

    // ---------- T6：spawnSubAgent 后台 ----------

    @Test
    void spawnRunsInBackgroundAndNotifies() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        SubAgentTaskManager mgr = new SubAgentTaskManager((spec, prompt, filtered, history) -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "后台完成";
        });
        // 完成后 countdown：轮询 drain
        SubAgentSpec spec = SubAgentSpec.GENERAL_PURPOSE;
        String taskId = mgr.spawnSubAgent(spec, "任务", parentRegistry());

        // 等待完成通知（最多 5s）
        long deadline = System.currentTimeMillis() + 5000;
        List<SubAgentTaskManager.TaskNotification> notifications = List.of();
        while (System.currentTimeMillis() < deadline) {
            notifications = mgr.drainNotifications();
            if (!notifications.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals(1, notifications.size());
        assertEquals("completed", notifications.get(0).status());
        assertEquals(taskId, notifications.get(0).taskId());
        assertEquals(SubAgentTaskManager.TaskStatus.COMPLETED, mgr.getTask(taskId).status);
    }

    @Test
    void spawnFailureNotifiesFailed() throws Exception {
        SubAgentTaskManager mgr = new SubAgentTaskManager((spec, prompt, filtered, history) -> {
            throw new IllegalStateException("执行爆炸");
        });
        String taskId = mgr.spawnSubAgent(SubAgentSpec.GENERAL_PURPOSE, "任务", parentRegistry());
        long deadline = System.currentTimeMillis() + 5000;
        List<SubAgentTaskManager.TaskNotification> notifications = List.of();
        while (System.currentTimeMillis() < deadline) {
            notifications = mgr.drainNotifications();
            if (!notifications.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals("failed", notifications.get(0).status());
        assertTrue(notifications.get(0).summary().contains("执行爆炸"));
    }

    // ---------- T10：fork ----------

    @Test
    void buildForkedConversationAddsPlaceholdersForHangingToolCalls() {
        List<Message> parent = List.of(
                Message.user("问题"),
                Message.assistantWithTools("我来查",
                        List.of(new dinocode.core.ToolCall("c1", "ReadFile", "{}"))),
                Message.tool(List.of(new ToolResult("c1", "结果", false))),
                Message.assistantWithTools("",
                        List.of(new dinocode.core.ToolCall("c2", "Bash", "{}")))); // 悬挂
        List<Message> forked = AgentTool.buildForkedConversation(parent);

        // 悬挂调用后补占位 tool 消息
        boolean hasPlaceholder = forked.stream()
                .filter(m -> m.role() == Role.TOOL)
                .flatMap(m -> m.toolResults().stream())
                .anyMatch(r -> r.content().contains("interrupted by fork"));
        assertTrue(hasPlaceholder);
        // 配对合法：悬挂 assistant（c2）之后紧跟占位 tool
        for (int i = 0; i < forked.size(); i++) {
            boolean hasC2 = forked.get(i).toolCalls().stream()
                    .anyMatch(c -> c.id().equals("c2"));
            if (hasC2) {
                assertEquals(Role.TOOL, forked.get(i + 1).role());
                assertEquals("c2", forked.get(i + 1).toolResults().get(0).toolCallId());
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("未找到 c2 悬挂调用");
    }

    @Test
    void forkRejectsNestedFork() {
        var tool = new AgentTool(parentRegistry(), (spec, prompt, filtered, history) -> "x")
                .withParentConversation(List.of(
                        Message.user("包含 <fork_boilerplate> 标签的历史")));
        var result = tool.execute(Map.of("description", "d", "prompt", "p")); // 无 subagent_type → fork
        assertTrue(result.isError());
        assertTrue(result.content().contains("cannot fork from a forked agent")); // N4
    }

    @Test
    void forkRequiresParentConversation() {
        var tool = new AgentTool(parentRegistry(), (spec, prompt, filtered, history) -> "x");
        var result = tool.execute(Map.of("description", "d", "prompt", "p"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("fork requires parent conversation context")); // F13
    }

    // ---------- T8：execute 分支 ----------

    @Test
    void missingRequiredArgsErrors() {
        var tool = new AgentTool(parentRegistry(), (spec, prompt, filtered, history) -> "x");
        assertTrue(tool.execute(Map.of("description", "d")).isError());
        assertTrue(tool.execute(Map.of("prompt", "p")).isError());
        assertTrue(tool.execute(Map.of("description", "d", "prompt", "p")).isError()); // fork 无父对话
    }

    @Test
    void unknownTypeErrors() {
        var tool = new AgentTool(parentRegistry(), (spec, prompt, filtered, history) -> "x")
                .withParentConversation(List.of(Message.user("x")));
        var result = tool.execute(Map.of("description", "d", "prompt", "p", "subagent_type", "nope"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("unknown agent type 'nope'"));
    }

    @Test
    void syncExecutesAndFormatsOutput() {
        var tool = new AgentTool(parentRegistry(), (spec, prompt, filtered, history) -> "任务结果")
                .withParentConversation(List.of(Message.user("x")));
        var result = tool.execute(Map.of("description", "d", "prompt", "p", "subagent_type", "general-purpose"));
        assertFalse(result.isError());
        assertTrue(result.content().contains("Agent \"general-purpose\" completed in"));
        assertTrue(result.content().contains("任务结果"));
    }

    @Test
    void asyncReturnsTaskIdImmediately() {
        CountDownLatch never = new CountDownLatch(1);
        SubAgentTaskManager mgr = new SubAgentTaskManager((spec, prompt, filtered, history) -> {
            try {
                never.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "never";
        });
        var tool = new AgentTool(parentRegistry(), (spec, prompt, filtered, history) -> "x")
                .withTaskManager(mgr)
                .withParentConversation(List.of(Message.user("x")));
        var result = tool.execute(Map.of("description", "d", "prompt", "p",
                "subagent_type", "general-purpose", "run_in_background", true));
        assertFalse(result.isError());
        assertTrue(result.content().matches(".*task_\\d+.*"));
        mgr.cancelTask(mgr.listTasks().get(0).id); // 清理后台线程
    }

    @Test
    void teamPathReservedForLaterChapter() {
        var tool = new AgentTool(parentRegistry(), (spec, prompt, filtered, history) -> "x")
                .withParentConversation(List.of(Message.user("x")));
        var result = tool.execute(Map.of("description", "d", "prompt", "p",
                "subagent_type", "general-purpose", "team_name", "alpha"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("later chapter"));
    }

    // ---------- T7：schema ----------

    @Test
    void schemaExposesAgentTypeEnum() {
        var tool = new AgentTool(parentRegistry(), (spec, prompt, filtered, history) -> "x");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = tool.schema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props.get("subagent_type"));
        assertTrue(schema.get("required").toString().contains("prompt"));
        assertTrue(tool.description().contains("general-purpose"));
        assertTrue(tool.description().contains("explore"));
    }
}
