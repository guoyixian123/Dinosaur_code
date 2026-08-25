package dino.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误文案与默认值的断言依据：checklist §A。
 */
class ConfigLoaderTest {

    @TempDir
    Path tmp;

    private Path write(String yaml) throws IOException {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    private String validYaml() {
        return """
                model: gpt-test
                api_key: cfg-key
                """;
    }

    private ConfigException loadFails(Path path, Map<String, String> env) {
        return assertThrows(ConfigException.class, () -> ConfigLoader.load(path, env));
    }

    @Test
    void missingFileAndNoEnvMeansMissingModel() {
        ConfigException e = loadFails(tmp.resolve("nope.yaml"), Map.of());
        assertEquals("配置缺少 model 字段", e.getMessage());
    }

    @Test
    void fullyEnvDrivenWithoutConfigFile() throws Exception {
        AppConfig config = ConfigLoader.load(tmp.resolve("nope.yaml"), Map.of(
                "DINO_MODEL", "qwen-plus",
                "DINO_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "DINO_API_KEY", "env-key"));
        assertEquals("qwen-plus", config.model());
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", config.baseUrl());
        assertEquals("env-key", config.apiKey());
    }

    @Test
    void envVarsOverrideConfigFile() throws Exception {
        Path file = write(validYaml());
        AppConfig config = ConfigLoader.load(file, Map.of(
                "DINO_MODEL", "gpt-4o",
                "DINO_BASE_URL", "https://custom.example/v1",
                "DINO_API_KEY", "env-key"));
        assertEquals("gpt-4o", config.model());
        assertEquals("https://custom.example/v1", config.baseUrl());
        assertEquals("env-key", config.apiKey());
    }

    @Test
    void brokenYaml() throws IOException {
        Path file = write("model: [unclosed");
        ConfigException e = loadFails(file, Map.of());
        assertTrue(e.getMessage().startsWith("配置文件解析失败: "), e.getMessage());
    }

    @Test
    void missingModel() throws IOException {
        Path file = write("""
                api_key: k
                """);
        ConfigException e = loadFails(file, Map.of());
        assertEquals("配置缺少 model 字段", e.getMessage());
    }

    @Test
    void missingApiKey() throws IOException {
        Path file = write("""
                model: m
                """);
        ConfigException e = loadFails(file, Map.of());
        assertEquals("缺少 api_key: 请设置环境变量 DINO_API_KEY 或在配置中填写 api_key", e.getMessage());
    }

    @Test
    void envVarOverridesConfigKey() throws Exception {
        Path file = write(validYaml());
        AppConfig config = ConfigLoader.load(file, Map.of("DINO_API_KEY", "env-key"));
        assertEquals("env-key", config.apiKey());
    }

    @Test
    void configKeyUsedWhenEnvAbsent() throws Exception {
        Path file = write(validYaml());
        AppConfig config = ConfigLoader.load(file, Map.of());
        assertEquals("cfg-key", config.apiKey());
    }

    @Test
    void defaultBaseUrlIsOpenAi() throws Exception {
        Path file = write(validYaml());
        AppConfig config = ConfigLoader.load(file, Map.of());
        assertEquals("https://api.openai.com/v1", config.baseUrl());
    }

    @Test
    void baseUrlOverride() throws Exception {
        Path file = write(validYaml() + "base_url: http://localhost:18080/v1\n");
        AppConfig config = ConfigLoader.load(file, Map.of());
        assertEquals("http://localhost:18080/v1", config.baseUrl());
    }

    @Test
    void maxTokensDefault() throws Exception {
        Path file = write(validYaml());
        AppConfig config = ConfigLoader.load(file, Map.of());
        assertEquals(4096, config.maxTokens());
    }

    @Test
    void maxTokensCustom() throws Exception {
        Path file = write(validYaml() + "max_tokens: 2000\n");
        AppConfig config = ConfigLoader.load(file, Map.of());
        assertEquals(2000, config.maxTokens());
    }

    @Test
    void maxTokensInvalid() throws IOException {
        Path file = write(validYaml() + "max_tokens: abc\n");
        ConfigException e = loadFails(file, Map.of());
        assertEquals("配置中 max_tokens 无效: 必须是正整数", e.getMessage());
    }
}
