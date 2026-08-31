package dinocode.command;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 5 条纯本地命令（ch10 F18~F21 / T5）：只输出信息，不改状态、不进 history、不耗 token。
 * N3a：任何状态可执行（idle 守护由 dispatcher 按 Kind 统一处理，handler 内不重复检查）。
 */
final class BuiltinLocal {

    private BuiltinLocal() {
    }

    /** /help：注册中心驱动的命令清单（字典序、两列对齐），单一信源（N7/AC1）。 */
    static Handler help(CommandRegistry reg) {
        return (AtomicBoolean cancelled, Ui ui) -> {
            List<Command> cmds = reg.visible();
            int width = 0;
            for (Command c : cmds) {
                width = Math.max(width, c.name().length() + 1); // + "/" 前缀
            }
            StringBuilder sb = new StringBuilder("可用命令:");
            for (Command c : cmds) {
                sb.append('\n').append(String.format("  /%-" + width + "s%s", c.name(), c.description()));
            }
            ui.println(sb.toString());
        };
    }

    /** /status：六项信息固定顺序、key 列对齐（F19/N8/AC4）。 */
    static Handler status() {
        return (AtomicBoolean cancelled, Ui ui) -> ui.println(String.format(
                "当前状态:\n"
                        + "  权限模式: %s\n"
                        + "  累计 token: %d 输入 / %d 输出\n"
                        + "  可用工具: %d 个\n"
                        + "  已加载记忆: %d 个文件\n"
                        + "  当前模型: %s\n"
                        + "  工作目录: %s",
                ui.mode().displayName(),
                ui.usageIn(), ui.usageOut(),
                ui.toolCount(),
                ui.memoryFiles().size(),
                ui.modelName().isEmpty() ? "（未就绪）" : ui.modelName(),
                ui.cwd()));
    }

    /** /memory：两级记忆文件名清单，空时友好提示（F20/AC5）。 */
    static Handler memory() {
        return (AtomicBoolean cancelled, Ui ui) -> {
            List<String> files = ui.memoryFiles();
            if (files.isEmpty()) {
                ui.println("无已加载的记忆文件。");
                return;
            }
            StringBuilder sb = new StringBuilder("已加载的记忆文件:");
            for (String f : files) {
                sb.append('\n').append("  ").append(f);
            }
            ui.println(sb.toString());
        };
    }

    /** /permission：当前权限模式名（F21/AC6）。 */
    static Handler permission() {
        return (AtomicBoolean cancelled, Ui ui) -> ui.println(
                "当前权限模式: " + ui.mode().displayName());
    }

    /** /session：会话存档路径 + session 标识（F22/AC7）。 */
    static Handler session() {
        return (AtomicBoolean cancelled, Ui ui) -> {
            String id = ui.sessionId();
            String path = ui.sessionPath();
            ui.println("会话标识: " + (id.isEmpty() ? "（未就绪）" : id)
                    + "\n存档路径: " + (path.isEmpty() ? "（未就绪）" : path));
        };
    }
}
