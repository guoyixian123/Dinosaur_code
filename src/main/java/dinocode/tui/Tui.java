package dinocode.tui;

import dinocode.agent.Agent;
import dinocode.agent.TurnEvent;
import dinocode.agent.TurnStream;
import dinocode.config.AppConfig;
import dinocode.core.Message;
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
 * Ctrl+C 语义（checklist §E）：
 * - 生成中：JLine 不在读取状态，SIGINT 到达本类注册的信号处理器 → 中断本轮生成；
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

    private volatile TurnStream activeStream;
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
                line = reader.readLine(Ansi.GREEN + "❯ " + Ansi.RESET);
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

    /** @return false 表示应退出程序 */
    private boolean handleCommand(String input) {
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
        session.getMessages().add(Message.user(input));
        session.setLastActive(System.currentTimeMillis());
        saveSessionQuietly();

        Spinner spinner = new Spinner(terminal.writer());
        generating = true;
        interrupted = false;
        boolean receivedAny = false;
        spinner.start();
        try (TurnStream stream = new Agent(provider, registry).run(session.getMessages(), currentMaxTokens())) {
            activeStream = stream;
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
                    case TurnEvent.Done done -> renderer.done(done.usage());
                    case TurnEvent.Error error -> renderer.failure(error.message());
                }
            }
        } finally {
            if (!receivedAny) {
                spinner.stop();
            }
            activeStream = null;
            generating = false;
        }

        if (interrupted) {
            renderer.notice("· 已中断");
        }
        session.setLastActive(System.currentTimeMillis());
        saveSessionQuietly();
    }

    private void onInterrupt() {
        if (generating) {
            interrupted = true;
            TurnStream stream = activeStream;
            if (stream != null) {
                stream.close(); // 关闭底层连接，解除生成循环的阻塞
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
