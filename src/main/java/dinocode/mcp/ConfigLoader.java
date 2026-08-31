package dinocode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 配置加载（ch07 F1/F2/F3）：两层 YAML 读取合并、${VAR} 展开、字段校验。
 *
 * <p>永不抛 checked 异常：文件缺失视为空层、格式非法跳过该层并 stderr 告警（N1 降级）；
 * 非法 server 直接剔除（N2 安全默认）。
 */
public final class ConfigLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private ConfigLoader() {
    }

    /**
     * 加载并合并两层配置（ch07 F1）。
     *
     * @param root 项目根（定位项目级 {@code <root>/.dino/mcp.yaml}）
     * @return 归一化配置；无任何合法 server 时为空 McpConfig
     */
    public static McpConfig loadConfig(Path root) {
        Map<String, McpConfig.ServerConfig> merged = new LinkedHashMap<>();
        for (Path path : layers(root)) {
            Map<String, McpConfig.ServerConfig> layer = loadFile(path);
            // 项目级文件后加载 → 同名 server 完整覆盖用户级（F1）
            merged.putAll(layer);
        }
        return new McpConfig(merged);
    }

    /** 两层文件：用户级在前（被覆盖），项目级在后（覆盖者）。 */
    private static List<Path> layers(Path root) {
        List<Path> out = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            out.add(Path.of(home, ".dino", "mcp.yaml"));
        }
        out.add(root.resolve(".dino").resolve("mcp.yaml"));
        return out;
    }

    /** 加载单层：文件不存在→空；解析/结构失败→空 + stderr 告警（N1 降级）。 */
    static Map<String, McpConfig.ServerConfig> loadFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = YAML.readTree(path.toFile());
        } catch (IOException e) {
            System.err.println("[mcp] warn: 跳过配置文件 " + path + ": 解析失败 (" + rootMessage(e) + ")");
            return Map.of();
        }
        if (root == null || !root.isObject() || !root.has("mcp_servers")) {
            return Map.of(); // 无 mcp_servers 段视为零个 server（F1）
        }
        JsonNode servers = root.path("mcp_servers");
        if (!servers.isObject()) {
            System.err.println("[mcp] warn: 跳过配置文件 " + path + ": mcp_servers 不是键值结构");
            return Map.of();
        }
        Map<String, McpConfig.ServerConfig> out = new LinkedHashMap<>();
        for (var entry : servers.properties()) {
            String name = entry.getKey();
            McpConfig.ServerConfig cfg = parseServer(name, entry.getValue());
            if (cfg != null) {
                out.put(name, cfg);
            }
        }
        return out;
    }

    /** 解析单个 server 定义；缺失/非法 → null + stderr 告警（F2/N2）。 */
    static McpConfig.ServerConfig parseServer(String name, JsonNode node) {
        if (node == null || !node.isObject()) {
            warnSkip(name, "定义不是键值结构");
            return null;
        }
        JsonNode typeNode = node.path("type");
        if (!typeNode.isTextual()) {
            warnSkip(name, "缺少 type 字段");
            return null;
        }
        String type = typeNode.asText();
        switch (type) {
            case "stdio" -> {
                JsonNode command = node.path("command");
                if (!command.isTextual() || command.asText().isBlank()) {
                    warnSkip(name, "stdio 缺少 command");
                    return null;
                }
                return new McpConfig.ServerConfig(type, command.asText(),
                        stringList(node.path("args")),
                        expandMap(name, stringMap(node.path("env"))),
                        null, Map.of());
            }
            case "http" -> {
                JsonNode url = node.path("url");
                if (!url.isTextual() || url.asText().isBlank()) {
                    warnSkip(name, "http 缺少 url");
                    return null;
                }
                return new McpConfig.ServerConfig(type, null, List.of(), Map.of(),
                        url.asText(),
                        expandMap(name, stringMap(node.path("headers"))));
            }
            default -> {
                warnSkip(name, "未知 type: " + type);
                return null;
            }
        }
    }

    // ---------- ${VAR} 展开（F3） ----------

    /** 对 map 的每个值做 ${VAR} 展开；仅 env/headers 的值参与（F3）。 */
    static Map<String, String> expandMap(String serverName, Map<String, String> raw) {
        if (raw.isEmpty()) {
            return raw;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            out.put(e.getKey(), expandVars(serverName, e.getValue()));
        }
        return out;
    }

    /** ${VAR} → 宿主环境变量；未定义展开为空串并一次性告警（F3/N2）。 */
    static String expandVars(String serverName, String value) {
        if (value == null || !value.contains("${")) {
            return value == null ? "" : value;
        }
        Set<String> undefined = new LinkedHashSet<>();
        Matcher m = VAR.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String env = System.getenv(name);
            if (env == null) {
                env = "";
                undefined.add(name);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(env));
        }
        m.appendTail(sb);
        for (String v : undefined) {
            System.err.println("[mcp] warn: 未定义环境变量 ${" + v + "} (server " + serverName + ")，展开为空串");
        }
        return sb.toString();
    }

    private static List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : node.properties()) {
            if (e.getValue().isTextual()) {
                out.put(e.getKey(), e.getValue().asText());
            }
        }
        return out;
    }

    private static void warnSkip(String name, String reason) {
        System.err.println("[mcp] warn: 跳过 server " + name + ": " + reason);
    }

    private static String rootMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null ? e.getClass().getSimpleName() : msg;
    }
}
