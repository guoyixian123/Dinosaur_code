package dinocode;

import dinocode.config.AppConfig;
import dinocode.config.ConfigException;
import dinocode.config.ConfigLoader;
import dinocode.provider.ChatProvider;
import dinocode.provider.ProviderFactory;
import dinocode.session.Session;
import dinocode.session.SessionSettings;
import dinocode.session.SessionStore;
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
        int exitCode = new Tui(config, provider, registry, store, session, restored).run();
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
