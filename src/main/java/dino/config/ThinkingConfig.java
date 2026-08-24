package dino.config;

/**
 * Claude extended thinking 配置。不配置 = 关闭。
 */
public record ThinkingConfig(boolean enabled, int budgetTokens) {

    public static final int DEFAULT_BUDGET = 2048;

    public static final ThinkingConfig DISABLED = new ThinkingConfig(false, DEFAULT_BUDGET);
}
