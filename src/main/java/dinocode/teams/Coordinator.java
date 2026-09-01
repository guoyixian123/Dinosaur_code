package dinocode.teams;

import java.util.Set;

/**
 * Coordinator Mode（ch15 F16/T9）：Lead 拥有团队时工具集收窄为调度白名单。
 * 动态判定而非裁剪 registry——团队全删后自动恢复全工具（N4）。
 */
public final class Coordinator {

    /** Lead 的 12 项调度白名单（写工具 WriteFile/EditFile 等被排除）。 */
    public static final Set<String> ALLOWED_TOOLS = Set.of(
            "Agent", "SendMessage",
            "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
            "TeamCreate", "TeamDelete",
            "ReadFile", "Glob", "Grep", "Bash");

    private Coordinator() {
    }

    public static boolean isCoordinatorTool(String name) {
        return ALLOWED_TOOLS.contains(name);
    }
}
