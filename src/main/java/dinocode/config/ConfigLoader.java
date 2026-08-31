package dinocode.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 配置加载与校验。唯一接触配置文件与环境变量的地方（见 spec 设计骨架）。
 *
 * 解析优先级（环境变量 &gt; 配置文件 &gt; 默认值）：
 * - protocol：{@code DINO_PROTOCOL} &gt; config.protocol（默认 openai）
 * - model：{@code DINO_MODEL} &gt; config.model（必填）
 * - base_url：{@code DINO_BASE_URL} &gt; config.base_url &gt; 按 protocol 的官方地址
 * - api_key：{@code DINO_API_KEY} &gt; config.api_key（必填）
 * - max_tokens：config.max_tokens &gt; 4096
 * - thinking：config.thinking（默认关闭）
 *
 * 配置文件为可选：不存在时从环境变量解析；存在但损坏则仍报错。
 */
public final class ConfigLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ConfigLoader() {
    }

    /**
     * @param path 配置文件路径（--config 或默认 ~/.dino/config.yaml）；不存在时靠环境变量
     * @param env  环境变量（Main 传 System.getenv()，测试可注入）
     */
    public static AppConfig load(Path path, Map<String, String> env) throws ConfigException {
        JsonNode root = null;
        if (Files.isRegularFile(path)) {
            try {
                root = YAML.readTree(path.toFile());
            } catch (IOException e) {
                throw new ConfigException("配置文件解析失败: " + rootCauseMessage(e), e);
            }
            if (root == null || !root.isObject()) {
                throw new ConfigException("配置文件解析失败: 内容为空或不是键值结构");
            }
        }

        String protocol = firstNonBlank(
                env.get(AppConfig.ENV_PROTOCOL),
                text(root, "protocol"),
                AppConfig.PROTOCOL_OPENAI);
        if (!AppConfig.PROTOCOL_ANTHROPIC.equals(protocol) && !AppConfig.PROTOCOL_OPENAI.equals(protocol)) {
            throw new ConfigException("未知 protocol: " + protocol + "，支持: anthropic, openai");
        }

        String model = firstNonBlank(env.get(AppConfig.ENV_MODEL), text(root, "model"));
        if (model == null) {
            throw new ConfigException("配置缺少 model 字段");
        }

        String baseUrl = firstNonBlank(
                env.get(AppConfig.ENV_BASE_URL),
                text(root, "base_url"),
                defaultBaseUrl(protocol));

        String apiKey = firstNonBlank(env.get(AppConfig.ENV_API_KEY), text(root, "api_key"));
        if (apiKey == null) {
            throw new ConfigException("缺少 api_key: 请设置环境变量 " + AppConfig.ENV_API_KEY + " 或在配置中填写 api_key");
        }

        int maxTokens = AppConfig.DEFAULT_MAX_TOKENS;
        JsonNode maxTokensNode = root == null ? null : root.get("max_tokens");
        if (maxTokensNode != null && !maxTokensNode.isNull()) {
            if (!maxTokensNode.canConvertToInt() || maxTokensNode.asInt() <= 0) {
                throw new ConfigException("配置中 max_tokens 无效: 必须是正整数");
            }
            maxTokens = maxTokensNode.asInt();
        }

        ThinkingConfig thinking = parseThinking(root == null ? null : root.get("thinking"));

        return new AppConfig(protocol, model, baseUrl, apiKey, maxTokens, thinking);
    }

    /** 默认配置路径：~/.dino/config.yaml */
    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".dino", "config.yaml");
    }

    private static ThinkingConfig parseThinking(JsonNode node) throws ConfigException {
        if (node == null || node.isNull()) {
            return ThinkingConfig.DISABLED;
        }
        if (!node.isObject()) {
            throw new ConfigException("配置中 thinking 无效: 必须是键值结构");
        }
        boolean enabled = node.has("enabled") && node.get("enabled").asBoolean(false);
        int budget = ThinkingConfig.DEFAULT_BUDGET;
        JsonNode budgetNode = node.get("budget_tokens");
        if (budgetNode != null && !budgetNode.isNull()) {
            if (!budgetNode.canConvertToInt() || budgetNode.asInt() <= 0) {
                throw new ConfigException("配置中 thinking.budget_tokens 无效: 必须是正整数");
            }
            budget = budgetNode.asInt();
        }
        return new ThinkingConfig(enabled, budget);
    }

    private static String text(JsonNode root, String field) {
        if (root == null) {
            return null;
        }
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String defaultBaseUrl(String protocol) {
        return AppConfig.PROTOCOL_ANTHROPIC.equals(protocol)
                ? "https://api.anthropic.com"
                : "https://api.openai.com/v1";
    }

    private static String rootCauseMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null ? t.getClass().getSimpleName() : message;
    }
}
