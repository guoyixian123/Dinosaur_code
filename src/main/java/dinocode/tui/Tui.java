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
    }

    /** @return 进程退出码 */
    public int run() {
        try {
            terminal = TerminalBuilder.builder()
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            terminal.handle(Terminal.Signal.INT, signal -> onInterrupt());
            reader = LineReaderBuilder.builder().terminal(terminal).build();
            // Shift+Tab（终端发送 ESC[Z）展开为 /mode 命令提交（ch06 F7，IDLE 态循环切换）
            bindShiftTab(reader);
            renderer = new Renderer(terminal.writer());

            Banner.print(terminal.writer(), config,
                    restored ? "已恢复上次会话 " + session.getId() : "新会话");
            renderer.notice("提示: Shift+Tab 切换权限模式，/plan 进入计划模式（只读工具），/do 按计划执行，/help 查看命令");

            loop();
            return 0;
        } catch (IOException e) {
            System.err.println("终端初始化失败: " + e.getMessage());
            return 1;
        } finally {
            closeTerminal();
        }
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
        // ch08 F21/F22：/compact 手动压缩（跳过阈值与熔断，无条件触发摘要）
        if ("/compact".equals(input)) {
            handleCompact();
            return true;
        }
        // /plan 与 /do 仍为计划工作流专用入口/出口（/do 固定回 default）
        if ("/plan".equals(input)) {
            mode = Mode.PLAN;
            renderer.notice("已进入计划模式（只读工具）。产出计划后用 /do 执行。");
            return true;
        }
        if ("/do".equals(input)) {
            if (mode != Mode.PLAN) {
                renderer.notice("当前不在计划模式，/do 仅用于执行 /plan 产出的计划。");
                return true;
            }
            mode = Mode.DEFAULT;
            renderer.notice("已切回默认模式，按计划开始执行。");
            turn(Reminder.EXECUTE_DIRECTIVE, false); // 指令本身不入历史
            return true;
        }

        CommandHandler.Result result = CommandHandler.handle(input, currentMaxTokens());
        switch (result.action()) {
            case EXIT -> {
                renderer.notice("再见 🦖");
                saveSessionQuietly();
                return false;
            }
            case NEW_SESSION -> {
                saveSessionQuietly();
                session = new Session(SessionStore.newSessionId(),
                        System.currentTimeMillis(), new ArrayList<>(), SessionSettings.EMPTY);
                renderer.notice("已开启新会话 " + session.getId());
            }
            case TOKENS_SET -> {
                session.setSettings(new SessionSettings(result.tokens()));
                saveSessionQuietly();
                renderer.notice("已设置最大输出为 " + result.tokens());
            }
            case PRINT -> renderer.notice(result.message());
        }
        return true;
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
                    registry.definitions(), compact.replacement, compact.recovery,
                    compact.autoTracking, compact.session,
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

    /** @param intoHistory false 表示指令由 Agent 直接消费、不写入历史（/do）。 */
    private void turn(String input, boolean intoHistory) {
        if (intoHistory) {
            session.getMessages().add(Message.user(input));
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
        try (TurnStream stream = new Agent(provider, registry, "0.1.0", engine)
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
