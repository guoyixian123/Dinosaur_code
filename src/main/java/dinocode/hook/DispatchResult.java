package dinocode.hook;

import java.util.List;

/**
 * 事件分派结果（ch12 F31~F33）：拦截判定 + 注入 prompt 集合。
 *
 * @param blocked          是否被拦截（仅拦截类事件可为 true）
 * @param reason           拦截原因（shell stderr / http reason）
 * @param blockingHookName 拦截 hook 名（F32：展示 [hook <name>] 前缀）
 * @param injectedPrompts  prompt 动作产生的注入文本，按声明序（F20/F33）
 */
public record DispatchResult(
        boolean blocked,
        String reason,
        String blockingHookName,
        List<String> injectedPrompts) {

    public static final DispatchResult EMPTY = new DispatchResult(false, null, null, List.of());

    public DispatchResult {
        injectedPrompts = injectedPrompts == null ? List.of() : List.copyOf(injectedPrompts);
    }

    public static DispatchResult empty() {
        return EMPTY;
    }
}
