package dinocode.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dinocode.core.Message;
import dinocode.provider.OpenAiProvider;
import dinocode.tool.Result;
import dinocode.tool.Tool;
import dinocode.tool.ToolRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线端到端（AC8）：OpenAiProvider + Agent + 工具走通「请求#1 工具调用 → 执行 → 回灌 → 请求#2 最终答复」，
 * 不依赖真实 key。
 */
class AgentHttpE2eTest {

    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    private static HttpServer server;
    private static int port;

    private static final class EchoTool implements Tool {
        @Override
        public String name() {
            return "Echo";
        }

        @Override
        public String description() {
            return "echo";
        }

        @Override
        public Map<String, Object> schema() {
            return Map.of("type", "object");
        }

        @Override
        public Result execute(Map<String, Object> args) {
            return Result.ok("echo 结果");
        }
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/v1/chat/completions", AgentHttpE2eTest::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        REQUEST_COUNT.incrementAndGet();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            if (body.contains("tool_call_id")) {
                // 请求#2：已回灌工具结果 → 返回最终答复
                send(out, "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"完成\"}}]}\n\n");
                send(out, "data: [DONE]\n\n");
            } else {
                // 请求#1：返回一个 Echo 工具调用
                send(out, "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"Echo\",\"arguments\":\"\"}}]}}]}\n\n");
                send(out, "data: [DONE]\n\n");
            }
        }
    }

    private static void send(OutputStream out, String sse) throws IOException {
        out.write(sse.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Test
    void fullToolLoopOverHttp() {
        OpenAiProvider provider = new OpenAiProvider("http://127.0.0.1:" + port + "/v1", "k", "gpt-test");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        List<Message> history = new ArrayList<>();
        history.add(Message.user("帮我做点事"));

        List<TurnEvent> events = new ArrayList<>();
        TurnEvent event;
        try (TurnStream stream = new Agent(provider, registry).run(history, 4096)) {
            while ((event = stream.next()) != null) {
                events.add(event);
            }
        }

        // 工具行出现（START + END）
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolStart s && s.name().equals("Echo")));
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolEnd));
        // 最终答复出现
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.Text t && t.delta().equals("完成")));
        // 两轮请求（请求#1 + 请求#2）
        assertEquals(2, REQUEST_COUNT.get());
    }
}
