package dino.provider;

import dino.config.AppConfig;

/**
 * 协议标识 → 适配器映射（见 spec 设计骨架）。
 */
public final class ProviderFactory {

    private ProviderFactory() {
    }

    public static ChatProvider create(AppConfig config) {
        return switch (config.protocol()) {
            case AppConfig.PROTOCOL_ANTHROPIC ->
                    new AnthropicProvider(config.baseUrl(), config.apiKey(), config.model());
            case AppConfig.PROTOCOL_OPENAI ->
                    new OpenAiProvider(config.baseUrl(), config.apiKey(), config.model());
            default -> throw new IllegalArgumentException("未知 protocol: " + config.protocol());
        };
    }
}
