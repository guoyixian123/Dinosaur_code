package dinocode.prompt;

import java.util.List;

/**
 * 内置模块内容（ch05 F1）：七个固定模块 + 三个可选空槽。
 * 内容全部为编译期常量——不含任何随轮次/时间变化的成分（N1 缓存确定性）。
 * 可选空槽（自定义指令/已激活 Skill/长期记忆）本章不接入真实来源，content 为空由装配跳过。
 */
public final class Modules {

    /** 身份（10） */
    private static final String IDENTITY = """
            你是 Dino Code，一个运行在终端的 AI 编程助手。
            你可以使用工具来读取、写入、修改文件，执行 shell 命令，按模式查找文件，搜索代码内容。""";

    /** 系统约束（20）：操作边界 */
    private static final String CONSTRAINTS = """
            在用户的工作目录约定内行事，不主动越出范围操作。
            绝不在任何输出中透露 api_key 或其他密钥。
            对删除、覆盖等破坏性操作保持谨慎，拿不准时先向用户确认。""";

    /** 任务模式（30）：ReAct 循环约定（沿用 ch04 F10 语义） */
    private static final String TASK_MODE = """
            持续使用工具推进任务，直到任务完成后才给出最终简洁答复，不要每步都停下等用户。""";

    /** 动作执行（40）：何时调工具、并发与副作用 */
    private static final String ACTIONS = """
            需要文件内容或文件系统信息、或需要执行某个操作时，调用相应的工具。
            连续的只读操作可以直接发出；有副作用的操作（写文件、执行命令）要单独发出并确认结果后再继续。""";

    /** 工具使用（50）：关键约定双重强化（ch05 F5） */
    private static final String TOOL_USE = """
            读文件、按模式找文件、搜索代码内容时，优先使用 ReadFile/Glob/Grep 专用工具，\
            不要用 Bash 拼凑等价命令。
            用 EditFile 修改文件前，必须先用 ReadFile 读取目标文件，确认 old_string 唯一。""";

    /** 语气风格（60） */
    private static final String TONE = """
            简洁、直接，不奉承，不堆砌客套话。""";

    /** 文本输出（70） */
    private static final String OUTPUT = """
            必要时使用 Markdown（代码块、列表）组织回复；最终答复精炼，不复述全过程。""";

    public static List<Module> fixedModules() {
        return List.of(
                new Module("身份", 10, IDENTITY),
                new Module("系统约束", 20, CONSTRAINTS),
                new Module("任务模式", 30, TASK_MODE),
                new Module("动作执行", 40, ACTIONS),
                new Module("工具使用", 50, TOOL_USE),
                new Module("语气风格", 60, TONE),
                new Module("文本输出", 70, OUTPUT));
    }

    /** 预留空槽：内容为空时装配跳过（F1）；ch09 起接受指令与记忆文本填充（F43）。 */
    public static List<Module> optionalModules(String instructions, String memory) {
        return List.of(
                new Module("自定义指令", 80, instructions == null ? "" : instructions),
                new Module("已激活 Skill", 90, ""),
                new Module("长期记忆", 100, memory == null ? "" : memory));
    }

    /** 兼容旧调用：两槽均为空。 */
    public static List<Module> optionalModules() {
        return optionalModules("", "");
    }

    private Modules() {
    }
}
