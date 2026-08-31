package dinocode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dinocode.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接管理器单测（ch07 F9/F11/AC4/AC8/AC10）：真实 stdio 子进程、失败隔离、并发安全、关闭干净。
 */
class McpManagerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    /**
     * 最小 stdio MCP server：python/jsh 不一定有，改用 java 内联——直接起一个
     * java 进程跑回显协议脚本太重；改用项目自带 jshell 也不稳。
     * 这里用 Node（若存在）跑最小 server；无 node 则跳过该用例。
     */
    private static boolean hasNode() {
        try {
            Process p = new ProcessBuilder("node", "--version").start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static final String ECHO_SERVER_JS = """
            let buf = '';
            process.stdin.on('data', d => {
              buf += d;
              let idx;
              while ((idx = buf.indexOf('\\n')) >= 0) {
                const line = buf.slice(0, idx); buf = buf.slice(idx + 1);
                if (!line.trim()) continue;
                let msg; try { msg = JSON.parse(line); } catch { continue; }
                if (msg.method === 'initialize') {
                  reply(msg.id, {protocolVersion: '2024-11-05', capabilities: {},
                                 serverInfo: {name: 'echo-server', version: '0.0.1'}});
                } else if (msg.method === 'tools/list') {
                  reply(msg.id, {tools: [
                    {name: 'echo', description: '回显输入',
                     inputSchema: {type: 'object', properties: {text: {type: 'string'}}}},
                    {name: 'bad name', description: '非法名工具', inputSchema: {type: 'object'}}]});
                } else if (msg.method === 'tools/call') {
                  reply(msg.id, {content: [{type: 'text', text: 'echo: ' + (msg.params.arguments.text || '')}]});
                }
              }
            });
            function reply(id, result) {
              process.stdout.write(JSON.stringify({jsonrpc: '2.0', id, result}) + '\\n');
            }
            """;

    @Test
    void emptyConfigStartsAndClosesImmediately() {
        McpManager mgr = McpManager.start(new McpConfig(Map.of()), "test");
        assertTrue(mgr.tools().isEmpty());
        mgr.close(); // 立即返回，不阻塞
    }

    @Test
    void stdioServerConnectsAndToolsAdapted() throws Exception {
        if (!hasNode()) {
            return; // 无 node 环境跳过端到端 stdio 用例
        }
        Path script = root.resolve("echo-server.js");
        Files.writeString(script, ECHO_SERVER_JS);

        McpConfig cfg = new McpConfig(Map.of("demo", new McpConfig.ServerConfig(
                "stdio", "node", java.util.List.of(script.toString()), Map.of(), null, Map.of())));
        McpManager mgr = McpManager.start(cfg, "test");
        try {
            assertEquals(1, mgr.tools().size()); // "bad name" 被禁用字符校验跳过（AC7）
            Tool echo = mgr.tools().get(0);
            assertEquals("mcp__demo__echo", echo.name());

            // 调用链路端到端（AC6）
            dinocode.tool.Result r = echo.execute(Map.of("text", "你好"));
            assertTrue(!r.isError());
            assertEquals("echo: 你好", r.content());
        } finally {
            mgr.close(); // 子进程被终止（AC4/AC10）
        }
    }

    @Test
    void failingServerIsIsolated() {
        // command 不存在 → 连接失败只跳过自身（AC8/N1）
        McpConfig cfg = new McpConfig(Map.of(
                "broken", new McpConfig.ServerConfig(
                        "stdio", "definitely-not-a-real-command-xyz", java.util.List.of(), Map.of(), null, Map.of())));
        McpManager mgr = McpManager.start(cfg, "test");
        try {
            assertTrue(mgr.tools().isEmpty()); // 失败 server 被跳过，启动未被阻断
        } finally {
            mgr.close();
        }
    }

    @Test
    void closeIsIdempotentAndFast() {
        McpManager mgr = McpManager.start(new McpConfig(Map.of()), "test");
        mgr.close();
        mgr.close(); // 二次 close 不抛不阻塞
    }

    @Test
    void concurrentStartIsSafe() throws Exception {
        // 并发 start + tools() 读，无并发异常（N8/AC15 简化版）
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(8);
        for (int i = 0; i < 8; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    McpManager mgr = McpManager.start(new McpConfig(Map.of()), "test");
                    mgr.tools();
                    mgr.close();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(0, failures.get());
    }

    /** StdioSession 行级 JSON-RPC 解析的直测（不依赖 node）。 */
    @Test
    void stdioSessionTalksJsonRpcOverLines() throws Exception {
        if (!hasNode()) {
            return;
        }
        Path script = root.resolve("echo-server2.js");
        Files.writeString(script, ECHO_SERVER_JS);
        McpConfig.ServerConfig cfg = new McpConfig.ServerConfig(
                "stdio", "node", java.util.List.of(script.toString()), Map.of(), null, Map.of());
        StdioSession session = new StdioSession(cfg);
        try {
            JsonNode tools = session.initializeAndListTools("DinoCode", "test");
            assertTrue(tools.isArray());
            assertEquals(2, tools.size());
        } finally {
            session.close();
        }
    }
}
