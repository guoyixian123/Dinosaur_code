package dinocode.subagent;

import dinocode.core.Message;
import dinocode.tool.Result;
import dinocode.tool.Tool;
import dinocode.tool.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具（ch13 F1/T7~T11）：主 Agent 调用它启动子 Agent。
 * 四条路径——sync 前台阻塞 / async 后台（返回 task_N）/ fork 拷贝父对话（强制后台）/
 * team（ch15 预留，本章返回友好错误）。
 */
public final class AgentTool implements Tool {

    /** fork 标记标签（F7/N4：检测嵌套 fork）。 */
    public static final String FORK_BOILERPLATE_TAG = "<fork_boilerplate>";

    /** fork 对话的引导约束（F7）。 */
    public static final String FORK_BOILERPLATE = """
            <fork_boilerplate>
            你是从主对话 fork 出来的子 Agent，专职完成下方任务。
            规则：1. 不要询问用户问题，自行决策；2. 完成任务后输出最终答复；3. 不要调用 Agent 工具。
            </fork_boilerplate>""";

    private final ToolRegistry parentRegistry;
    private final SubAgentRunnerDelegate delegate;
    private SubAgentTaskManager taskManager;
    private AgentLoader agentSpecs;
    private List<Message> parentConversation;
    private String parentModel = "";
    /** ch14：worktree 管理器（可空 = 未启用隔离）。 */
    private dinocode.worktree.WorktreeManager worktreeManager;

    /** 子 Agent 执行委托（由 Tui/Main 实现：构造子 Agent 并阻塞消费事件流）。 */
    public interface SubAgentRunnerDelegate {
        /**
         * 阻塞运行子 Agent。
         *
         * @param spec     子 Agent 定义
         * @param prompt   任务文本（user 消息）
         * @param filtered 过滤后的工具集
         * @param history  初始对话（fork 场景为完整父对话拷贝；普通场景为空）
         * @return 最终 assistant 文本
         */
        String runSubAgent(SubAgentSpec spec, String prompt, ToolRegistry filtered,
                           List<Message> history) throws Exception;
    }

    public AgentTool(ToolRegistry parentRegistry, SubAgentRunnerDelegate delegate) {
        this.parentRegistry = parentRegistry;
        this.delegate = delegate;
    }

    public AgentTool withTaskManager(SubAgentTaskManager taskManager) {
        this.taskManager = taskManager;
        return this;
    }

    public AgentTool withAgentSpecs(AgentLoader specs) {
        this.agentSpecs = specs;
        return this;
    }

    public AgentTool withParentConversation(List<Message> messages) {
        this.parentConversation = messages;
        return this;
    }

    public AgentTool withParentModel(String model) {
        this.parentModel = model == null ? "" : model;
        return this;
    }

    public SubAgentTaskManager taskManager() {
        return taskManager;
    }

    /** ch14：注入 worktree 管理器（启用 isolation: worktree 隔离）。 */
    public AgentTool withWorktreeManager(dinocode.worktree.WorktreeManager manager) {
        this.worktreeManager = manager;
        return this;
    }

    @Override
    public String name() {
        return "Agent";
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder("启动一个子 Agent 执行独立任务（上下文隔离，避免撑爆主对话）。"
                + "可用类型: ");
        List<String> names = agentSpecs != null ? agentSpecs.listNames()
                : List.of("general-purpose", "plan", "explore");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(names.get(i));
        }
        sb.append("。不带 subagent_type 时 fork 当前对话上下文。");
        return sb.toString();
    }

    @Override
    public Map<String, Object> schema() {
        List<String> typeEnum = agentSpecs != null ? agentSpecs.listNames()
                : List.of("general-purpose", "plan", "explore");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("description", Map.of("type", "string",
                "description", "一句话描述这个子任务（3-5 词）"));
        props.put("prompt", Map.of("type", "string",
                "description", "交给子 Agent 的完整任务描述"));
        props.put("subagent_type", Map.of(
                "type", "string",
                "description", "子 Agent 类型；缺省时 fork 当前对话",
                "enum", typeEnum));
        props.put("run_in_background", Map.of("type", "boolean",
                "description", "true = 后台执行，立即返回 task_id"));
        props.put("isolation", Map.of(
                "type", "string",
                "description", "worktree = 在隔离的 git worktree 中执行（不影响主目录文件）",
                "enum", List.of("worktree")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("description", "prompt"));
        return schema;
    }

    @Override
    public boolean readOnly() {
        return false; // 子 Agent 可能执行写操作
    }

    /** 解析参数（Jackson convertValue 失败 → 参数错误）。 */
    private record Args(String description, String prompt, String subagentType,
                        boolean runInBackground, String isolation) {
    }

    private Args parseArgs(Map<String, Object> args) {
        return new Args(
                str(args.get("description")),
                str(args.get("prompt")),
                str(args.get("subagent_type")),
                Boolean.TRUE.equals(args.get("run_in_background")),
                str(args.get("isolation")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @Override
    public Result execute(Map<String, Object> args) {
        Args a;
        try {
            a = parseArgs(args);
        } catch (Exception e) {
            return Result.error("参数解析失败: " + e.getMessage());
        }
        if (a.description() == null || a.description().isBlank()
                || a.prompt() == null || a.prompt().isBlank()) {
            return Result.error("Error: description and prompt are required");
        }

        // 分支顺序（T8）：subagent_type 空 → fork；team → ch15 预留；后台 → async；默认 sync
        if (a.subagentType() == null || a.subagentType().isBlank()) {
            return runFork(a.prompt());
        }
        SubAgentSpec spec = resolveSpec(a.subagentType());
        if (spec == null) {
            return Result.error("Error: unknown agent type '" + a.subagentType()
                    + "'. Available: " + (agentSpecs != null ? agentSpecs.listNames()
                    : List.of("general-purpose", "plan", "explore")));
        }
        if (args.containsKey("team_name")) {
            return Result.error("Error: team support arrives in a later chapter"); // ch15 预留
        }
        if (a.runInBackground()) {
            return runAsync(spec, a.prompt());
        }
        return runSync(spec, a.prompt(), a.isolation());
    }

    /** 查找 spec：优先 agentSpecs（Markdown 定义），回退三档 builtin。 */
    private SubAgentSpec resolveSpec(String type) {
        if (agentSpecs != null) {
            SubAgentSpec custom = agentSpecs.get(type);
            if (custom != null) {
                return custom;
            }
        }
        return switch (type) {
            case "general-purpose" -> SubAgentSpec.GENERAL_PURPOSE;
            case "plan" -> SubAgentSpec.PLAN;
            case "explore" -> SubAgentSpec.EXPLORE;
            default -> null;
        };
    }

    // ---------- sync（T9） ----------

    private Result runSync(SubAgentSpec spec, String prompt, String isolation) {
        long start = System.nanoTime();
        dinocode.worktree.AgentWorktree.Result wtResult = null;
        String effectivePrompt = prompt;
        try {
            // ch14 F11：isolation=worktree 时创建隔离工作区
            if ("worktree".equals(isolation) && worktreeManager != null) {
                try {
                    byte[] rnd = new byte[4];
                    new java.security.SecureRandom().nextBytes(rnd);
                    String slug = "agent-a" + java.util.HexFormat.of().formatHex(rnd).substring(0, 7);
                    wtResult = dinocode.worktree.AgentWorktree.create(
                            slug, worktreeManager.getProjectRoot(), worktreeManager.getSymlinkDirs());
                    String notice = dinocode.worktree.AgentWorktree.buildNotice(
                            System.getProperty("user.dir"), wtResult.worktreePath());
                    effectivePrompt = notice + "\n\n" + prompt;
                } catch (Exception e) {
                    return Result.error("Error creating agent worktree: " + e.getMessage());
                }
            }
            ToolRegistry filtered = ToolFilter.filterForAgent(parentRegistry, spec);
            String output = delegate.runSubAgent(spec, effectivePrompt, filtered, List.of());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            // ch14 F12：完成后按变更决策——干净自动清理，脏则保留并附加信息
            String wtInfo = "";
            if (wtResult != null) {
                if (dinocode.worktree.WorktreeChanges.hasChanges(
                        wtResult.worktreePath(), wtResult.headCommit())) {
                    wtInfo = String.format("%n%nWorktree kept at %s (branch %s) — has uncommitted changes or new commits.",
                            wtResult.worktreePath(), wtResult.worktreeBranch());
                } else {
                    dinocode.worktree.AgentWorktree.remove(
                            wtResult.worktreePath(), wtResult.worktreeBranch(), wtResult.gitRoot());
                }
            }
            return Result.ok(String.format("Agent \"%s\" completed in %d.%03ds.%n%n%s%s",
                    spec.name(), elapsedMs / 1000, elapsedMs % 1000, output, wtInfo));
        } catch (Exception e) {
            // 隔离失败时保留 worktree 供检查（fail-closed 语义）
            return Result.error("Agent \"" + spec.name() + "\" failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    // ---------- async（T11） ----------

    private Result runAsync(SubAgentSpec spec, String prompt) {
        if (taskManager == null) {
            return Result.error("Background execution not available (no task manager configured)");
        }
        String taskId = taskManager.spawnSubAgent(spec, prompt, parentRegistry);
        return Result.ok(String.format(
                "Agent \"%s\" launched in background (task %s). You will be notified when it completes.",
                spec.name(), taskId));
    }

    // ---------- fork（T10/F7） ----------

    private Result runFork(String prompt) {
        if (parentConversation == null) {
            return Result.error("Error: fork requires parent conversation context");
        }
        // N4：先扫父对话拒绝嵌套 fork（在 taskManager 检查之前，fail-fast）
        for (Message m : parentConversation) {
            if (m.content() != null && m.content().contains(FORK_BOILERPLATE_TAG)) {
                return Result.error("Error: cannot fork from a forked agent. "
                        + "Use subagent_type to spawn a definition-based agent instead.");
            }
        }
        if (taskManager == null) {
            return Result.error("Error: fork requires task manager for background execution");
        }
        List<Message> forked = buildForkedConversation(parentConversation);
        forked.add(Message.user(FORK_BOILERPLATE + "\n\nYour task:\n" + prompt));

        SubAgentSpec spec = new SubAgentSpec(
                "forked-agent", "fork 子 Agent",
                List.of(), List.of(), null, 200, "");
        String taskId = spawnForkTask(spec, forked);
        return Result.ok(String.format(
                "Forked agent \"%s\" launched in background (task %s). Results will arrive via task-notification.",
                spec.name(), taskId));
    }

    /** fork 任务的 spawn：需要携带 forked history，通过独立队列登记。 */
    private final Map<String, List<Message>> forkHistories = new LinkedHashMap<>();

    private String spawnForkTask(SubAgentSpec spec, List<Message> forked) {
        // 注册 forked history，runner 在执行时按 taskId 取
        String taskId = taskManager.createTask(spec.name());
        forkHistories.put(taskId, forked);
        Thread thread = Thread.ofVirtual().start(() -> {
            taskManager.setRunning(taskId, Thread.currentThread());
            try {
                ToolRegistry filtered = ToolFilter.filterForAgent(parentRegistry, spec);
                String output = delegate.runSubAgent(spec, null, filtered, forkHistories.get(taskId));
                taskManager.setCompleted(taskId, output);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                taskManager.setFailed(taskId, "Interrupted");
            } catch (Exception e) {
                taskManager.setFailed(taskId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
        taskManager.setRunning(taskId, thread);
        return taskId;
    }

    /**
     * 构造 forked conversation（T10/F7）：拷贝父消息；悬挂的 toolCalls（assistant 带调用
     * 但其后无 tool 结果）补占位 tool 结果，保证消息序列合法。
     */
    static List<Message> buildForkedConversation(List<Message> parent) {
        List<Message> out = new java.util.ArrayList<>();
        for (int i = 0; i < parent.size(); i++) {
            Message m = parent.get(i);
            out.add(new Message(m.role(), m.content(),
                    new java.util.ArrayList<>(m.toolCalls()), new java.util.ArrayList<>(m.toolResults())));
            // 悬挂工具调用：assistant 带 toolCalls，且下一条不是对应 tool 结果 → 补占位
            if (!m.toolCalls().isEmpty()
                    && (i == parent.size() - 1 || parent.get(i + 1).role() != dinocode.core.Role.TOOL)) {
                List<dinocode.core.ToolResult> placeholders = new java.util.ArrayList<>();
                for (dinocode.core.ToolCall c : m.toolCalls()) {
                    placeholders.add(new dinocode.core.ToolResult(c.id(),
                            "(tool execution interrupted by fork)", false));
                }
                out.add(Message.tool(placeholders));
            }
        }
        return out;
    }
}
