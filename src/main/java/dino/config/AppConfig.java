package dino.config;

/**
 * 应用配置（加载校验后的结果）。
 * 只支持 OpenAI 协议，覆盖一切 OpenAI 兼容端点（OpenAI / DeepSeek / 千问 / 智谱 / vLLM 等）。
 * 具体默认值见 checklist §A / §D。
 */
public record AppConfig(
        String model,
        String baseUrl,
        String apiKey,
        int maxTokens) {

    public static final int DEFAULT_MAX_TOKENS = 4096;

    /** 缺省端点：OpenAI 官方。其余端点用 base_url 指定。 */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    public static final String ENV_API_KEY = "DINO_API_KEY";
    public static final String ENV_MODEL = "DINO_MODEL";
    public static final String ENV_BASE_URL = "DINO_BASE_URL";
}
