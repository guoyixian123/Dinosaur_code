package dinocode.session;

/**
 * 会话级设置（随会话持久化，见 checklist §D「/tokens 调整后重启仍生效」）。
 * 目前只有 /tokens 的最大输出覆盖值；null 表示沿用配置默认。
 */
public record SessionSettings(Integer maxTokensOverride) {

    public static final SessionSettings EMPTY = new SessionSettings(null);
}
