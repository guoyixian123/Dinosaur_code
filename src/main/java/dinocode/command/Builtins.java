package dinocode.command;

/**
 * 12 条内置命令一次性注册（ch10 F12~F23 / T8）。
 * 启动期调用一次；注册中心对名字/别名冲突立即抛异常终止启动（F2）。
 */
public final class Builtins {

    private Builtins() {
    }

    /** 按字典序注册全部 12 条命令（/help 的 handler 闭包捕获 reg 自身）。 */
    public static void registerAll(CommandRegistry reg) {
        reg.register(Command.of("clear", "结束当前会话并开启新会话", Kind.UI, BuiltinUi.clear()));
        reg.register(Command.of("compact", "手动压缩上下文（摘要历史，释放 token）", Kind.UI, BuiltinUi.compact()));
        reg.register(Command.of("do", "按计划开始执行（切回全工具）", Kind.PROMPT, BuiltinPrompt.doRun()));
        reg.register(Command.of("exit", "保存并退出", Kind.UI, BuiltinUi.exit()));
        reg.register(Command.of("help", "显示本帮助", Kind.LOCAL, BuiltinLocal.help(reg)));
        reg.register(Command.of("memory", "查看已加载的记忆文件列表", Kind.LOCAL, BuiltinLocal.memory()));
        reg.register(Command.of("permission", "查看当前权限模式", Kind.LOCAL, BuiltinLocal.permission()));
        reg.register(Command.of("plan", "进入计划模式（只读工具，先出计划）", Kind.UI, BuiltinUi.plan()));
        reg.register(Command.of("resume", "恢复历史会话", Kind.UI, BuiltinUi.resume()));
        reg.register(Command.of("review", "请 AI 审查上下文中的代码", Kind.PROMPT, BuiltinPrompt.review()));
        reg.register(Command.of("session", "查看当前会话标识与存档路径", Kind.LOCAL, BuiltinLocal.session()));
        reg.register(Command.of("status", "查看当前状态（模式/用量/工具/记忆/模型/目录）", Kind.LOCAL, BuiltinLocal.status()));
    }
}
