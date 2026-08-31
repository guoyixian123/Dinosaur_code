package dinocode.permission;

/**
 * 权限判定中间值（ch06 F6）。模式兜底层值域严格为 {ALLOW, ASK}；
 * DENY 只可能来自黑名单、沙箱、显式 deny 规则、人在回路拒绝。
 */
public enum Decision {
    ALLOW,
    DENY,
    ASK
}
