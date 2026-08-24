package dino.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dino.core.ChatEvent;
import dino.core.ErrorKind;

/**
 * HTTP / 流内错误到统一 Failure 事件的映射。文案见 checklist §G。
 */
final class ApiErrors {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ApiErrors() {
    }

    static ChatEvent.Failure fromHttp(int status, String body) {
        String apiMessage = extractMessage(body);
        if (status == 401 || status == 403) {
            return new ChatEvent.Failure(ErrorKind.AUTH, "认证失败: 请检查 api_key");
        }
        if (status == 429) {
            return new ChatEvent.Failure(ErrorKind.RATE_LIMIT, "请求过于频繁（限流），请稍后再试");
        }
        if (looksLikeOverflow(apiMessage)) {
            return overflowFailure();
        }
        return new ChatEvent.Failure(ErrorKind.OTHER,
                "请求失败 (" + status + "): " + truncate(apiMessage));
    }

    /** 流内的错误事件（Anthropic 的 event: error）。 */
    static ChatEvent.Failure fromStreamError(String type, String message) {
        if (looksLikeOverflow(message)) {
            return overflowFailure();
        }
        if (type != null && type.contains("overloaded")) {
            return new ChatEvent.Failure(ErrorKind.RATE_LIMIT, "服务端繁忙（过载），请稍后再试");
        }
        return new ChatEvent.Failure(ErrorKind.OTHER, "请求失败: " + truncate(message));
    }

    static ChatEvent.Failure overflowFailure() {
        return new ChatEvent.Failure(ErrorKind.CONTEXT_OVERFLOW,
                "对话历史超出模型上下文上限，可用 /new 开启新会话");
    }

    /** 两家错误体同形：{"error": {"message": "..."}}。解析失败返回原文截断。 */
    static String extractMessage(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            JsonNode message = node.path("error").path("message");
            if (message.isTextual() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception e) {
            // 落到原文截断
        }
        return body == null ? "" : body;
    }

    private static boolean looksLikeOverflow(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("prompt is too long")
                || lower.contains("context_length_exceeded")
                || lower.contains("maximum context length")
                || lower.contains("too many tokens")
                || lower.contains("context limit");
    }

    private static String truncate(String s) {
        if (s == null || s.isBlank()) {
            return "（无错误详情）";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }
}
