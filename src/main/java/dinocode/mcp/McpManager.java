package dinocode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dinocode.tool.Tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 多 server 生命周期管理（ch07 F9/F11）：并发连接全部 server（每 server 30s 超时、
 * 失败隔离仅跳过自身）、缓存适配好的工具、退出时统一关闭（5s 兜底不死锁）。
 */
public final class McpManager implements AutoCloseable {

    private static final String CLIENT_NAME = "DinoCode";

    private final Object lock = new Object();
    private final List<CallerSession> sessions = new ArrayList<>();
    private final List<Tool> tools = new ArrayList<>();

    private McpManager() {
    }

    /**
     * 启动：并发连接所有配置中的 server，阻塞直到全部尝试结束（成功/失败/超时）。
     * 任一 server 失败只跳过自身 + stderr 告警（N1），不阻断启动。
     */
    public static McpManager start(McpConfig cfg, String version) {
        McpManager mgr = new McpManager();
        if (cfg.servers().isEmpty()) {
            return mgr;
        }
        CountDownLatch latch = new CountDownLatch(cfg.servers().size());
        for (Map.Entry<String, McpConfig.ServerConfig> e : cfg.servers().entrySet()) {
            Thread.ofVirtual().start(() -> {
                try {
                    connectOne(mgr, e.getKey(), e.getValue(), version);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(); // 每个 connectOne 自带 30s 上界（进程 waitFor / HTTP timeout / socket timeout）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (mgr.lock) {
            mgr.tools.sort(Comparator.comparing(Tool::name)); // 稳定排序：server 名已含于 fullName
        }
        return mgr;
    }

    /** 连接单个 server：构造会话 → 握手 + 列工具 → 适配注册；任何失败只跳过自身（F9）。 */
    private static void connectOne(McpManager mgr, String name, McpConfig.ServerConfig srv, String version) {
        CallerSession session = null;
        try {
            session = srv.type().equals("stdio")
                    ? new StdioSession(srv)
                    : new HttpSession(srv);
            JsonNode tools = session.initializeAndListTools(CLIENT_NAME, version);
            List<Tool> adapted = new ArrayList<>();
            for (JsonNode t : tools) {
                McpTool.adapt(name, t, session).ifPresent(adapted::add);
            }
            synchronized (mgr.lock) {
                mgr.sessions.add(session);
                mgr.tools.addAll(adapted);
            }
            System.err.println("[mcp] server " + name + " 就绪，注册 " + adapted.size() + " 个工具");
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            System.err.println("[mcp] warn: server " + name + " 连接失败，已跳过: " + reason);
            if (session != null) {
                session.close(); // 列工具失败的连接不泄漏
            }
        }
    }

    /** 适配好的工具列表（不可变；Agent 注册进工具中心）。 */
    public List<Tool> tools() {
        synchronized (lock) {
            return List.copyOf(tools);
        }
    }

    /** 统一关闭（F11/N7）：并发关闭各会话，5s 总兜底，绝不阻塞退出。 */
    @Override
    public void close() {
        List<CallerSession> toClose;
        synchronized (lock) {
            toClose = List.copyOf(sessions);
            sessions.clear();
        }
        if (toClose.isEmpty()) {
            return;
        }
        CountDownLatch done = new CountDownLatch(toClose.size());
        for (CallerSession s : toClose) {
            Thread.ofVirtual().start(() -> {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // 退出路径，忽略
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await(5, TimeUnit.SECONDS); // 兜底：某 server 卡住不拖死整个退出（N7）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
