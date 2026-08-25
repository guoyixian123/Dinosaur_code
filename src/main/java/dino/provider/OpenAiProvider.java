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
 * OpenAI 协议适配器（chat completions，stream=true）。
 * 也服务于任意 OpenAI 兼容端点（base_url 可配，见 spec 能力清单 8）。
 */
public final class OpenAiProvider extends AbstractHttpProvider {

    public OpenAiProvider(String baseUrl, String apiKey, String model) {
        super(baseUrl, apiKey, model);
    }

    @Override
    protected HttpRequest buildRequest(ChatRequest request) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        ArrayNode messages = JSON.createArrayNode();
        for (Message m : request.history()) {
            ObjectNode message = messages.addObject();
            message.put("role", m.role().wire());
            message.put("content", m.content());
        }
        body.put("messages", messages);
        body.put("stream", true);
        body.put("max_tokens", request.maxTokens());
        // 让最后一个数据块带回用量，供终端显示（checklist §B）
        body.put("stream_options", Map.of("include_usage", true));

        return HttpRequest.newBuilder(uri("/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
    }

    @Override
    protected EventStream streamFrom(InputStream body) {
        return new OpenAiEventStream(body);
    }

    /** 包内可见，便于单测直接回放 fixture。 */
    static final class OpenAiEventStream extends HttpEventStream {

        private Usage usage = Usage.UNKNOWN;

        OpenAiEventStream(InputStream body) {
            super(body);
        }

        @Override
        protected ChatEvent map(SseReader.SseEvent event) {
            String data = event.data();
            if (data == null || data.isBlank()) {
                return null;
            }
            if ("[DONE]".equals(data.trim())) {
                return new ChatEvent.Done(usage);
            }
            JsonNode root;
            try {
                root = JSON.readTree(data);
            } catch (JsonProcessingException e) {
                return null;
            }
            JsonNode usageNode = root.path("usage");
            if (usageNode.isObject() && usageNode.has("prompt_tokens")) {
                usage = new Usage(
                        usageNode.path("prompt_tokens").asInt(),
                        usageNode.path("completion_tokens").asInt());
            }
            JsonNode delta = root.path("choices").path(0).path("delta");
            // 推理模型（DeepSeek reasoner / 千问思考）的思考流 → 思考增量，暗色显示
            JsonNode reasoning = delta.path("reasoning_content");
            if (reasoning.isTextual() && !reasoning.asText().isEmpty()) {
                return new ChatEvent.ThinkingDelta(reasoning.asText());
            }
            JsonNode content = delta.path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                return new ChatEvent.TextDelta(content.asText());
            }
            return null;
        }
    }
}
