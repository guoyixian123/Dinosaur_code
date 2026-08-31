package dinocode.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * stdio 传输会话（ch07 F4/F6）：以 command+args 启动子进程，
 * 通过 stdin/stdout 按 JSON-RPC 逐行（LSP 式 Content-Length 帧或行分隔 JSON）通信。
 *
 * <p>MCP stdio 传输使用<b>换行分隔</b>的 JSON-RPC 消息。stderr 透传宿主 stderr（F4）；
 * close 时关 stdin → 等待 → 超时强杀（F11/N7）。
 */
final class StdioSession extends CallerSession {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process process;
    private final OutputStream stdin;
    private final BufferedReader stdout;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    StdioSession(McpConfig.ServerConfig cfg) throws IOException {
        java.util.List<String> argv = new java.util.ArrayList<>(cfg.args());
        argv.add(0, cfg.command());
        ProcessBuilder pb = new ProcessBuilder(argv);
        // env 与宿主环境合并，同名宿主变量被配置覆盖（F4）
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.putAll(cfg.env());
        pb.environment().putAll(env);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT); // stderr 透传
        this.process = pb.start();
        this.stdin = process.getOutputStream();
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        startReader();
    }

    /** 后台读循环：按行解析响应，按 id 唤醒等待者。 */
    private void startReader() {
        Thread.ofVirtual().start(() -> {
            try {
                String line;
                while ((line = stdout.readLine()) != null) {
                    handleLine(line);
                }
            } catch (IOException ignored) {
                // 进程退出/流关闭路径
            } finally {
                // 流结束：所有等待者按失败收尾
                for (CompletableFuture<JsonNode> f : pending.values()) {
                    f.completeExceptionally(new IOException("MCP server 进程已退出"));
                }
                pending.clear();
            }
        });
    }

    private void handleLine(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        JsonNode msg;
        try {
            msg = JSON.readTree(trimmed);
        } catch (JsonProcessingException e) {
            return; // 非 JSON 行（某些 server 的启动横幅）静默忽略
        }
        if (!msg.has("id")) {
            return; // 通知/请求（server→client）：本章不支持的能力，忽略
        }
        long id = msg.path("id").asLong(-1);
        CompletableFuture<JsonNode> f = id >= 0 ? pending.remove(id) : null;
        if (f == null) {
            return;
        }
        if (msg.has("error")) {
            f.completeExceptionally(new IOException("协议错误: " + msg.path("error").path("message").asText("unknown")));
        } else {
            f.complete(msg.path("result"));
        }
    }

    @Override
    protected synchronized JsonNode request(String method, Object params) throws Exception {
        long id = nextRequestId();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) {
            req.put("params", params);
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            stdin.write((JSON.writeValueAsString(req) + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            pending.remove(id);
            throw e;
        }
        try {
            return future.get(timeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IOException(cause);
        }
    }

    @Override
    JsonNode callToolNode(String name, Map<String, Object> arguments) throws Exception {
        return request("tools/call",
                Map.of("name", name, "arguments", arguments == null ? Map.of() : arguments));
    }

    /** 握手 + 列工具（F6 前两步）；返回工具数组节点。 */
    JsonNode initializeAndListTools(String clientName, String clientVersion) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", clientName, "version", clientVersion));
        JsonNode initResult = request("initialize", params);
        // 握手完成通知（MCP 协议要求）
        try {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("jsonrpc", "2.0");
            note.put("method", "notifications/initialized");
            stdin.write((JSON.writeValueAsString(note) + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException ignored) {
            // 通知失败不致命
        }
        String serverName = initResult.path("serverInfo").path("name").asText("");
        if (!serverName.isEmpty()) {
            System.err.println("[mcp] connected: " + serverName);
        }
        JsonNode tools = request("tools/list", Map.of());
        return tools.path("tools");
    }

    /** 关闭（F11/N7）：关 stdin → 等 2s → 强杀。 */
    @Override
    public void close() {
        try {
            stdin.close(); // server 看到 stdin EOF 自然退出
        } catch (IOException ignored) {
            //
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy(); // SIGTERM
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly(); // SIGKILL 兜底
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
