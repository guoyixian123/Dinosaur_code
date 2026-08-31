package dinocode.core;

import java.util.Map;

/**
 * 注册中心导出的协议无关工具定义（F1/F3）。
 * inputSchema 为完整 JSON Schema 对象（type/properties/required）。
 */
public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
}
