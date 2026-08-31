package dinocode.core;

/**
 * 本轮 token 用量。字段可为 null（协议未回传时）。
 * cacheWrite/cacheRead 为缓存写/读 token（ch05 F4）：协议未提供时为 null。
 */
public record Usage(Integer inputTokens, Integer outputTokens, Integer cacheWrite, Integer cacheRead) {

    public static final Usage UNKNOWN = new Usage(null, null, null, null);

    public Usage(Integer inputTokens, Integer outputTokens) {
        this(inputTokens, outputTokens, null, null);
    }

    /** 合并两段用量（如 Anthropic 的 message_start 与 message_delta 各带一半）。 */
    public Usage merge(Usage other) {
        if (other == null) {
            return this;
        }
        return new Usage(
                pick(inputTokens, other.inputTokens),
                pick(outputTokens, other.outputTokens),
                pick(cacheWrite, other.cacheWrite),
                pick(cacheRead, other.cacheRead));
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
