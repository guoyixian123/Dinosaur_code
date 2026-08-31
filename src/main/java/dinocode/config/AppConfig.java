package dinocode.config;

/**
 * 应用配置（加载校验后的结果）。
 * 支持 OpenAI 与 Anthropic 协议（ch03 双协议），具体默认值见 checklist §A / §D / §F。
 */
public record AppConfig(
        String protocol,
        String model,
        String baseUrl,
        String apiKey,
        int maxTokens,
        ThinkingConfig thinking,
        int contextWindow) {

    public static final String PROTOCOL_ANTHROPIC = "anthropic";
    public static final String PROTOCOL_OPENAI = "openai";

    public static final int DEFAULT_MAX_TOKENS = 4096;

    /** 协议默认上下文窗口（ch08 F31）；contextWindow 未配置（0）时使用。 */
    public static final int DEFAULT_CONTEXT_WINDOW_ANTHROPIC = 200000;
    public static final int DEFAULT_CONTEXT_WINDOW_OPENAI = 128000;

    public static final String ENV_API_KEY = "DINO_API_KEY";
    public static final String ENV_PROTOCOL = "DINO_PROTOCOL";
    public static final String ENV_MODEL = "DINO_MODEL";
    public static final String ENV_BASE_URL = "DINO_BASE_URL";

    /** 兼容旧调用点的构造器（contextWindow 走协议默认）。 */
    public AppConfig(String protocol, String model, String baseUrl, String apiKey,
                     int maxTokens, ThinkingConfig thinking) {
        this(protocol, model, baseUrl, apiKey, maxTokens, thinking, 0);
    }

    public boolean isAnthropic() {
        return PROTOCOL_ANTHROPIC.equals(protocol);
    }

    /** 有效上下文窗口（F30/F31/G7）：配置值 &gt; 0 用配置，否则按协议默认。 */
    public int effectiveContextWindow() {
        if (contextWindow > 0) {
            return contextWindow;
        }
        return isAnthropic()
                ? DEFAULT_CONTEXT_WINDOW_ANTHROPIC
                : DEFAULT_CONTEXT_WINDOW_OPENAI;
    }
}
