package dinocode.skill;

import dinocode.core.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 技能执行器（ch11 F6~F9/T5）：inline 注入主 Agent / fork 隔离子 Agent 双模式。
 */
public final class SkillExecutor {

    private static final int FORK_RECENT_MAX = 5;

    private SkillExecutor() {
    }

    /**
     * inline 模式（F6）：校验白名单 → 渲染 prompt → activateSkill 注入 SOP →
     * 按 allowed_tools 设工具过滤；返回渲染后的 body。
     */
    public static String executeInline(SkillCatalog.Skill skill, String args, SkillHost host) {
        assertAllowedToolsExist(skill.meta(), host.toolRegistry());
        String body = substituteArguments(skill.promptBody(), args);
        host.activateSkill(skill.meta().name(), body);
        List<String> allowed = skill.meta().allowedTools();
        if (allowed != null && !allowed.isEmpty()) {
            host.setToolFilter(allowed::contains);
        } else {
            host.setToolFilter(name -> true);
        }
        return body;
    }

    /**
     * fork 模式（F7）：校验白名单 → 渲染 prompt → buildForkSeed 种子 → runSubAgent；
     * 返回子 Agent 最终 assistant 文本。
     */
    public static String executeFork(SkillCatalog.Skill skill, String args, SkillForkHost host) {
        assertAllowedToolsExist(skill.meta(), host.toolRegistry());
        String body = substituteArguments(skill.promptBody(), args);
        List<Message> seed = buildForkSeed(skill.meta().forkContext(), host.snapshotParentMessages());
        return host.runSubAgent(body, seed, skill.meta().allowedTools(), skill.meta().model());
    }

    /**
     * 参数替换（F8）：args 空原样返回；body 含 $ARGUMENTS 占位替换；
     * 否则追加 {@code ## User Request} 段。
     */
    public static String substituteArguments(String body, String args) {
        if (args == null || args.isBlank()) {
            return body == null ? "" : body;
        }
        if (body != null && body.contains("$ARGUMENTS")) {
            return body.replace("$ARGUMENTS", args.strip());
        }
        String base = body == null ? "" : body;
        return base + (base.endsWith("\n") || base.isEmpty() ? "" : "\n\n")
                + "## User Request\n" + args.strip();
    }

    /**
     * fork 种子（F9）：full 全量拷贝；recent 取尾部最多 5 条；其他（含 none）返回空。
     */
    public static List<Message> buildForkSeed(String forkContext, List<Message> parent) {
        if (parent == null || parent.isEmpty()) {
            return List.of();
        }
        return switch (forkContext == null ? "none" : forkContext.toLowerCase()) {
            case "full" -> new ArrayList<>(parent);
            case "recent" -> {
                int from = Math.max(0, parent.size() - FORK_RECENT_MAX);
                yield new ArrayList<>(parent.subList(from, parent.size()));
            }
            default -> List.of(); // none 及未知值
        };
    }

    /** 白名单校验（F6/N5）：未注册工具立即抛 IllegalStateException 暴露配置错误。 */
    static void assertAllowedToolsExist(SkillCatalog.SkillMeta meta, dinocode.tool.ToolRegistry registry) {
        List<String> allowed = meta.allowedTools();
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        for (String tool : allowed) {
            if (registry.get(tool).isEmpty()) {
                throw new IllegalStateException(
                        "技能 '" + meta.name() + "' 的 allowed_tools 引用了未注册工具: " + tool);
            }
        }
    }
}
