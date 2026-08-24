package dino.config;

/**
 * 应用配置（加载校验后的结果）。
 * 具体默认值见 checklist §A / §D / §F。
 */
public record AppConfig(
        String protocol,
        String model,
        String baseUrl,
        String apiKey,
        int maxTokens,
        ThinkingConfig thinking) {

    public static final String PROTOCOL_ANTHROPIC = "anthropic";
    public static final String PROTOCOL_OPENAI = "openai";

    public static final int DEFAULT_MAX_TOKENS = 4096;

    public static final String ENV_API_KEY = "DINO_API_KEY";

    public boolean isAnthropic() {
        return PROTOCOL_ANTHROPIC.equals(protocol);
    }
}
