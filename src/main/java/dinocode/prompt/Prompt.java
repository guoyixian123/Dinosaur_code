package dinocode.prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统提示装配（ch05 F1）：把模块按优先级升序拼接为稳定系统提示。
 * 只依赖常量内容 → 跨轮逐字节一致，可安全进缓存前缀（N1）。
 * 环境信息不在此处——见 {@link Environment}（变化内容不进稳定块）。
 */
public final class Prompt {

    /**
     * 按优先级升序装配模块：空 content 跳过（可选空槽）、其余以空行连接。
     * 挂载新模块 = 放进列表，不改本方法（F1/N8）。
     */
    public static String assembleSystem(List<Module> modules) {
        List<Module> sorted = new ArrayList<>(modules);
        sorted.sort((a, b) -> Integer.compare(a.priority(), b.priority()));
        StringBuilder sb = new StringBuilder();
        for (Module m : sorted) {
            if (m.content() == null || m.content().isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(m.content());
        }
        return sb.toString();
    }

    /** 完整稳定系统提示 = 固定模块 + 可选空槽（空槽自动跳过）。 */
    public static String buildSystemPrompt() {
        return buildSystemPrompt("", "");
    }

    /**
     * ch09 F43/AC27：参数化系统提示。
     * instructions 非空 → 填入 custom-instructions 槽（priority 80）；
     * memory 非空 → 填入 long-term-memory 槽（priority 100）；空则跳过（与 ch08 一致）。
     */
    public static String buildSystemPrompt(String instructions, String memory) {
        List<Module> all = new ArrayList<>(Modules.fixedModules());
        all.addAll(Modules.optionalModules(instructions, memory));
        return assembleSystem(all);
    }

    private Prompt() {
    }
}
