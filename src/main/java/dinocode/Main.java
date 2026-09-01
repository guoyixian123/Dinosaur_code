package dinocode;

import dinocode.agent.CompactContext;
import dinocode.compact.state.SessionContext;
import dinocode.config.AppConfig;
import dinocode.config.ConfigException;
import dinocode.config.ConfigLoader;
import dinocode.mcp.McpConfig;
import dinocode.mcp.McpManager;
import dinocode.permission.PermissionEngine;
import dinocode.provider.ChatProvider;
import dinocode.provider.ProviderFactory;
import dinocode.session.Session;
import dinocode.session.SessionSettings;
import dinocode.session.SessionStore;
import dinocode.tool.Tool;
import dinocode.tool.ToolRegistry;
import dinocode.tui.Tui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 组装入口（见 spec 设计骨架）：
 * 加载配置 → 建 Provider → 恢复会话 → 进入交互循环。
 */
public final class Main {

    public static void main(String[] args) {
        AppConfig config = loadConfig(args);

        SessionStore store = SessionStore.defaultStore();
        // ch09 起启动永远开新会话；恢复走 /resume（JSONL 会话列表），不再自动加载 JSON 旧会话
        Session session = new Session(SessionStore.newSessionId(),
                System.currentTimeMillis(), new ArrayList<>(), SessionSettings.EMPTY);

        ChatProvider provider = ProviderFactory.create(config);
        ToolRegistry registry = ToolRegistry.createDefault();
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();

        // ch09 F1~F8：三层指令文件加载（进程启动时一次，结果缓存）
        String instructionText = new dinocode.instructions.Loader(root).load();
        // ch09 F27~F42：记忆管理器（两级 Store）+ 索引注入文本
        dinocode.memory.Memory.Manager memMgr = new dinocode.memory.Memory.Manager(
                root.resolve(".dino").resolve("memory"),
                Path.of(System.getProperty("user.home"), ".dino", "memory"));
        memMgr.setProvider(provider);
        String memoryText = memMgr.loadIndex();

        // ch07：MCP 客户端——加载配置、并发连接 server、适配注册远端工具；退出时统一关闭
        McpConfig mcpConfig = dinocode.mcp.ConfigLoader.loadConfig(root);
        McpManager mcpManager = McpManager.start(mcpConfig, "0.1.0");
        Runtime.getRuntime().addShutdownHook(new Thread(mcpManager::close, "mcp-shutdown"));
        for (Tool tool : mcpManager.tools()) {
            registry.register(tool);
        }

        // ch06：项目根沙箱 + 三层规则配置 + 启动默认模式
        PermissionEngine engine = PermissionEngine.create(root);
        // ch12 F6/G2：Hook 引擎（两层 YAML 叠加加载；错误 stderr 后跳过，不阻断启动）
        dinocode.hook.HookEngine hookEngine = dinocode.hook.HookLoader.load(root);
        // ch08：上下文管理状态（会话目录 + 账本 + 熔断 + 锚点），进程启动时生成一次（F34/F35）
        SessionContext sesCtx = SessionContext.create(root);
        CompactContext compact = new CompactContext(sesCtx, config.effectiveContextWindow());

        // ch13：子 Agent 系统——加载定义、构造 Agent 工具与任务管理器
        dinocode.subagent.AgentLoader agentSpecs = dinocode.subagent.AgentLoader.loadAll(root);
        // 子 Agent 执行委托：构造独立 Agent 实例阻塞消费事件流（N5）
        dinocode.agent.SubAgentExecutor subExecutor = new dinocode.agent.SubAgentExecutor(
                provider, config.maxTokens(), engine, instructionText, memoryText, hookEngine);
        dinocode.subagent.SubAgentTaskManager.SubAgentRunner runner = subExecutor::run;
        dinocode.subagent.SubAgentTaskManager taskManager = new dinocode.subagent.SubAgentTaskManager(runner);
        dinocode.subagent.AgentTool.SubAgentRunnerDelegate delegate = subExecutor::run;
        dinocode.subagent.AgentTool agentTool = new dinocode.subagent.AgentTool(registry, delegate)
                .withTaskManager(taskManager)
                .withAgentSpecs(agentSpecs);
        registry.register(agentTool);
        // TaskStop：让模型/用户能停止后台子 Agent 任务（此前从未注册，后台任务无法取消）
        registry.register(new dinocode.subagent.TaskStopTool(taskManager));

        // ch14：worktree 系统——管理器 + 会话级工具 + SubAgent 隔离 + 启动恢复 + 后台清理
        dinocode.worktree.WorktreeManager worktreeManager = new dinocode.worktree.WorktreeManager(
                root, List.of("node_modules"), 24);
        registry.register(new dinocode.worktree.EnterWorktreeTool(worktreeManager, sesCtx.sessionId()));
        registry.register(new dinocode.worktree.ExitWorktreeTool(worktreeManager, root));
        agentTool.withWorktreeManager(worktreeManager);
        // F9 启动恢复：持久化的 worktree 会话写回单例（目录仍存在才恢复）
        dinocode.worktree.WorktreeSession savedSession = dinocode.worktree.WorktreeSessionStore.load(root);
        if (savedSession != null && java.nio.file.Files.exists(java.nio.file.Path.of(savedSession.worktreePath()))) {
            dinocode.worktree.WorktreeSessionStore.restoreSession(savedSession);
        }
        // F17 后台清理：每小时跑一次，24h 未动的孤儿 worktree 清掉（关停时 shutdown）
        java.util.concurrent.ScheduledExecutorService worktreeCleanup =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread th = new Thread(r, "worktree-cleanup");
                    th.setDaemon(true);
                    return th;
                });
        dinocode.worktree.StaleCleanup.startCleanupLoop(worktreeCleanup, root, 3600, 24);

        // ch15：Agent 团队——TeamManager + 三件套工具 + AgentTool 团队分支
        dinocode.teams.TeamManager teamMgr = new dinocode.teams.TeamManager(
                dinocode.teams.TeamManager.teamsBaseDir());
        registry.register(new dinocode.teams.TeamTools.TeamCreateTool(teamMgr));
        registry.register(new dinocode.teams.TeamTools.TeamDeleteTool(teamMgr));
        registry.register(new dinocode.teams.TeamTools.SendMessageTool(teamMgr, "lead"));
        agentTool.withTeamManager(teamMgr);
        dinocode.teams.TeammateRunner.SingleTurnRunner teamRunner =
                (Object agent, Object conv, java.util.List<dinocode.core.Message> seed) -> {
                    // agent 槽是队员的 SubAgentSpec（SpawnDispatcher 塞入）；工具集用
                    // teammateTools（全量 + SendMessage），队员靠它与 Lead/队友沟通
                    dinocode.subagent.SubAgentSpec spec = (dinocode.subagent.SubAgentSpec) agent;
                    return subExecutor.run(spec, null, agentTool.teammateTools(), seed);
                };
        agentTool.withTeammateRunner(teamRunner);

        // ch09 F13~F16：JSONL 会话存档写入器 + Session 回调挂接
        dinocode.session.archive.Writer archiveWriter;
        try {
            archiveWriter = dinocode.session.archive.Writer.create(sesCtx.sessionDir());
        } catch (java.io.IOException e) {
            System.err.println("警告: 会话存档不可用(" + e.getMessage() + ")，继续运行");
            archiveWriter = null;
        }
        if (archiveWriter != null) {
            session.setArchiveCallbacks(archiveWriter::archiveAppend, archiveWriter::archiveReplace);
        }

        // ch09 F25/F26：后台清理 30 天前的过期会话目录（不阻塞启动）
        Thread.ofVirtual().start(() -> dinocode.session.archive.SessionArchive.cleanExpired(
                root.resolve(".dino").resolve("sessions"), java.time.Duration.ofDays(30)));

        int exitCode = new Tui(config, provider, registry, engine, compact, store, session, false)
                .withArchive(archiveWriter, memMgr, instructionText, memoryText, root)
                .withHookEngine(hookEngine)
                .withTaskManager(taskManager)
                .withTeamManager(teamMgr)
                .run();
        mcpManager.close(); // 正常退出路径（shutdown hook 兜底异常退出）
        worktreeCleanup.shutdown(); // ch14 T12：停后台清理
        teamMgr.closeAll(); // ch15 T15：中断所有队员虚拟线程
        System.exit(exitCode);
    }

    /** 配置错误：按 checklist §A 的文案报错并以退出码 1 退出。 */
    private static AppConfig loadConfig(String[] args) {
        Path configPath = ConfigLoader.defaultPath();
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) {
                configPath = Path.of(args[i + 1]);
            }
        }
        try {
            return ConfigLoader.load(configPath, System.getenv());
        } catch (ConfigException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            throw new IllegalStateException("unreachable");
        }
    }

    private Main() {
    }
}
