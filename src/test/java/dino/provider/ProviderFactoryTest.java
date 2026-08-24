package dino.provider;

import dino.config.AppConfig;
import dino.config.ThinkingConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderFactoryTest {

    private AppConfig config(String protocol, String baseUrl) {
        return new AppConfig(protocol, "m", baseUrl, "k", 4096, ThinkingConfig.DISABLED);
    }

    @Test
    void anthropicProtocolMapsToAnthropicProvider() {
        ChatProvider provider = ProviderFactory.create(config("anthropic", "https://api.anthropic.com"));
        assertInstanceOf(AnthropicProvider.class, provider);
        assertEquals("https://api.anthropic.com", provider.baseUrl());
    }

    @Test
    void openaiProtocolMapsToOpenAiProvider() {
        ChatProvider provider = ProviderFactory.create(config("openai", "https://api.openai.com/v1"));
        assertInstanceOf(OpenAiProvider.class, provider);
    }

    @Test
    void baseUrlOverrideIsPassedThrough() {
        ChatProvider provider = ProviderFactory.create(config("openai", "http://localhost:18080/v1"));
        assertEquals("http://localhost:18080/v1", provider.baseUrl());
    }

    @Test
    void unknownProtocolRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ProviderFactory.create(config("foo", "http://x")));
    }
}
