package dino.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dino.core.ChatEvent;
import dino.core.Message;
import dino.core.Usage;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anthropic 协议适配器（Messages，stream: true）。
 * max_tokens 为该协议强制参数（见 spec 设计骨架）；
 * thinking 开启时附加思考参数，参数约束以官方最新文档为准（tasks.md T7）。
 */
public final class AnthropicProvider extends AbstractHttpProvider {

    /** Messages API 版本头。 */
    static final String API_VERSION = "2023-06-01";

    public AnthropicProvider(String baseUrl, String apiKey, String model) {
        super(baseUrl, apiKey, model);
    }

    @Override
    protected HttpRequest buildRequest(ChatRequest request) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("max_tokens", request.maxTokens());
        ArrayNode messages = JSON.createArrayNode();
        for (Message m : request.history()) {
            ObjectNode message = messages.addObject();
            message.put("role", m.role().wire());
            message.put("content", m.content());
        }
        body.put("messages", messages);
        body.put("stream", true);
        if (request.thinkingEnabled()) {
            body.put("thinking", Map.of(
                    "type", "enabled",
                    "budget_tokens", request.thinkingBudget()));
        }

        return HttpRequest.newBuilder(uri("/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
    }

    @Override
    protected EventStream streamFrom(InputStream body) {
        return new AnthropicEventStream(body);
    }

    /** 包内可见，便于单测直接回放 fixture。 */
    static final class AnthropicEventStream extends HttpEventStream {

        private Usage usage = Usage.UNKNOWN;

        AnthropicEventStream(InputStream body) {
            super(body);
        }

        @Override
        protected ChatEvent map(SseReader.SseEvent event) {
            if ("ping".equals(event.event())) {
                return null;
            }
            String data = event.data();
            if (data == null || data.isBlank()) {
                return null;
            }
            JsonNode root;
            try {
                root = JSON.readTree(data);
            } catch (JsonProcessingException e) {
                return null;
            }
            String type = root.path("type").asText("");
            return switch (type) {
                case "message_start" -> {
                    JsonNode inputTokens = root.path("message").path("usage").path("input_tokens");
                    if (inputTokens.isInt()) {
                        usage = usage.merge(new Usage(inputTokens.asInt(), null));
                    }
                    yield null;
                }
                case "content_block_delta" -> {
                    JsonNode delta = root.path("delta");
                    String deltaType = delta.path("type").asText("");
                    if ("thinking_delta".equals(deltaType)) {
                        yield new ChatEvent.ThinkingDelta(delta.path("thinking").asText(""));
                    }
                    if ("text_delta".equals(deltaType)) {
                        yield new ChatEvent.TextDelta(delta.path("text").asText(""));
                    }
                    yield null;
                }
                case "message_delta" -> {
                    JsonNode outputTokens = root.path("usage").path("output_tokens");
                    if (outputTokens.isInt()) {
                        usage = usage.merge(new Usage(null, outputTokens.asInt()));
                    }
                    yield null;
                }
                case "message_stop" -> new ChatEvent.Done(usage);
                case "error" -> ApiErrors.fromStreamError(
                        root.path("error").path("type").asText(null),
                        root.path("error").path("message").asText(""));
                default -> null; // content_block_start/stop 等：忽略
            };
        }
    }
}
