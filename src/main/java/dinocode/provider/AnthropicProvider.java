package dinocode.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dinocode.config.ThinkingConfig;
import dinocode.core.ChatEvent;
import dinocode.core.Message;
import dinocode.core.Role;
import dinocode.core.ToolCall;
import dinocode.core.ToolDefinition;
import dinocode.core.ToolResult;
import dinocode.core.Usage;
import dinocode.prompt.Prompt;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic 协议适配器（Messages，stream: true）。
 * max_tokens 为该协议强制参数；thinking 开启时附加思考参数；
 * 工具调用（tool_use / tool_result）见 ch03 工具系统。
 */
public final class AnthropicProvider extends AbstractHttpProvider {

    /** Messages API 版本头。 */
    static final String API_VERSION = "2023-06-01";

    private final ThinkingConfig thinking;

    public AnthropicProvider(String baseUrl, String apiKey, String model, ThinkingConfig thinking) {
        super(baseUrl, apiKey, model);
        this.thinking = thinking;
    }

    @Override
    protected HttpRequest buildRequest(ChatRequest request) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("max_tokens", request.maxTokens());
        body.put("system", Prompt.SYSTEM_PROMPT);
        body.put("messages", toMessages(request.history()));
        body.put("stream", true);
        if (!request.tools().isEmpty()) {
            body.put("tools", toTools(request.tools()));
        }
        // 续答（历史含工具交互）时不启用 thinking，避免回灌签名导致的 400（见 plan 决策）
        if (thinking.enabled() && !hasToolTurns(request.history())) {
            body.put("thinking", Map.of("type", "enabled", "budget_tokens", thinking.budgetTokens()));
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

    // ---------- 请求体序列化 ----------

    private static ArrayNode toMessages(List<Message> history) {
        ArrayNode messages = JSON.createArrayNode();
        for (Message m : history) {
            switch (m.role()) {
                case Role.USER -> {
                    ObjectNode node = messages.addObject();
                    node.put("role", "user");
                    node.put("content", m.content());
                }
                case Role.ASSISTANT -> appendAssistant(messages, m);
                case Role.TOOL -> appendToolResults(messages, m);
            }
        }
        return messages;
    }

    private static void appendAssistant(ArrayNode messages, Message m) {
        ObjectNode node = messages.addObject();
        node.put("role", "assistant");
        ArrayNode content = node.putArray("content");
        if (!m.content().isEmpty()) {
            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text", m.content());
        }
        for (ToolCall c : m.toolCalls()) {
            ObjectNode toolUse = content.addObject();
            toolUse.put("type", "tool_use");
            toolUse.put("id", c.id());
            toolUse.put("name", c.name());
            toolUse.set("input", parseJson(c.arguments()));
        }
    }

    private static void appendToolResults(ArrayNode messages, Message m) {
        // Anthropic 要求 tool_result 由 user 角色提交
        ObjectNode node = messages.addObject();
        node.put("role", "user");
        ArrayNode content = node.putArray("content");
        for (ToolResult r : m.toolResults()) {
            ObjectNode tr = content.addObject();
            tr.put("type", "tool_result");
            tr.put("tool_use_id", r.toolCallId());
            tr.put("content", r.content());
            if (r.isError()) {
                tr.put("is_error", true);
            }
        }
    }

    private static ArrayNode toTools(List<ToolDefinition> tools) {
        ArrayNode arr = JSON.createArrayNode();
        for (ToolDefinition d : tools) {
            ObjectNode tool = arr.addObject();
            tool.put("name", d.name());
            tool.put("description", d.description());
            tool.set("input_schema", JSON.valueToTree(d.inputSchema()));
        }
        return arr;
    }

    private static JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return JSON.createObjectNode();
        }
    }

    private static boolean hasToolTurns(List<Message> history) {
        for (Message m : history) {
            if (m.role() == Role.TOOL || !m.toolCalls().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ---------- 流式解析 ----------

    /** 包内可见，便于单测直接回放 fixture。 */
    static final class AnthropicEventStream extends HttpEventStream {

        private Usage usage = Usage.UNKNOWN;
        private final Map<Integer, ToolUseAccumulator> toolUses = new LinkedHashMap<>();

        AnthropicEventStream(InputStream body) {
            super(body);
        }

        @Override
        protected List<ChatEvent> map(SseReader.SseEvent event) {
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
                case "content_block_start" -> {
                    JsonNode block = root.path("content_block");
                    if ("tool_use".equals(block.path("type").asText())) {
                        int index = root.path("index").asInt(-1);
                        if (index >= 0) {
                            ToolUseAccumulator acc = new ToolUseAccumulator();
                            acc.id = block.path("id").asText("");
                            acc.name = block.path("name").asText("");
                            toolUses.put(index, acc);
                        }
                    }
                    yield null;
                }
                case "content_block_delta" -> {
                    JsonNode delta = root.path("delta");
                    String deltaType = delta.path("type").asText("");
                    if ("thinking_delta".equals(deltaType)) {
                        yield List.of(new ChatEvent.ThinkingDelta(delta.path("thinking").asText("")));
                    }
                    if ("text_delta".equals(deltaType)) {
                        yield List.of(new ChatEvent.TextDelta(delta.path("text").asText("")));
                    }
                    if ("input_json_delta".equals(deltaType)) {
                        ToolUseAccumulator acc = toolUses.get(root.path("index").asInt(-1));
                        if (acc != null) {
                            acc.arguments.append(delta.path("partial_json").asText(""));
                        }
                        yield null;
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
                case "message_stop" -> {
                    List<ChatEvent> result = new ArrayList<>();
                    for (ToolUseAccumulator acc : toolUses.values()) {
                        result.add(new ChatEvent.ToolCallComplete(acc.build()));
                    }
                    result.add(new ChatEvent.Done(usage));
                    yield result;
                }
                case "error" -> {
                    String errType = root.path("error").path("type").asText(null);
                    String errMsg = root.path("error").path("message").asText("");
                    yield List.of(ApiErrors.fromStreamError(errType, errMsg));
                }
                default -> null; // content_block_stop 等：忽略
            };
        }
    }

    private static final class ToolUseAccumulator {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();

        ToolCall build() {
            String args = arguments.toString();
            return new ToolCall(id, name, args == null || args.isBlank() ? "{}" : args);
        }
    }
}
