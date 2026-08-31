package dinocode.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dinocode.tool.Result;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 远端工具适配（ch07 F7/F8）：把 MCP server 的工具包装成内置 {@link Tool} 抽象。
 * Agent / provider / permission 全部无感（N3/N4）。
 */
final class McpTool implements dinocode.tool.Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** LLM 工具名安全字符（F8）。 */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_-]+$");
    /** 非 text 内容块的每工具一次性告警池（F7）。 */
    private static final ConcurrentHashMap<String, Boolean> NON_TEXT_WARNED = new ConcurrentHashMap<>();
    private static final ExecutorService EXEC = Executors.newVirtualThreadPerTaskExecutor();

    private final String fullName;
    private final String remoteName;
    private final String description;
    private final Map<String, Object> schema;
    private final boolean readOnly;
    private final CallerSession session;

    private McpTool(String fullName, String remoteName, String description,
                    Map<String, Object> schema, boolean readOnly, CallerSession session) {
        this.fullName = fullName;
        this.remoteName = remoteName;
        this.description = description;
        this.schema = schema;
        this.readOnly = readOnly;
        this.session = session;
    }

    /**
     * 适配单个远端工具；名字含非法字符 → empty + 告警（F8）。
     * schema 透传 JSON Schema 不裁剪（F7）；readOnly 仅信 readOnlyHint==true（N2）。
     */
    static Optional<McpTool> adapt(String serverName, JsonNode tool, CallerSession session) {
        String remoteName = tool.path("name").asText("");
        if (remoteName.isEmpty()) {
            return Optional.empty();
        }
        String fullName = "mcp__" + serverName + "__" + remoteName;
        if (!VALID_NAME.matcher(fullName).matches()) {
            System.err.println("[mcp] warn: 跳过工具 " + fullName + ": 名字含 LLM 工具名非法字符");
            return Optional.empty();
        }
        String remoteDesc = tool.path("description").asText("");
        String description = remoteDesc.isBlank()
                ? "来自 MCP server " + serverName + " 的工具 " + remoteName
                : remoteDesc;
        Map<String, Object> schema = toMap(tool.path("inputSchema"));
        if (schema == null || schema.isEmpty()) {
            schema = Map.of("type", "object"); // provider 拒收空 schema 的兜底
        }
        boolean readOnly = tool.path("annotations").path("readOnlyHint").asBoolean(false);
        return Optional.of(new McpTool(fullName, remoteName, description, schema, readOnly, session));
    }

    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        try {
            return JSON.convertValue(node, new TypeReference<Map<String, Object>>() {
            });
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String name() {
        return fullName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> schema() {
        return schema;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    /**
     * 执行（F7/F10）：经会话发 tools/call；30s 超时与协议错误均转 isError 回灌，不抛异常。
     * text 块按序拼接；远端 isError 映射；非 text 块静默丢弃 + 每工具一次性告警。
     */
    @Override
    public Result execute(Map<String, Object> args) {
        JsonNode result;
        Future<JsonNode> future = EXEC.submit(() -> session.callToolNode(remoteName, args));
        try {
            result = future.get(CallerSession.TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return Result.error("MCP 工具调用超时（30s）");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Result.error("MCP 工具调用失败: " + cause.getMessage());
        }
        return collect(result.path("content"), result.path("isError").asBoolean(false));
    }

    /** content 数组 + isError → Result：text 拼接、非 text 丢弃。 */
    private Result collect(JsonNode content, boolean isError) {
        StringBuilder sb = new StringBuilder();
        boolean nonText = false;
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(block.path("text").asText());
            } else {
                nonText = true;
            }
        }
        if (nonText && NON_TEXT_WARNED.putIfAbsent(fullName, Boolean.TRUE) == null) {
            // putIfAbsent 返回 null 表示首次：每工具只告警一次（F7）
            System.err.println("[mcp] warn: 工具 " + fullName + " 返回了非 text 内容块（已丢弃）");
        }
        return isError ? Result.error(sb.toString()) : Result.ok(sb.toString());
    }

    /** 稳定排序用。 */
    @Override
    public String toString() {
        return fullName;
    }

    /** 供 McpManager 收集远端 schema 展示（未用字段保留可读性）。 */
    Map<String, Object> rawSchema() {
        return new LinkedHashMap<>(schema);
    }
}
