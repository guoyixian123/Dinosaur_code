package dinocode.tool;

import java.util.Map;

/**
 * 统一工具抽象（F1）。每个工具暴露名称、给模型看的描述、参数 Schema、执行入口。
 * execute 解析失败 / IO 失败一律包成 {@link Result#error}，不抛异常。
 */
public interface Tool {

    /** 模型看到的工具名，如 "ReadFile"。 */
    String name();

    /** 给模型的用途说明。 */
    String description();

    /** 手写 JSON Schema（type/properties/required），LinkedHashMap 保序。 */
    Map<String, Object> schema();

    /** 执行工具；args 为解析好的参数对象。 */
    Result execute(Map<String, Object> args);
}
