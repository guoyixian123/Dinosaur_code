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

/**
 * 组装入口（见 spec 设计骨架）：
 * 加载配置 → 建 Provider → 恢复会话 → 进入交互循环。
 */
public final class Main {

    public static void main(String[] args) {
        AppConfig config = loadConfig(args);

        SessionStore store = SessionStore.defaultStore();
        SessionStore.LoadResult loaded = store.loadLatest();
        if (loaded.hadCorrupt()) {
            System.out.println("提示: 检测到损坏的会话文件，已跳过");
        }

        boolean restored = loaded.session() != null;
        Session session = restored
                ? loaded.session()
                : new Session(SessionStore.newSessionId(),
                        System.currentTimeMillis(), new ArrayList<>(), SessionSettings.EMPTY);

        ChatProvider provider = ProviderFactory.create(config);
        ToolRegistry registry = ToolRegistry.createDefault();
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();

        // ch07：MCP 客户端——加载配置、并发连接 server、适配注册远端工具；退出时统一关闭
        McpConfig mcpConfig = dinocode.mcp.ConfigLoader.loadConfig(root);
        McpManager mcpManager = McpManager.start(mcpConfig, "0.1.0");
        Runtime.getRuntime().addShutdownHook(new Thread(mcpManager::close, "mcp-shutdown"));
        for (Tool tool : mcpManager.tools()) {
            registry.register(tool);
        }

        // ch06：项目根沙箱 + 三层规则配置 + 启动默认模式
        PermissionEngine engine = PermissionEngine.create(root);
        // ch08：上下文管理状态（会话目录 + 账本 + 熔断 + 锚点），进程启动时生成一次（F34/F35）
        CompactContext compact = new CompactContext(SessionContext.create(root),
                config.effectiveContextWindow());
        int exitCode = new Tui(config, provider, registry, engine, compact, store, session, restored).run();
        mcpManager.close(); // 正常退出路径（shutdown hook 兜底异常退出）
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
