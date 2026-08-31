package dinocode.permission;

import java.util.Optional;

/**
 * 权限模式四档（ch06 F5/F7）：规则未命中时的兜底裁决来源。
 * 迁移自 agent 包并扩展（NORMAL→DEFAULT，新增 ACCEPT_EDITS/BYPASS）。
 */
public enum Mode {
    DEFAULT,        // 只读 Allow / 文件写 Ask / 命令执行 Ask
    ACCEPT_EDITS,   // 文件写 Allow / 命令执行 Ask
    PLAN,           // 仅只读工具可见（沿用 ch04）；矩阵同 default 作防御兜底
    BYPASS;         // 全 Allow（黑名单/沙箱仍拦）

    public String displayName() {
        return switch (this) {
            case DEFAULT -> "default";
            case ACCEPT_EDITS -> "acceptEdits";
            case PLAN -> "plan";
            case BYPASS -> "bypassPermissions";
        };
    }

    /** 大小写不敏感识别四档名；未知返回 empty。 */
    public static Optional<Mode> parse(String s) {
        if (s == null) {
            return Optional.empty();
        }
        for (Mode m : values()) {
            if (m.displayName().equalsIgnoreCase(s.strip())) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** Shift+Tab 循环顺序：DEFAULT → ACCEPT_EDITS → PLAN → BYPASS → DEFAULT。 */
    public Mode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
