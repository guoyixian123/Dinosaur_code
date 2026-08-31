package dinocode.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dinocode.core.ChatEvent;
import dinocode.core.Message;
import dinocode.core.Role;
import dinocode.core.ToolCall;
import dinocode.core.ToolDefinition;
import dinocode.core.ToolResult;
import dinocode.core.Usage;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
        body.put("messages", toMessages(request));
        body.put("stream", true);
        body.put("max_tokens", request.maxTokens());
        // 让最后一个数据块带回用量，供终端显示（checklist §B）
        body.put("stream_options", Map.of("include_usage", true));
        if (!request.tools().isEmpty()) {
            body.put("tools", toTools(request.tools()));
        }
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

    // ---------- 请求体序列化 ----------

    /**
     * 消息装配（ch05 F3/F6/F8）：单条 system 消息 = 稳定块在前 + 环境段拼尾
     * （兼容端点对多条 system 支持不一；stable 居前缀 → 端点前缀缓存自动命中稳定部分）。
     * reminder 非空时追加一条尾部 user 消息（OpenAI 容忍 tool 后接 user）。
     */
    private static ArrayNode toMessages(ChatRequest request) {
        ArrayNode messages = JSON.createArrayNode();
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        String content = request.systemStable();
        if (!request.systemEnvironment().isEmpty()) {
            content = content.isEmpty()
                    ? request.systemEnvironment()
                    : content + "\n\n" + request.systemEnvironment();
        }
        if (!content.isEmpty()) {
            system.put("content", content);
        } else {
            messages.remove(messages.size() - 1); // 无系统内容时不发空 system 消息
        }
        for (Message m : request.history()) {
            switch (m.role()) {
                case Role.USER -> {
                    ObjectNode node = messages.addObject();
                    node.put("role", "user");
                    node.put("content", m.content());
                }
                case Role.ASSISTANT -> appendAssistant(messages, m);
                case Role.TOOL -> {
                    for (ToolResult r : m.toolResults()) {
                        ObjectNode node = messages.addObject();
                        node.put("role", "tool");
                        node.put("tool_call_id", r.toolCallId());
                        node.put("content", r.content());
                    }
                }
            }
        }
        if (!request.reminder().isEmpty()) {
            ObjectNode node = messages.addObject();
            node.put("role", "user");
            node.put("content", request.reminder());
        }
        return messages;
    }

    private static void appendAssistant(ArrayNode messages, Message m) {
        ObjectNode node = messages.addObject();
        node.put("role", "assistant");
        if (m.toolCalls().isEmpty()) {
            node.put("content", m.content());
            return;
        }
        if (!m.content().isEmpty()) {
            node.put("content", m.content());
        }
        ArrayNode toolCalls = node.putArray("tool_calls");
        for (ToolCall c : m.toolCalls()) {
            ObjectNode tc = toolCalls.addObject();
            tc.put("id", c.id());
            tc.put("type", "function");
            ObjectNode fn = tc.putObject("function");
            fn.put("name", c.name());
            fn.put("arguments", normalizeArgs(c.arguments()));
        }
    }

    private static ArrayNode toTools(List<ToolDefinition> tools) {
        ArrayNode arr = JSON.createArrayNode();
        for (ToolDefinition d : tools) {
            ObjectNode tool = arr.addObject();
            tool.put("type", "function");
            ObjectNode fn = tool.putObject("function");
            fn.put("name", d.name());
            fn.put("description", d.description());
            fn.set("parameters", JSON.valueToTree(d.inputSchema()));
        }
        return arr;
    }

    private static String normalizeArgs(String args) {
        return args == null || args.isBlank() ? "{}" : args;
    }

    // ---------- 流式解析 ----------

    /** 包内可见，便于单测直接回放 fixture。 */
    static final class OpenAiEventStream extends HttpEventStream {

        private Usage usage = Usage.UNKNOWN;
        private final Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();

        OpenAiEventStream(InputStream body) {
            super(body);
        }

        @Override
        protected List<ChatEvent> map(SseReader.SseEvent event) {
            String data = event.data();
            if (data == null || data.isBlank()) {
                return null;
            }
            if ("[DONE]".equals(data.trim())) {
                List<ChatEvent> result = new ArrayList<>();
                for (ToolCallAccumulator acc : toolCalls.values()) {
                    result.add(new ChatEvent.ToolCallComplete(acc.build()));
                }
                result.add(new ChatEvent.Done(usage));
                return result;
            }
            JsonNode root;
            try {
                root = JSON.readTree(data);
            } catch (JsonProcessingException e) {
                return null;
            }
            JsonNode usageNode = root.path("usage");
            if (usageNode.isObject() && usageNode.has("prompt_tokens")) {
                // 缓存命中解析（ch05 F4/N6）：prompt_tokens_details.cached_tokens，缺字段为 null
                JsonNode details = usageNode.path("prompt_tokens_details");
                usage = new Usage(
                        usageNode.path("prompt_tokens").asInt(),
                        usageNode.path("completion_tokens").asInt(),
                        null,
                        details.path("cached_tokens").isInt() ? details.path("cached_tokens").asInt() : null);
            }
            JsonNode delta = root.path("choices").path(0).path("delta");
            // 工具调用分片按 index 累积
            JsonNode toolCallsNode = delta.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode tc : toolCallsNode) {
                    int index = tc.path("index").asInt(-1);
                    if (index < 0) {
                        continue;
                    }
                    toolCalls.computeIfAbsent(index, i -> new ToolCallAccumulator()).accumulate(tc);
                }
            }
            // 推理模型（DeepSeek reasoner / 千问思考）的思考流 → 思考增量，暗色显示
            JsonNode reasoning = delta.path("reasoning_content");
            if (reasoning.isTextual() && !reasoning.asText().isEmpty()) {
                return List.of(new ChatEvent.ThinkingDelta(reasoning.asText()));
            }
            JsonNode content = delta.path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                return List.of(new ChatEvent.TextDelta(content.asText()));
            }
            return null;
        }
    }

    /** 按 index 累积一次工具调用的分片。 */
    private static final class ToolCallAccumulator {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();

        void accumulate(JsonNode tc) {
            if (tc.path("id").isTextual()) {
                id = tc.path("id").asText();
            }
            JsonNode fn = tc.path("function");
            if (fn.path("name").isTextual()) {
                name = fn.path("name").asText();
            }
            if (fn.path("arguments").isTextual()) {
                arguments.append(fn.path("arguments").asText());
            }
        }

        ToolCall build() {
            String args = arguments.toString();
            return new ToolCall(id, name, args == null || args.isBlank() ? "{}" : args);
        }
    }
}
