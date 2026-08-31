package dinocode.skill;

import dinocode.core.Message;

import java.util.List;

/**
 * fork 宿主接口（ch11 F10）：在 SkillHost 基础上支持隔离子 Agent。
 */
public interface SkillForkHost extends SkillHost {

    /** 起隔离子 Agent 执行 body；seed 为父上下文种子消息；返回最终 assistant 文本。 */
    String runSubAgent(String body, List<Message> seed, List<String> allowedTools, String model);

    /** 父会话消息快照（fork_context 种子数据源）。 */
    List<Message> snapshotParentMessages();
}
