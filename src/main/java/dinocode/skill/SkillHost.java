package dinocode.skill;

import dinocode.tool.ToolRegistry;

import java.util.function.Predicate;

/**
 * 技能宿主接口（ch11 F10/N4）：TUI/Agent 层实现，skill 包不反向依赖 agent/tui。
 */
public interface SkillHost {

    /** 激活技能：把 SOP 注入主 Agent 上下文。 */
    void activateSkill(String name, String body);

    /** 按 allowed_tools 过滤工具集（谓词返回 true = 保留）。 */
    void setToolFilter(Predicate<String> filter);

    /** 工具注册中心（白名单校验用）。 */
    ToolRegistry toolRegistry();
}
