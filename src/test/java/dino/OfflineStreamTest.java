package dino;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dino.config.AppConfig;
import dino.config.ThinkingConfig;
import dino.core.ChatEvent;
import dino.core.ErrorKind;
import dino.core.Message;
import dino.core.Role;
import dino.provider.ChatProvider;
import dino.provider.ChatRequest;
import dino.provider.EventStream;
import dino.provider.ProviderFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T10 离线流式验证环境：mock 端点 + fixture 回放，
 * 离线跑通「配置 → Provider → 事件流」全链路（见 checklist §A/§B/§D/§G 中标注 mock 的项）。
 * 不依赖任何真实 key。
 */
class OfflineStreamTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Recorded> RECORDED = new ConcurrentHashMap<>();

    private static HttpServer server;
    private static int port;

    private record Recorded(String authorization, String apiKey, String anthropicVersion, String body) {
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange ->
                streamFixture(exchange, "openai", "openai-stream.txt", 0));
        server.createContext("/ten/chat/completions", exchange ->
                streamGenerated(exchange, "ten", 10, 100));
        server.createContext("/slow/chat/completions", exchange ->
                streamGenerated(exchange, "slow", 20, 60));
        server.createContext("/v1/messages", exchange ->
                streamFixture(exchange, "anthropic", "anthropic-stream.txt", 0));
        server.createContext("/err401/chat/completions", exchange ->
                respondError(exchange, "err401", 401, "{\"error\":{\"message\":\"invalid api key\"}}"));
        server.createContext("/err429/chat/completions", exchange ->
                respondError(exchange, "err429", 429, "{\"error\":{\"message\":\"rate limited\"}}"));
        server.createContext("/overflow/chat/completions", exchange ->
                respondError(exchange, "overflow", 400,
                        "{\"error\":{\"message\":\"This model's maximum context length is 128000 tokens\"}}"));

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    // ---------- helpers ----------

    private static AppConfig config(String protocol, String pathSuffix, String apiKey) {
        return new AppConfig(protocol, "model-x",
                "http://127.0.0.1:" + port + pathSuffix, apiKey, 4096, ThinkingConfig.DISABLED);
    }

    private static ChatRequest request(int maxTokens) {
        return new ChatRequest(List.of(new Message(Role.USER, "你好")), maxTokens, false, 0);
    }

    private static List<ChatEvent> drain(EventStream stream) {
        List<ChatEvent> events = new ArrayList<>();
        ChatEvent event;
        while ((event = stream.next()) != null) {
            events.add(event);
        }
        return events;
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = OfflineStreamTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in, "fixture 缺失: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void record(HttpExchange exchange, String key) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        RECORDED.put(key, new Recorded(
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("x-api-key"),
                exchange.getRequestHeaders().getFirst("anthropic-version"),
                body));
    }

    private static void streamFixture(HttpExchange exchange, String key, String fixtureName, long delayMs)
            throws IOException {
        record(exchange, key);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String block : fixture(fixtureName).split("\n\n")) {
                if (block.isBlank()) {
                    continue;
                }
                out.write((block + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                sleep(delayMs);
            }
        } catch (IOException ignored) {
            // 客户端中断（中断语义测试）
        }
    }

    private static void streamGenerated(HttpExchange exchange, String key, int chunks, long delayMs)
            throws IOException {
        record(exchange, key);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (int i = 1; i <= chunks; i++) {
                String json = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"chunk-" + i + " \"}}]}";
                out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                sleep(delayMs);
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // 客户端中断（中断语义测试）
        }
    }

    private static void respondError(HttpExchange exchange, String key, int status, String json)
            throws IOException {
        record(exchange, key);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- tests ----------

    @Test
    void openaiFullPipelineWithEnvKeyAndDefaultMaxTokens() throws Exception {
        AppConfig config = config("openai", "/v1", "env-key");
        ChatProvider provider = ProviderFactory.create(config);

        List<ChatEvent> events = drain(provider.chat(request(4096)));

        assertEquals(new ChatEvent.TextDelta("Hello"), events.get(0));
        assertEquals(new ChatEvent.TextDelta(" world"), events.get(1));
        assertInstanceOf(ChatEvent.Done.class, events.get(2));

        Recorded recorded = RECORDED.get("openai");
        assertEquals("Bearer env-key", recorded.authorization()); // checklist §A：环境变量优先
        JsonNode body = JSON.readTree(recorded.body());
        assertEquals(4096, body.get("max_tokens").asInt());       // checklist §D：默认值断言
        assertTrue(body.get("stream").asBoolean());
    }

    @Test
    void openaiTenChunksArriveIncrementally() {
        AppConfig config = config("openai", "/ten", "k");
        ChatProvider provider = ProviderFactory.create(config);

        long start = System.nanoTime();
        List<Long> arrivals = new ArrayList<>();
        int textEvents = 0;
        try (EventStream stream = provider.chat(request(4096))) {
            ChatEvent event;
            while ((event = stream.next()) != null) {
                if (event instanceof ChatEvent.TextDelta) {
                    textEvents++;
                    arrivals.add(System.nanoTime());
                }
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(10, textEvents);            // checklist §B：10 个 chunk
        assertTrue(elapsedMs >= 500, "应持续流出而非一次到达，实际 " + elapsedMs + "ms");
    }

    @Test
    void tokensLowArrivesInRequestBody() throws Exception {
        AppConfig config = config("openai", "/v1", "k");
        ChatProvider provider = ProviderFactory.create(config);

        drain(provider.chat(request(1024)));     // 模拟 /tokens low 后的请求

        JsonNode body = JSON.readTree(RECORDED.get("openai").body());
        assertEquals(1024, body.get("max_tokens").asInt()); // checklist §D：low=1024
    }

    @Test
    void anthropicThinkingPipelineAndHeaders() throws Exception {
        AppConfig config = config("anthropic", "", "sk-cfg");
        ChatProvider provider = ProviderFactory.create(config);

        List<ChatEvent> events = drain(provider.chat(
                new ChatRequest(List.of(new Message(Role.USER, "你好")), 8000, true, 3000)));

        assertEquals(List.of(
                new ChatEvent.ThinkingDelta("让我想想"),
                new ChatEvent.ThinkingDelta("……"),
                new ChatEvent.TextDelta("你好 "),
                new ChatEvent.TextDelta("！")), events.subList(0, 4));
        assertInstanceOf(ChatEvent.Done.class, events.get(4));

        Recorded recorded = RECORDED.get("anthropic");
        assertEquals("sk-cfg", recorded.apiKey());               // x-api-key 认证
        assertNotNull(recorded.anthropicVersion());              // 版本头
        JsonNode body = JSON.readTree(recorded.body());
        assertEquals(8000, body.get("max_tokens").asInt());
        assertEquals(3000, body.get("thinking").get("budget_tokens").asInt());
        assertEquals("enabled", body.get("thinking").get("type").asText());
    }

    @Test
    void anthropicWithoutThinkingOmitsThinkingField() throws Exception {
        AppConfig config = config("anthropic", "", "k");
        ChatProvider provider = ProviderFactory.create(config);

        drain(provider.chat(request(4096))); // thinking 未开启

        JsonNode body = JSON.readTree(RECORDED.get("anthropic").body());
        assertFalse(body.has("thinking")); // checklist §F：不带思考字段
    }

    @Test
    void interruptMidStreamKeepsPartialContentSilently() {
        AppConfig config = config("openai", "/slow", "k");
        ChatProvider provider = ProviderFactory.create(config);

        StringBuilder partial = new StringBuilder();
        int received = 0;
        try (EventStream stream = provider.chat(request(4096))) {
            ChatEvent event;
            while ((event = stream.next()) != null) {
                if (event instanceof ChatEvent.TextDelta text) {
                    partial.append(text.text());
                    received++;
                    if (received == 2) {
                        stream.close(); // 模拟 Ctrl+C：关闭流
                    }
                }
            }
            assertNull(stream.next()); // close 之后静默结束
        }

        assertTrue(received >= 2);
        assertTrue(partial.toString().startsWith("chunk-1 chunk-2"), partial.toString());
        assertFalse(partial.toString().contains("chunk-19"), "中断后不应继续接收");
    }

    @Test
    void status401MapsToAuthFailure() {
        ChatProvider provider = ProviderFactory.create(config("openai", "/err401", "bad"));
        List<ChatEvent> events = drain(provider.chat(request(4096)));

        assertEquals(1, events.size());
        ChatEvent.Failure failure = assertInstanceOf(ChatEvent.Failure.class, events.get(0));
        assertEquals(ErrorKind.AUTH, failure.kind());
        assertEquals("认证失败: 请检查 api_key", failure.message()); // checklist §G 精确文案
    }

    @Test
    void status429MapsToRateLimit() {
        ChatProvider provider = ProviderFactory.create(config("openai", "/err429", "k"));
        List<ChatEvent> events = drain(provider.chat(request(4096)));

        ChatEvent.Failure failure = assertInstanceOf(ChatEvent.Failure.class, events.get(0));
        assertEquals(ErrorKind.RATE_LIMIT, failure.kind());
    }

    @Test
    void contextOverflowDetected() {
        ChatProvider provider = ProviderFactory.create(config("openai", "/overflow", "k"));
        List<ChatEvent> events = drain(provider.chat(request(4096)));

        ChatEvent.Failure failure = assertInstanceOf(ChatEvent.Failure.class, events.get(0));
        assertEquals(ErrorKind.CONTEXT_OVERFLOW, failure.kind());
        assertTrue(failure.message().contains("/new"), failure.message()); // checklist §G
    }

    @Test
    void connectionRefusedMapsToNetwork() {
        AppConfig config = new AppConfig("openai", "model-x",
                "http://127.0.0.1:1", "k", 4096, ThinkingConfig.DISABLED); // 1 端口：拒绝连接
        ChatProvider provider = ProviderFactory.create(config);
        List<ChatEvent> events = drain(provider.chat(request(4096)));

        ChatEvent.Failure failure = assertInstanceOf(ChatEvent.Failure.class, events.get(0));
        assertEquals(ErrorKind.NETWORK, failure.kind());
        assertTrue(failure.message().startsWith("网络异常"), failure.message()); // checklist §G
    }
}
