package dinocode.provider;

import dinocode.config.AppConfig;

/**
 * 协议标识 → 适配器映射（见 spec 设计骨架）。
 */
public final class ProviderFactory {

    private ProviderFactory() {
    }

    public static ChatProvider create(AppConfig config) {
        if (config.isAnthropic()) {
            return new AnthropicProvider(config.baseUrl(), config.apiKey(), config.model(), config.thinking());
        }
        return new OpenAiProvider(config.baseUrl(), config.apiKey(), config.model());
    }
}
