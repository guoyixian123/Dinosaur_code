package dinocode.prompt;

/**
 * 内置 system prompt（ch03 F3）：说明 Agent 角色与工具使用约定。
 */
public final class Prompt {

    public static final String SYSTEM_PROMPT = """
            你是 Dino Code，一个运行在终端的 AI 编程助手。
            你可以使用工具来读取、写入、修改文件，执行 shell 命令，按模式查找文件，搜索代码内容。
            当需要文件内容或文件系统信息、或需要执行某个操作时，请调用相应的工具；
            拿到工具结果后再据此给出简洁的答复。""";

    private Prompt() {
    }
}
