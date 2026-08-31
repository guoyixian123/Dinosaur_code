package dinocode.tui;

import dinocode.agent.Agent;
import dinocode.agent.CancelToken;
import dinocode.agent.Mode;
import dinocode.agent.TurnEvent;
import dinocode.agent.TurnStream;
import dinocode.config.AppConfig;
import dinocode.core.Message;
import dinocode.core.Usage;
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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * 终端交互层：唯一接触终端的地方（见 spec 设计骨架）。
 *
 * Ctrl+C 语义（checklist §E + ch04 F7）：
 * - 生成中：JLine 不在读取状态，SIGINT 到达本类注册的信号处理器 → 取消本轮 Agent Loop（不退出）；
 * - 等待输入：终端处于 raw 模式，Ctrl+C 由 JLine 转为 UserInterruptException，
 *   输入行为空则退出，非空则仅清空当前行。
 */
public final class Tui {

    private final AppConfig config;
    private final ChatProvider provider;
    private final ToolRegistry registry;
    private final SessionStore store;
    private final boolean restored;

    private Session session;
    private Terminal terminal;
    private LineReader reader;
    private Renderer renderer;

    // ch04：模式跨轮保持；用量跨轮累加
    private Mode mode = Mode.NORMAL;
    private long usageIn;
    private long usageOut;

    private volatile CancelToken turnCancel;
    private volatile boolean generating;
    private volatile boolean interrupted;

    public Tui(AppConfig config, ChatProvider provider, ToolRegistry registry, SessionStore store,
               Session session, boolean restored) {
        this.config = config;
        this.provider = provider;
        this.registry = registry;
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
            renderer = new Renderer(terminal.writer());

            Banner.print(terminal.writer(), config,
                    restored ? "已恢复上次会话 " + session.getId() : "新会话");
            renderer.notice("提示: /plan 进入计划模式（只读工具），/do 按计划执行，/help 查看命令");

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

    /** 提示符：计划模式带 PLAN 徽标（ch04 F10）。 */
    private String prompt() {
        return mode == Mode.PLAN
                ? Ansi.GREEN + "❯ [PLAN] " + Ansi.RESET
                : Ansi.GREEN + "❯ " + Ansi.RESET;
    }

    /** @return false 表示应退出程序 */
    private boolean handleCommand(String input) {
        // ch04 F10：/plan 与 /do 在通用命令之外先识别
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
            mode = Mode.NORMAL;
            renderer.notice("已切回普通模式，按计划开始执行。");
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
        try (TurnStream stream = new Agent(provider, registry, "0.1.0")
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
