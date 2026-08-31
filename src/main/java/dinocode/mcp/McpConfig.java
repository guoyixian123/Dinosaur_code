package dinocode.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP server 配置（ch07 F1/F2）：两层合并、${VAR} 展开、字段校验后的归一化形式。
 *
 * @param servers 按 server 名排序的合法 server 定义
 */
public record McpConfig(Map<String, ServerConfig> servers) {

    public McpConfig {
        servers = servers == null ? Map.of() : Map.copyOf(servers);
    }

    /**
     * 单个 MCP server 定义（ch07 F2）：显式 type 字段，不靠字段嗅探。
     *
     * @param type    "stdio" 或 "http"
     * @param command stdio 必填（子进程命令）
     * @param args    stdio 可选（子进程参数）
     * @param env     stdio 可选（已展开 ${VAR}，与宿主环境合并后注入子进程）
     * @param url     http 必填（Streamable HTTP endpoint）
     * @param headers http 可选（已展开 ${VAR}，注入每次请求）
     */
    public record ServerConfig(
            String type,
            String command,
            List<String> args,
            Map<String, String> env,
            String url,
            Map<String, String> headers) {

        public ServerConfig {
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }
}
