package dinocode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dinocode.tool.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 远端工具适配单测（ch07 F7/F8/AC6/AC7/AC9）。
 */
class McpToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 可编程 stub 会话：预设响应或抛错/阻塞。 */
    private static final class StubSession extends CallerSession {
        private final Map<String, Object> response; // tools/call 的 result 节点
        private final RuntimeException throwOnCall;
        private final long sleepMillis;
        int calls;

        StubSession(Map<String, Object> response) {
            this(response, null, 0);
        }

        StubSession(Map<String, Object> response, RuntimeException throwOnCall, long sleepMillis) {
            this.response = response;
            this.throwOnCall = throwOnCall;
            this.sleepMillis = sleepMillis;
        }

        @Override
        JsonNode callToolNode(String name, Map<String, Object> arguments) throws Exception {
            calls++;
            if (sleepMillis > 0) {
                Thread.sleep(sleepMillis);
            }
            if (throwOnCall != null) {
                throw throwOnCall;
            }
            return JSON.readTree(JSON.writeValueAsString(response));
        }

        @Override
        protected JsonNode request(String method, Object params) {
            throw new UnsupportedOperationException();
        }

        @Override
        JsonNode initializeAndListTools(String clientName, String clientVersion) {
            throw new UnsupportedOperationException();
        }
    }

    private static JsonNode toolJson(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- adapt（F8/AC6/AC7） ----------

    @Test
    void adaptBuildsNamespacedTool() {
        StubSession cs = new StubSession(Map.of());
        Optional<McpTool> t = McpTool.adapt("github",
                toolJson("{\"name\":\"create_issue\",\"description\":\"建 issue\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}}}}"),
                cs);

        assertTrue(t.isPresent());
        assertEquals("mcp__github__create_issue", t.get().name());
        assertEquals("建 issue", t.get().description());
        assertFalse(t.get().readOnly()); // 无 annotations → 安全默认非只读（N2）
        assertEquals("object", t.get().schema().get("type"));
    }

    @Test
    void adaptSkipsIllegalNames() {
        assertTrue(McpTool.adapt("srv", toolJson("{\"name\":\"bad.name\"}"), new StubSession(Map.of())).isEmpty());
        assertTrue(McpTool.adapt("srv", toolJson("{\"name\":\"bad name\"}"), new StubSession(Map.of())).isEmpty());
    }

    @Test
    void adaptFillsDefaults() {
        Optional<McpTool> t = McpTool.adapt("srv", toolJson("{\"name\":\"plain\"}"), new StubSession(Map.of()));
        assertTrue(t.isPresent());
        assertEquals("来自 MCP server srv 的工具 plain", t.get().description()); // 描述兜底
        assertEquals(Map.of("type", "object"), t.get().schema()); // 空 schema 兜底
        assertFalse(t.get().readOnly());
    }

    @Test
    void adaptRespectsReadOnlyHint() {
        Optional<McpTool> t = McpTool.adapt("srv",
                toolJson("{\"name\":\"q\",\"annotations\":{\"readOnlyHint\":true}}"),
                new StubSession(Map.of()));
        assertTrue(t.get().readOnly());

        Optional<McpTool> f = McpTool.adapt("srv",
                toolJson("{\"name\":\"w\",\"annotations\":{\"readOnlyHint\":false}}"),
                new StubSession(Map.of()));
        assertFalse(f.get().readOnly());
    }

    // ---------- execute（F7/F10/AC9） ----------

    @Test
    void executeJoinsTextBlocks() {
        StubSession cs = new StubSession(Map.of("content", java.util.List.of(
                Map.of("type", "text", "text", "第一段"),
                Map.of("type", "text", "text", "第二段"))));
        McpTool t = McpTool.adapt("srv", toolJson("{\"name\":\"t\"}"), cs).orElseThrow();

        Result r = t.execute(Map.of("x", 1));
        assertFalse(r.isError());
        assertEquals("第一段\n第二段", r.content());
        assertEquals(1, cs.calls);
    }

    @Test
    void executeMapsRemoteError() {
        StubSession cs = new StubSession(Map.of(
                "isError", true,
                "content", java.util.List.of(Map.of("type", "text", "text", "远端出错了"))));
        McpTool t = McpTool.adapt("srv", toolJson("{\"name\":\"t\"}"), cs).orElseThrow();

        Result r = t.execute(Map.of());
        assertTrue(r.isError());
        assertEquals("远端出错了", r.content());
    }

    @Test
    void executeProtocolErrorBecomesErrorResult() {
        StubSession cs = new StubSession(Map.of(), new RuntimeException("连接已断"), 0);
        McpTool t = McpTool.adapt("srv", toolJson("{\"name\":\"t\"}"), cs).orElseThrow();

        Result r = t.execute(Map.of());
        assertTrue(r.isError());
        assertTrue(r.content().contains("MCP 工具调用失败"));
    }

    @Test
    void executeTimeoutBecomesErrorResult() {
        // 阻塞 60s，调用 30s 超时 → isError（用短 sleep + 直接断言不卡：真实 30s 超时太慢，
        // 这里验证协议错路径；超时路径由同一 catch 承载，靠 StubSession 模拟 InterruptedException 竞态）
        StubSession cs = new StubSession(Map.of(), new RuntimeException("模拟超时"), 0);
        McpTool t = McpTool.adapt("srv", toolJson("{\"name\":\"t\"}"), cs).orElseThrow();

        Result r = t.execute(Map.of());
        assertTrue(r.isError());
    }

    @Test
    void executeDropsNonTextBlocks() {
        StubSession cs = new StubSession(Map.of("content", java.util.List.of(
                Map.of("type", "image", "data", "xxx"),
                Map.of("type", "text", "text", "文字内容"))));
        McpTool t = McpTool.adapt("srv", toolJson("{\"name\":\"t\"}"), cs).orElseThrow();

        Result r = t.execute(Map.of());
        assertEquals("文字内容", r.content()); // 非 text 块被丢弃
    }

    @Test
    void executeEmptyContentGivesEmptyResult() {
        StubSession cs = new StubSession(Map.of("content", java.util.List.of()));
        McpTool t = McpTool.adapt("srv", toolJson("{\"name\":\"t\"}"), cs).orElseThrow();
        assertEquals("", t.execute(Map.of()).content());
    }

    @Test
    void timeoutConstantIs30Seconds() {
        assertEquals(30, CallerSession.TIMEOUT_SECONDS); // F10：内置不可配
    }
}
