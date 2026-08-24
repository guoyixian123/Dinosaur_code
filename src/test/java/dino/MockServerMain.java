package dino;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 独立运行的 mock 端点（测试作用域工具，不进产物）：
 * 供真实进程做端到端冒烟，不依赖任何真实 key。
 * 每请求一次返回一轮不同的回复，便于验证多轮。
 */
public final class MockServerMain {

    private static final AtomicInteger TURN = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18080;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/v1/chat/completions", MockServerMain::handle);
        server.createContext("/err401/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"message\":\"invalid api key\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        System.out.println("MOCK READY " + port);
        Thread.currentThread().join();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        // 记录每次请求体，供验证「多轮请求携带完整历史」
        java.nio.file.Files.writeString(java.nio.file.Path.of("mock-requests.log"),
                requestBody + System.lineSeparator(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        int turn = TURN.incrementAndGet();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            send(out, chunk("这是第 " + turn + " 轮"));
            sleep(50);
            send(out, chunk("的 mock 回复。"));
            send(out, "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}\n\n");
            send(out, "data: [DONE]\n\n");
        } catch (IOException ignored) {
            // 客户端中断
        }
    }

    private static String chunk(String text) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"}}]}\n\n";
    }

    private static void send(OutputStream out, String sse) throws IOException {
        out.write(sse.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private MockServerMain() {
    }
}
