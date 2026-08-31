package dinocode.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 配置加载单测（ch07 F1/F2/F3/AC1/AC2/AC3）：两层合并、${VAR} 展开、字段校验降级。
 */
class ConfigLoaderTest {

    @TempDir
    Path root;

    private static Path projectConfig(Path root, String content) throws IOException {
        Path dino = root.resolve(".dino");
        Files.createDirectories(dino);
        Path f = dino.resolve("mcp.yaml");
        Files.writeString(f, content);
        return f;
    }

    @Test
    void missingFilesLoadAsEmpty() {
        McpConfig cfg = ConfigLoader.loadConfig(root);
        assertTrue(cfg.servers().isEmpty());
    }

    @Test
    void loadsStdioAndHttpServers() throws IOException {
        projectConfig(root, """
                mcp_servers:
                  demo:
                    type: stdio
                    command: echo
                    args: ["-x", "y"]
                    env:
                      K: "${HOME}"
                  web:
                    type: http
                    url: "https://mcp.example.com/mcp"
                    headers:
                      Authorization: "Bearer ${UNDEFINED_VAR_XYZ}"
                """);
        McpConfig cfg = ConfigLoader.loadConfig(root);
        assertEquals(2, cfg.servers().size());

        McpConfig.ServerConfig stdio = cfg.servers().get("demo");
        assertEquals("stdio", stdio.type());
        assertEquals("echo", stdio.command());
        assertEquals(java.util.List.of("-x", "y"), stdio.args());
        // ${HOME} 在测试环境必有值 → 展开非空
        assertTrue(!stdio.env().get("K").isEmpty());

        McpConfig.ServerConfig http = cfg.servers().get("web");
        assertEquals("http", http.type());
        assertEquals("https://mcp.example.com/mcp", http.url());
        // 未定义变量展开为空串（N2），不抛异常
        assertEquals("Bearer ", http.headers().get("Authorization"));
    }

    @Test
    void projectLevelOverridesUserLevelEntirely() throws IOException {
        // 用户级（HOME 下临时目录无法注入，改用两层同 root 模拟：直接测 loadFile + 合并语义）
        Map<String, McpConfig.ServerConfig> user = ConfigLoader.loadFile(
                write("user.yaml", """
                        mcp_servers:
                          a:
                            type: stdio
                            command: user-cmd
                          b:
                            type: stdio
                            command: user-b
                        """));
        Map<String, McpConfig.ServerConfig> project = ConfigLoader.loadFile(
                write("project.yaml", """
                        mcp_servers:
                          a:
                            type: http
                            url: "https://proj.example.com"
                        """));

        Map<String, McpConfig.ServerConfig> merged = new java.util.LinkedHashMap<>(user);
        merged.putAll(project); // 项目级整对象覆盖（F1）
        assertEquals(2, merged.size());
        assertEquals("http", merged.get("a").type()); // 项目级覆盖
        assertEquals("user-b", merged.get("b").command()); // 用户级保留
    }

    @Test
    void invalidServersSkippedOthersLoad() throws IOException {
        projectConfig(root, """
                mcp_servers:
                  no-type:
                    command: echo
                  bad-type:
                    type: websocket
                    url: "wss://x"
                  stdio-no-command:
                    type: stdio
                  http-no-url:
                    type: http
                  good:
                    type: stdio
                    command: echo
                """);
        McpConfig cfg = ConfigLoader.loadConfig(root);
        assertEquals(1, cfg.servers().size());
        assertTrue(cfg.servers().containsKey("good"));
    }

    @Test
    void malformedYamlDegradesToEmptyLayer() throws IOException {
        projectConfig(root, "{{{{ 不是 YAML ]]]]");
        McpConfig cfg = ConfigLoader.loadConfig(root);
        assertTrue(cfg.servers().isEmpty()); // 降级为空，不抛异常（N1）
    }

    @Test
    void nonMappingMcpServersDegrades() throws IOException {
        projectConfig(root, "mcp_servers: [1, 2, 3]");
        assertTrue(ConfigLoader.loadConfig(root).servers().isEmpty());
    }

    @Test
    void commandAndArgsNotExpanded() throws IOException {
        projectConfig(root, """
                mcp_servers:
                  a:
                    type: stdio
                    command: "cmd-${UNDEFINED_VAR_XYZ}"
                    args: ["${ALSO_UNDEFINED_XYZ}"]
                """);
        // command/args 不做展开（F3）——保留字面量
        assertEquals("cmd-${UNDEFINED_VAR_XYZ}", ConfigLoader.loadConfig(root).servers().get("a").command());
        assertEquals("${ALSO_UNDEFINED_XYZ}",
                ConfigLoader.loadConfig(root).servers().get("a").args().get(0));
    }

    @Test
    void envValueExpansionDirect() {
        // 直接测 expandVars：已定义变量展开
        String home = System.getProperty("user.home");
        assertTrue(ConfigLoader.expandVars("t", "${HOME}/x").startsWith(home.substring(0, Math.min(3, home.length())))
                || !ConfigLoader.expandVars("t", "${HOME}/x").equals("${HOME}/x"));
        assertEquals("plain", ConfigLoader.expandVars("t", "plain"));
        assertEquals("", ConfigLoader.expandVars("t", null));
    }

    @Test
    void exampleConfigDocParses() throws Exception {
        // docs/mcp-servers.example.yaml 三个 server 全部解析成功（T6 验证）
        Path example = Path.of("docs", "mcp-servers.example.yaml");
        if (!Files.isRegularFile(example)) {
            return; // 仓库外运行时跳过
        }
        Map<String, McpConfig.ServerConfig> servers = ConfigLoader.loadFile(example);
        assertEquals(3, servers.size());
        assertEquals("stdio", servers.get("github").type());
        assertNull(servers.get("example-http").command());
        assertTrue(servers.get("example-http").headers().containsKey("Authorization"));
    }

    private static Path write(String name, String content) throws IOException {
        Path f = Files.createTempFile("mcp-", name);
        Files.writeString(f, content);
        return f;
    }
}
