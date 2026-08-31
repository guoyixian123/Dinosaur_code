package dinocode.agent;

/**
 * Agent 模式（ch04 F10）：普通 / 计划。
 *
 * @deprecated ch06 起改用 {@link dinocode.permission.Mode}（四档权限模式轴），
 *         本枚举保留至迁移完成。
 */
@Deprecated(forRemoval = true)
public enum Mode {
    NORMAL,
    PLAN
}
