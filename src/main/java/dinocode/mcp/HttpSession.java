package dinocode.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streamable HTTP 传输会话（ch07 F5/F6）：请求-响应式 JSON-RPC over HTTP POST。
 * 不订阅独立 SSE 通道（F5 决策）；headers 注入每次请求（鉴权用）。
 *
 * <p>Streamable HTTP：POST JSON-RPC → 响应可为 application/json（直接返回）
 * 或 text/event-stream（本实现请求 Accept 只声明 json，要求 server 返回 json）。
 */
final class HttpSession extends CallerSession {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI endpoint;
    private final Map<String, String> headers;
    private final AtomicLong idGen = new AtomicLong();

    HttpSession(McpConfig.ServerConfig cfg) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.endpoint = URI.create(cfg.url());
        this.headers = cfg.headers();
    }

    @Override
    protected synchronized JsonNode request(String method, Object params) throws Exception {
        long id = idGen.incrementAndGet();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) {
            req.put("params", params);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(timeoutMillis()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(req)));
        headers.forEach(builder::header); // F5：headers 注入每次请求
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        JsonNode body;
        try {
            body = JSON.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new IOException("响应不是 JSON: " + truncate(response.body()));
        }
        if (body.has("error")) {
            throw new IOException("协议错误: " + body.path("error").path("message").asText("unknown"));
        }
        return body.path("result");
    }

    @Override
    JsonNode callToolNode(String name, Map<String, Object> arguments) throws Exception {
        return request("tools/call",
                Map.of("name", name, "arguments", arguments == null ? Map.of() : arguments));
    }

    /** 握手 + 列工具（F6 前两步）；返回工具数组节点。 */
    JsonNode initializeAndListTools(String clientName, String clientVersion) throws Exception {
        JsonNode initResult = request("initialize", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", clientName, "version", clientVersion)));
        String serverName = initResult.path("serverInfo").path("name").asText("");
        if (!serverName.isEmpty()) {
            System.err.println("[mcp] connected: " + serverName);
        }
        JsonNode tools = request("tools/list", Map.of());
        return tools.path("tools");
    }

    @Override
    public void close() {
        // Streamable HTTP 会话释放：DELETE endpoint（F11）；失败静默——退出路径
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(2))
                    .DELETE();
            headers.forEach(builder::header);
            http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() <= 200 ? s : s.substring(0, 200) + "…");
    }
}
