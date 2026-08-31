package dinocode.prompt;

/**
 * 系统提示模块（ch05 F1）：按职责拆分的指令单元。
 *
 * @param name     模块标识（身份、系统约束 …），仅用于可读性与测试断言
 * @param priority 数值越小优先级越高、排越前；固定模块 10..70，可选模块 80..100
 * @param content  模块正文；为空则装配时跳过（可选空槽）
 */
public record Module(String name, int priority, String content) {
}
