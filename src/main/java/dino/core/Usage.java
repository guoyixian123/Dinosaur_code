package dino.core;

/**
 * 本轮 token 用量。字段可为 null（协议未回传时）。
 */
public record Usage(Integer inputTokens, Integer outputTokens) {

    public static final Usage UNKNOWN = new Usage(null, null);

    /** 合并两段用量（如 Anthropic 的 message_start 与 message_delta 各带一半）。 */
    public Usage merge(Usage other) {
        if (other == null) {
            return this;
        }
        return new Usage(
                pick(inputTokens, other.inputTokens),
                pick(outputTokens, other.outputTokens));
    }

    private static Integer pick(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.max(a, b);
    }
}
