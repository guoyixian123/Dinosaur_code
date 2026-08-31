package dinocode.permission;

/**
 * 人在回路三选一结果（ch06 F8）。TUI 经容量=1 的 BlockingQueue 回传给 agent。
 */
public enum Outcome {
    DENY_ONCE,      // 拒绝本次
    ALLOW_ONCE,     // 允许本次（不留规则）
    ALLOW_FOREVER   // 永久允许（+写本地层文件，精确匹配）
}
