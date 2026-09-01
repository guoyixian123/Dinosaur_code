package dinocode.tui;

import dinocode.agent.Agent;
import dinocode.agent.ApprovalRequest;
import dinocode.agent.CancelToken;
import dinocode.agent.CompactContext;
import dinocode.agent.TurnEvent;
import dinocode.agent.TurnStream;
import dinocode.compact.ContextCompactor;
import dinocode.compact.Token;
import dinocode.config.AppConfig;
import dinocode.core.Message;
import dinocode.core.ToolDefinition;
import dinocode.core.Usage;
import dinocode.permission.Mode;
import dinocode.permission.Outcome;
import dinocode.permission.PermissionEngine;
import dinocode.prompt.Reminder;
import dinocode.provider.ChatProvider;
import dinocode.session.Session;
import dinocode.session.SessionSettings;
import dinocode.session.SessionStore;
import dinocode.tool.ToolRegistry;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 终端交互层：唯一接触终端的地方（见 spec 设计骨架）。
 *
 * Ctrl+C 语义（checklist §E + ch04 F7 + ch06 F8）：
 * - 生成中：JLine 不在读取状态，SIGINT 到达本类注册的信号处理器 → 取消本轮 Agent Loop（不退出）；
 * - 等待批准（APPROVING）：同生成中——取消本轮，并向 respond 兜底回传 DENY_ONCE 解除 agent 阻塞；
 * - 等待输入：终端处于 raw 模式，Ctrl+C 由 JLine 转为 UserInterruptException，
 *   输入行为空则退出，非空则仅清空当前行。
 *
 * ch06：Shift+Tab 循环切换权限模式（IDLE 态）；/plan、/do 沿用计划工作流。
 */
public final class Tui {

    private final AppConfig config;
    private final ChatProvider provider;
    private final ToolRegistry registry;
    private final PermissionEngine engine;
    private final CompactContext compact;
    private final SessionStore store;
    private final boolean restored;

    private Session session;
    private Terminal terminal;
    private LineReader reader;
    private Renderer renderer;

    // ch06：权限模式跨轮保持；用量跨轮累加
    private Mode mode;
    private long usageIn;
    private long usageOut;

    private volatile CancelToken turnCancel;
    private volatile boolean generating;
    private volatile boolean interrupted;
    /** 人在回路待批准请求（ch06 F8）；非 null 表示处于 APPROVING 态。 */
    private volatile ApprovalRequest pendingApproval;

    // ch09：会话存档与记忆
    private dinocode.session.archive.Writer archiveWriter;
    private dinocode.memory.Memory.Manager memMgr;
    private String instructionText = "";
    private String memoryText = "";
    private java.nio.file.Path workspace;

    // ch11：技能目录
    private final dinocode.skill.SkillCatalog skillCatalog = new dinocode.skill.SkillCatalog();
    /** 本会话已激活的技能名（系统提示 Active Skills 段与工具过滤依据）。 */
    private final java.util.Set<String> activeSkills = new java.util.LinkedHashSet<>();

    // ch12：Hook 引擎（可空 = 未配置 hooks）
    private dinocode.hook.HookEngine hookEngine;
    /** ch12 F31：本会话的常驻 Agent（SessionStart/End/Resume 等 TUI 驱动事件的分派载体）。 */
    private Agent sessionAgent;
    // ch13：子 Agent 任务管理器（通知注入 + fork 父对话引用）
    private dinocode.subagent.SubAgentTaskManager taskManager;

    public Tui(AppConfig config, ChatProvider provider, ToolRegistry registry, PermissionEngine engine,
               CompactContext compact, SessionStore store, Session session, boolean restored) {
        this.config = config;
        this.provider = provider;
        this.registry = registry;
        this.engine = engine;
        this.compact = compact;
        this.mode = engine.startMode(); // 启动默认模式取自配置（F4/AC18）
        this.store = store;
        this.session = session;
        this.restored = restored;
        // ch10 F1/F2：启动期注册 12 条内置命令 + 2 条遗留本地命令（/new /tokens）
        // 冲突时 register 抛 IllegalStateException → 启动立即终止（F2/AC16）
        dinocode.command.Builtins.registerAll(cmdRegistry);
        cmdRegistry.register(dinocode.command.Command.of("new", "开启新会话", dinocode.command.Kind.UI,
                (cancelled, ui) -> clearAndNewSession()));
        cmdRegistry.register(dinocode.command.Command.of("tokens",
                "查看或设置最大输出 (/tokens low|medium|high|max|<数值>)", dinocode.command.Kind.LOCAL,
                (cancelled, ui) -> ui.println("用法: /tokens 已由启动参数与 /tokens 旧接口管理；当前会话最大输出 "
                        + currentMaxTokens())));
        // ch12 F34/F35：/hooks 列出已加载 hook（按 event 分组）
        cmdRegistry.register(dinocode.command.Command.of("hooks", "查看已加载的 Hook 列表",
                dinocode.command.Kind.LOCAL, (cancelled, ui) -> printHooks(ui)));
    }

    /** ch12：注入 Hook 引擎（Main 在构造后链式调用）。 */
    public Tui withHookEngine(dinocode.hook.HookEngine engine) {
        this.hookEngine = engine;
        return this;
    }

    /** ch13：注入子 Agent 任务管理器（fork 父对话 + 通知注入）。 */
    public Tui withTaskManager(dinocode.subagent.SubAgentTaskManager taskManager) {
        this.taskManager = taskManager;
        return this;
    }

    /** ch13 F5：取出后台子 Agent 的完成通知，格式化为可注入的 user 消息（无通知返回 null）。 */
    private String drainTaskNotifications() {
        if (taskManager == null) {
            return null;
        }
        var notifications = taskManager.drainNotifications();
        if (notifications.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<task-notification>");
        for (var n : notifications) {
            sb.append("\n<task id=\"").append(n.taskId()).append("\" agent=\"")
                    .append(n.agentName()).append("\" status=\"").append(n.status()).append("\">\n")
                    .append(n.summary()).append("\n</task>");
        }
        sb.append("\n</task-notification>");
        return sb.toString();
    }

    /** ch12 F34/F35：/hooks 输出——按 event 分组、每条一行；无 hook 时提示。 */
    private void printHooks(dinocode.command.Ui ui) {
        if (hookEngine == null || hookEngine.isEmpty()) {
            ui.println("No hooks loaded.");
            return;
        }
        StringBuilder sb = new StringBuilder("已加载 Hook:");
        dinocode.hook.Event prev = null;
        for (var rule : hookEngine.rules()) {
            if (prev != rule.event()) {
                sb.append("\n[").append(rule.event().wireName()).append("]");
                prev = rule.event();
            }
            sb.append("\n  ").append(rule.name()).append("  ").append(rule.event().wireName())
                    .append("  ").append(actionType(rule.action()));
            if (rule.onlyOnce()) {
                sb.append(" [once]");
            }
            if (rule.async()) {
                sb.append(" [async]");
            }
        }
        sb.append("\nLoaded from: ").append(String.join(", ", hookEngine.sources()));
        ui.println(sb.toString());
    }

    private static String actionType(dinocode.hook.Action action) {
        return switch (action) {
            case dinocode.hook.Action.Shell s -> "shell";
            case dinocode.hook.Action.Prompt p -> "prompt";
            case dinocode.hook.Action.Http h -> "http";
            case dinocode.hook.Action.Subagent s -> "subagent";
        };
    }

    /** ch09：注入存档/记忆组件（Main 在构造后链式调用）。 */
    public Tui withArchive(dinocode.session.archive.Writer writer, dinocode.memory.Memory.Manager memMgr,
                           String instructionText, String memoryText, java.nio.file.Path workspace) {
        this.archiveWriter = writer;
        this.memMgr = memMgr;
        this.instructionText = instructionText == null ? "" : instructionText;
        this.memoryText = memoryText == null ? "" : memoryText;
        this.workspace = workspace;
        return this;
    }

    /** @return 进程退出码 */
    public int run() {
        try {
            terminal = TerminalBuilder.builder()
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            terminal.handle(Terminal.Signal.INT, signal -> onInterrupt());
            reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new SlashCompleter(cmdRegistry)) // ch10 F24：/ 补全（Tab 触发）
                    .build();
            // Shift+Tab（终端发送 ESC[Z）展开为 /mode 命令提交（ch06 F7，IDLE 态循环切换）
            bindShiftTab(reader);
            renderer = new Renderer(terminal.writer());

            // ch11 T7：扫描两层技能目录并注册为 PROMPT 命令（description 以 [skill] 结尾，N7）
            skillCatalog.loadCatalog(workspace != null ? workspace : java.nio.file.Path.of("").toAbsolutePath());
            wireSkillsToAgent();
            cmdRegistry.register(dinocode.command.Command.of("skills", "[skill] 列出已加载技能",
                    dinocode.command.Kind.LOCAL,
                    (cancelled, ui) -> {
                        var skills = skillCatalog.list();
                        if (skills.isEmpty()) {
                            ui.println("当前没有已加载的技能。");
                        } else {
                            StringBuilder sb = new StringBuilder("已加载技能:");
                            for (var s : skills) {
                                sb.append("\n  /").append(s.meta().name()).append("  ")
                                        .append(s.meta().description() == null ? "" : s.meta().description())
                                        .append(" [").append(s.meta().mode()).append("]");
                            }
                            ui.println(sb.toString());
                        }
                    }));

            Banner.print(terminal.writer(), config,
                    restored ? "已恢复上次会话 " + session.getId() : "新会话");
            renderer.notice("提示: Shift+Tab 切换权限模式，Tab 补全斜杠命令，/help 查看全部命令");

            // ch12 F9：SessionStart 事件（env 装配完毕、首条 user 消息进入之前）
            ensureSessionAgent();
            sessionAgent.dispatchSessionHook(dinocode.hook.Event.SESSION_START, sessionId(), Map.of());

            loop();
            return 0;
        } catch (IOException e) {
            System.err.println("终端初始化失败: " + e.getMessage());
            return 1;
        } finally {
            closeTerminal();
        }
    }

    /** ch11 T7：把 catalog 内每个技能注册为 PROMPT 命令（跳过已有命令，F11/N7）。 */
    private void wireSkillsToAgent() {
        for (var skill : skillCatalog.list()) {
            registerSkillCommand(skill.meta().name());
        }
    }

    private void registerSkillCommand(String name) {
        if (cmdRegistry.lookup(name).isPresent()) {
            return; // 跳过已存在命令（F11）
        }
        var skill = skillCatalog.get(name);
        String desc = (skill.meta().description() == null ? name : skill.meta().description())
                + " [skill]"; // N7：description 以 [skill] 结尾作 UI 分支 marker
        cmdRegistry.register(dinocode.command.Command.of(name, desc,
                dinocode.command.Kind.PROMPT, (cancelled, ui) -> {
                    // 占位 handler：真实执行走 dispatchSlash 的 [skill] 分支（executeSkillCommand）
                }));
    }

    private void loop() {
        while (true) {
            String line;
            try {
                line = reader.readLine(prompt());
            } catch (UserInterruptException e) {
                if (e.getPartialLine() == null || e.getPartialLine().isBlank()) {
                    saveSessionQuietly();
                    return; // 输入行为空：退出
                }
                continue; // 输入行非空：清空当前行，继续等待输入
            } catch (EndOfFileException e) {
                saveSessionQuietly();
                return; // Ctrl+D
            }

            String input = line.strip();
            if (input.isEmpty()) {
                continue;
            }
            if (input.startsWith("/")) {
                if (!handleCommand(input)) {
                    return;
                }
                continue;
            }
            turn(input);
        }
    }

    /** 提示符：权限模式徽标（ch06 F7）。 */
    private String prompt() {
        return switch (mode) {
            case PLAN -> Ansi.GREEN + "❯ [PLAN] " + Ansi.RESET;
            case ACCEPT_EDITS -> Ansi.GREEN + "❯ [ACCEPT EDITS] " + Ansi.RESET;
            case BYPASS -> Ansi.RED + "❯ [BYPASS] " + Ansi.RESET;
            case DEFAULT -> Ansi.GREEN + "❯ " + Ansi.RESET;
        };
    }

    /** Shift+Tab（终端发送 ESC[Z）→ /mode 宏（独立方法避免源码内嵌控制字符）。 */
    private static void bindShiftTab(LineReader reader) {
        // JLine 3.26 默认 LineReader 的 getKeys() 可能为 null（未启用 keymap 时）：
        // 绑定失败只降级（Shift+Tab 不可用，/mode 命令仍可手动输入），不阻断启动
        try {
            var keyMap = reader.getKeys();
            if (keyMap != null) {
                keyMap.bind(new org.jline.reader.Macro("/mode\n"), "[Z");
            }
        } catch (RuntimeException e) {
            // 忽略：键位绑定是增强功能
        }
    }

    /** @return false 表示应退出程序 */
    private boolean handleCommand(String input) {
        // ch06 F7：Shift+Tab 展开为 /mode，循环切换权限模式（DEFAULT→ACCEPT_EDITS→PLAN→BYPASS→DEFAULT）
        if ("/mode".equals(input)) {
            mode = mode.next();
            renderer.notice("权限模式: " + mode.displayName() + "（Shift+Tab 继续切换）");
            return true;
        }
        return dispatchSlash(input); // ch10：统一走命令注册中心（LOCAL/UI/PROMPT 三类）
    }

    // ==================== ch10 命令注册中心集成 ====================

    private final dinocode.command.CommandRegistry cmdRegistry = new dinocode.command.CommandRegistry();

    /** 斜杠分发（ch10 F3~F7 + ch11 F11/F12）："/" 开头走注册中心；返回 false 表示应退出。 */
    private boolean dispatchSlash(String input) {
        var parsed = dinocode.command.Dispatch.parse(input);
        if (!parsed.isSlash()) {
            return true; // 调用方已保证以 / 开头，防御分支
        }
        var cmdOpt = cmdRegistry.lookup(parsed.name());
        if (cmdOpt.isEmpty()) {
            renderer.notice("未知命令" + (parsed.name().isEmpty() ? "" : ": " + parsed.name())
                    + "。输入 /help 查看可用命令"); // F6/N7：提示文案引导 /help
            return true;
        }
        var cmd = cmdOpt.get();
        // N3a：UI/PROMPT 类命令仅在空闲态可执行
        if (cmd.kind() != dinocode.command.Kind.LOCAL && generating) {
            renderer.failure("请等待当前任务完成");
            return true;
        }
        // ch11 F11/F12：[skill] 后缀 → 技能激活分支
        if (cmd.kind() == dinocode.command.Kind.PROMPT && cmd.description().endsWith("[skill]")) {
            executeSkillCommand(parsed.name(), input);
            return true;
        }
        try {
            cmd.handler().handle(new java.util.concurrent.atomic.AtomicBoolean(false), asUi());
        } catch (ExitRequested exit) {
            return false; // /exit：终止主循环
        } catch (Exception e) {
            renderer.failure("命令执行失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        return true;
    }

    /**
     * ch11 F11/F12/T8：技能命令执行——promptBody + args 作为 user message 进入对话
     * （与真实用户消息同持久化路径），UI 提示 Successfully loaded skill。
     */
    private void executeSkillCommand(String name, String rawInput) {
        var full = skillCatalog.getFull(name);
        if (full == null) {
            renderer.failure("技能不存在: " + name);
            return;
        }
        // args = 命令名之后的文本（Dispatch 不解析尾巴，这里手动取）
        String body = rawInput.strip();
        String args = "";
        if (body.startsWith("/" + name)) {
            args = body.substring(("/" + name).length()).strip();
        }
        String prompt = dinocode.skill.SkillExecutor.substituteArguments(full.promptBody(), args);
        activeSkills.add(name);
        renderer.notice("skill(" + name + ") Successfully loaded skill"); // F12
        turn(prompt, true); // 与真实用户消息同路径（N3）
    }

    /** 已激活技能的系统提示段（F6 buildActiveContext）。 */
    private String skillContext() {
        return activeSkills.isEmpty() ? "" : "\n\n" + skillCatalog.buildActiveContext(activeSkills);
    }

    /** ch12：常驻轻量 Agent（承载 TUI 驱动事件的分派；无 hook 时构造开销极小）。 */
    private void ensureSessionAgent() {
        if (sessionAgent == null) {
            sessionAgent = new Agent(provider, registry, "0.1.0", engine, compact, memMgr,
                    instructionText, memoryText, hookEngine);
        }
    }

    private String sessionId() {
        return compact != null && compact.session() != null ? compact.session().sessionId() : "";
    }

    /** Tui 的 Ui 抽象实现（F33）：handler 通过此视图操作 TUI。 */
    private UiView asUi() {
        return new UiView(this);
    }

    /** /exit 的内部控制流信号（dispatcher 捕获后终止主循环）。 */
    static final class ExitRequested extends RuntimeException {
        ExitRequested() {
            super(null, null, false, false);
        }
    }

    /**
     * Ui 接口的 Tui 实现（静态嵌套类，避免 Tui 直接 implements 泄漏接口到构造期）。
     * 全部方法在用户输入线程同步执行（N1 本地命令无可观察延迟）。
     */
    private static final class UiView implements dinocode.command.Ui {
        private final Tui tui;

        UiView(Tui tui) {
            this.tui = tui;
        }

        @Override
        public void println(String msg) {
            tui.renderer.notice(msg);
        }

        @Override
        public void error(String msg) {
            tui.renderer.failure(msg);
        }

        @Override
        public Mode mode() {
            return tui.mode;
        }

        @Override
        public void setMode(Mode m) {
            tui.mode = m;
        }

        @Override
        public void injectAndSend(String displayLabel, String presetPrompt) {
            // N3：与真实用户消息同路径——写入历史 + 存档回调触发 + 触发回合
            if ("/do".equals(displayLabel)) {
                // /do 保持 ch04 语义：指令本身不入历史（N10 外部行为不变）
                tui.mode = Mode.DEFAULT;
                tui.renderer.notice("已切回默认模式，按计划开始执行。");
                tui.turn(presetPrompt, false);
                return;
            }
            tui.turn(presetPrompt, true);
        }

        @Override
        public long usageIn() {
            return tui.usageIn;
        }

        @Override
        public long usageOut() {
            return tui.usageOut;
        }

        @Override
        public String modelName() {
            return tui.provider.model();
        }

        @Override
        public String cwd() {
            return System.getProperty("user.dir");
        }

        @Override
        public int toolCount() {
            return tui.registry.count();
        }

        @Override
        public List<String> memoryFiles() {
            var files = tui.memMgr != null ? tui.memMgr.listFiles() : null;
            if (files == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>(files.project());
            out.addAll(files.user());
            return out;
        }

        @Override
        public String sessionPath() {
            return tui.archiveWriter != null ? tui.archiveWriter.file().toString() : "";
        }

        @Override
        public String sessionId() {
            return tui.compact != null && tui.compact.session() != null
                    ? tui.compact.session().sessionId() : "";
        }

        @Override
        public void quit() {
            // ch12 F9：SessionEnd（进程关闭前）
            tui.ensureSessionAgent();
            tui.sessionAgent.dispatchSessionHook(dinocode.hook.Event.SESSION_END, tui.sessionId(), Map.of());
            // N12：先取消进行中的回合再请求退出
            tui.renderer.notice("再见 🦖");
            tui.saveSessionQuietly();
            CancelToken cancel = tui.turnCancel;
            if (cancel != null) {
                cancel.cancel();
            }
            throw new ExitRequested(); // dispatcher 捕获 → 主循环 return false
        }

        @Override
        public void forceCompact() {
            tui.handleCompact();
        }

        @Override
        public void openResumeMenu() {
            tui.handleResume();
        }

        @Override
        public void clearAndNewSession() {
            tui.clearAndNewSession();
        }

        @Override
        public boolean idle() {
            return !tui.generating;
        }
    }

    /** /clear（ch10 F17/AC8）：关旧存档 → 开新会话目录与存档 → 清空内存消息与用量。 */
    private void clearAndNewSession() {
        // ch12 F9：SessionEnd（关旧会话前）
        ensureSessionAgent();
        sessionAgent.dispatchSessionHook(dinocode.hook.Event.SESSION_END, sessionId(), Map.of());
        try {
            if (archiveWriter != null) {
                archiveWriter.close(); // 旧 JSONL 保留在磁盘（N9：/resume 仍可见）
            }
        } catch (IOException e) {
            renderer.failure("旧会话存档关闭失败: " + e.getMessage());
        }
        saveSessionQuietly(); // 旧 JSON 格式会话也收尾
        Session newSession = new Session(SessionStore.newSessionId(),
                System.currentTimeMillis(), new ArrayList<>(), SessionSettings.EMPTY);
        if (compact != null) {
            compact.resetForNewSession(dinocode.compact.state.SessionContext.create(workspace));
        }
        if (archiveWriter != null && compact != null) {
            try {
                archiveWriter = dinocode.session.archive.Writer.create(
                        compact.session().sessionDir());
                newSession.setArchiveCallbacks(archiveWriter::archiveAppend, archiveWriter::archiveReplace);
            } catch (IOException e) {
                renderer.failure("新会话存档开启失败: " + e.getMessage());
                archiveWriter = null;
            }
        }
        session = newSession;
        usageIn = 0; // F17：累计 token 归零
        usageOut = 0;
        activeSkills.clear();
        if (hookEngine != null) {
            hookEngine.resetForNewSession(); // ch12 N5：only_once 集合清空
        }
        // ch12 F9：SessionStart（/clear 新建会话后）
        ensureSessionAgent();
        sessionAgent.dispatchSessionHook(dinocode.hook.Event.SESSION_START, sessionId(), Map.of());
    }

    private void turn(String input) {
        turn(input, true);
    }

    /** 手动压缩（ch08 F22/F23/F37/F24）：与主循环互斥（tryLock 抢不到说明正在生成）。 */
    private void handleCompact() {
        if (compact == null) {
            renderer.notice("上下文管理未启用。");
            return;
        }
        if (!compact.tryAcquireRun()) {
            renderer.notice("正在生成回复，请等本轮结束后再压缩。");
            return;
        }
        try {
            long before = Token.estimateTokens(compact.getUsageAnchor(),
                    session.getMessages(), compact.getAnchorMsgLen());
            ContextCompactor.Result r = ContextCompactor.manage(new ContextCompactor.Input(
                    session.getMessages(), provider, compact.contextWindow,
                    registry.definitions(), compact.replacement(), compact.recovery(),
                    compact.autoTracking(), compact.session(),
                    compact.getUsageAnchor(), compact.getAnchorMsgLen(), before,
                    ContextCompactor.TriggerKind.MANUAL));
            if (r.newMsgs() != null) {
                session.replaceMessages(r.newMsgs()); // 摘要后的新历史（F22）
            }
            renderer.notice(String.format("已压缩，token 从 %d 降至 %d", r.beforeTokens(), r.afterTokens()));
        } catch (dinocode.compact.CompactException e) {
            renderer.failure("压缩失败: " + e.getMessage());
        } finally {
            compact.releaseRun();
        }
    }

    /**
     * ch09 F17~F24：/resume 会话恢复——列出有效会话（按修改时间倒序），用户输入编号选择，
     * 空输入/Esc 取消。行式菜单适配 JLine 阻塞读行模型（AC11）。
     */
    private void handleResume() {
        if (generating) {
            renderer.notice("请等待当前任务完成。"); // F46 互斥
            return;
        }
        if (workspace == null) {
            renderer.notice("会话存档未启用。");
            return;
        }
        var sessions = dinocode.session.archive.SessionArchive.list(
                workspace.resolve(".dino").resolve("sessions"));
        if (sessions.isEmpty()) {
            renderer.notice("没有可恢复的历史会话。");
            return;
        }
        renderer.notice("选择要恢复的会话（输入编号，直接回车取消）：");
        for (int i = 0; i < sessions.size() && i < 10; i++) {
            var info = sessions.get(i);
            renderer.notice(String.format("  [%d] %s · %s · %s · %s",
                    i + 1, info.title(), dinocode.session.archive.SessionArchive.relativeTime(info.modifiedAt()),
                    info.model().isEmpty() ? "-" : info.model(), humanSize(info.size())));
        }
        try {
            String choice = reader.readLine(Ansi.GREEN + "resume ❯ " + Ansi.RESET).strip();
            if (choice.isEmpty()) {
                renderer.notice("已取消恢复。");
                return;
            }
            int idx;
            try {
                idx = Integer.parseInt(choice) - 1;
            } catch (NumberFormatException e) {
                renderer.notice("无效编号，已取消恢复。");
                return;
            }
            if (idx < 0 || idx >= sessions.size()) {
                renderer.notice("编号超出范围，已取消恢复。");
                return;
            }
            resumeSession(sessions.get(idx));
        } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
            renderer.notice("已取消恢复。");
        }
    }

    /** 执行恢复流程（F21/F22/F23 + ch12 F9 SessionEnd/SessionResume）。 */
    private void resumeSession(dinocode.session.archive.SessionArchive.SessionInfo info) {
        try {
            // ch12 F9：SessionEnd（切换离开旧会话前）
            ensureSessionAgent();
            sessionAgent.dispatchSessionHook(dinocode.hook.Event.SESSION_END, sessionId(), Map.of());
            // 1. 加载消息（从最后 compact 标记之后；坏行跳过；孤立工具调用截断）
            List<Message> msgs = dinocode.session.archive.SessionArchive.load(info.dir());
            // 2. 时间跨度提醒（F21-5/AC17）：最后消息距现在超 6 小时
            java.time.Duration gap = java.time.Duration.between(
                    dinocode.session.archive.SessionArchive.lastModifiedOf(info.dir()),
                    java.time.Instant.now());
            if (gap.toHours() >= 6) {
                long hours = gap.toHours();
                String span = hours >= 24 ? (gap.toDays() + " 天") : (hours + " 小时");
                msgs.add(Message.user("[系统提示] 本会话已暂停 " + span
                        + "。部分上下文可能已过时，如需最新信息请重新读取相关文件。"));
            }
            // 3. 切换会话：新 Session（回调指向新 Writer）+ 替换 SessionContext
            dinocode.session.archive.Writer newWriter = dinocode.session.archive.Writer.open(info.dir());
            session = Session.fromMessages(info.id(), msgs,
                    newWriter::archiveAppend, newWriter::archiveReplace);
            compact.resetSession(dinocode.compact.state.SessionContext.open(workspace, info.id()));
            activeSkills.clear();
            if (hookEngine != null) {
                hookEngine.resetForNewSession(); // ch12 N5
            }
            // ch12 F9：SessionResume（恢复完成后、首条 user 消息之前）
            sessionAgent.dispatchSessionHook(dinocode.hook.Event.SESSION_RESUME, info.id(), Map.of());
            // 4. F24：原新会话的 JSONL 保留不删
            renderer.notice("已恢复会话 " + info.id() + "，共 " + msgs.size() + " 条消息");
        } catch (Exception e) {
            renderer.failure("恢复失败: " + e.getMessage()); // N5 单点错误降级
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        }
        return String.format("%.1fMB", bytes / 1024.0 / 1024);
    }

    private void turn(String input, boolean intoHistory) {
        if (intoHistory) {
            // ch12 F9/F32：UserPromptSubmit hook（写历史前，可拦截）
            ensureSessionAgent();
            var result = sessionAgent.dispatchSessionHook(
                    dinocode.hook.Event.USER_PROMPT_SUBMIT, sessionId(), Map.of("prompt", input));
            if (result.blocked()) {
                renderer.failure("[hook " + result.blockingHookName() + "] " + result.reason());
                return; // 阻止消息进入对话历史，焦点回输入框
            }
            // ch13 F5：注入上一轮后台子 Agent 的完成通知（主 Agent 下一轮可见）
            String notifications = drainTaskNotifications();
            if (notifications != null) {
                session.append(Message.user(notifications));
            }
            session.append(Message.user(input)); // ch09：经 append 触发 JSONL 存档回调
        }
        session.setLastActive(System.currentTimeMillis());
        saveSessionQuietly();

        Spinner spinner = new Spinner(terminal.writer());
        generating = true;
        interrupted = false;
        boolean receivedAny = false;
        CancelToken cancel = new CancelToken();
        turnCancel = cancel;
        spinner.start();
        try (TurnStream stream = new Agent(provider, registry, "0.1.0", engine, compact, memMgr,
                instructionText + skillContext(), memoryText, hookEngine)
                .run(session.getMessages(), currentMaxTokens(), mode, cancel)) {
            TurnEvent event;
            while ((event = stream.next()) != null) {
                if (!receivedAny) {
                    spinner.stop();
                    receivedAny = true;
                }
                switch (event) {
                    case TurnEvent.Text text -> renderer.text(text.delta());
                    case TurnEvent.Thinking thinking -> renderer.thinking(thinking.delta());
                    case TurnEvent.ToolStart start -> renderer.toolLine(start.name(), start.argsPreview());
                    case TurnEvent.ToolEnd end -> renderer.toolSummary(end.summary(), end.isError());
                    case TurnEvent.UsageReport report -> accumulateUsage(report.usage());
                    case TurnEvent.Iter iter -> renderer.iter(iter.iter());
                    case TurnEvent.Notice notice -> renderer.notice(notice.message());
                    case TurnEvent.Approval approval -> {
                        // 人在回路（ch06 F8）：暂停流式渲染，弹三选一菜单
                        spinner.ensureStopped();
                        handleApproval(approval.request());
                        receivedAny = false; // 菜单关闭后恢复 spinner
                        spinner.start();
                    }
                    case TurnEvent.Done done -> renderer.done(done.usage());
                    case TurnEvent.Error error -> renderer.failure(error.message());
                }
            }
        } finally {
            if (!receivedAny) {
                spinner.stop();
            }
            turnCancel = null;
            generating = false;
        }

        if (interrupted) {
            renderer.notice("· 已中断");
        }
        session.setLastActive(System.currentTimeMillis());
        saveSessionQuietly();
    }

    /**
     * 人在回路三选一（ch06 F8）：多行待批准块 + 菜单。
     * 数字键 1/2/3 直选；回车确认默认「允许本次」；Esc/Ctrl+C 取消本轮（回传 DENY_ONCE 解阻塞）。
     */
    private void handleApproval(ApprovalRequest request) {
        pendingApproval = request;
        PrintWriter out = terminal.writer();
        out.println();
        out.println(Ansi.YELLOW + "● " + request.name() + "(" + request.args() + ")" + Ansi.RESET);
        out.println(Ansi.DIM + "  " + request.reason() + Ansi.RESET);
        out.println("是否继续?  [1] 允许本次  [2] 永久允许（写入本地配置）  [3] 拒绝本次");
        out.println(Ansi.DIM + "  数字键选择，Esc 取消" + Ansi.RESET);
        out.flush();

        try {
            while (pendingApproval != null) {
                int ch = terminal.reader().read();
                switch (ch) {
                    case '1' -> submitOutcome(Outcome.ALLOW_ONCE);
                    case '2' -> submitOutcome(Outcome.ALLOW_FOREVER);
                    case '3' -> submitOutcome(Outcome.DENY_ONCE);
                    case '\r', '\n' -> submitOutcome(Outcome.ALLOW_ONCE); // 回车=默认允许本次
                    case 27 -> cancelApproval(); // Esc
                    default -> {
                        // 忽略其他按键
                    }
                }
            }
        } catch (IOException e) {
            submitOutcome(Outcome.DENY_ONCE); // 读输入失败：兜底拒绝，解阻塞
        }
    }

    /** 回传用户决策并退出 APPROVING 态。 */
    private void submitOutcome(Outcome outcome) {
        ApprovalRequest request = pendingApproval;
        pendingApproval = null;
        if (request != null) {
            request.respond().offer(outcome);
        }
    }

    /** Esc 取消待批准：兜底 DENY_ONCE 解 agent 阻塞，再取消本轮（N4）。 */
    private void cancelApproval() {
        submitOutcome(Outcome.DENY_ONCE);
        interrupted = true;
        CancelToken cancel = turnCancel;
        if (cancel != null) {
            cancel.cancel();
        }
    }

    /** 会话累计 token 用量（ch04 F8）。 */
    private void accumulateUsage(Usage usage) {
        if (usage == null) {
            return;
        }
        if (usage.inputTokens() != null) {
            usageIn += usage.inputTokens();
        }
        if (usage.outputTokens() != null) {
            usageOut += usage.outputTokens();
        }
    }

    private void onInterrupt() {
        if (pendingApproval != null) { // APPROVING 态：取消本轮 + 兜底解阻塞
            cancelApproval();
            return;
        }
        if (generating) {
            interrupted = true;
            CancelToken cancel = turnCancel;
            if (cancel != null) {
                cancel.cancel(); // 取消本轮 Loop（关底层流），不退出程序
            }
        }
        // 等待输入阶段由 JLine 抛 UserInterruptException，见 loop()
    }

    private int currentMaxTokens() {
        Integer override = session.getSettings().maxTokensOverride();
        return override != null ? override : config.maxTokens();
    }

    private void saveSessionQuietly() {
        if (session.getMessages().isEmpty()) {
            return;
        }
        try {
            store.save(session);
        } catch (IOException e) {
            renderer.failure("会话保存失败: " + e.getMessage());
        }
    }

    private void closeTerminal() {
        if (terminal != null) {
            try {
                terminal.close();
            } catch (IOException ignored) {
                // 退出路径，忽略
            }
        }
    }
}
