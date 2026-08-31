package dinocode.hook;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hook 引擎（ch12 G1/F27/F31~F33）：事件分派、only_once 集合、动作执行协调。
 * 同步 hook 串行执行（N3）；async hook 起 virtual thread 立即返回（F28）；
 * 拦截命中短路跳过后续同事件 hook；hook 失败只记 stderr 不中断主流程（G9/F29）。
 */
public final class HookEngine {

    private final List<HookRule> rules;   // 按加载顺序
    private final List<String> sources;   // 加载来源文件（/hooks 展示，AC12）
    private final HookExecutor executor;
    private final Set<String> onceFired = new HashSet<>(); // only_once 内存集合（N5）

    public HookEngine(List<HookRule> rules, List<String> sources) {
        this(rules, sources, new HookExecutor());
    }

    HookEngine(List<HookRule> rules, List<String> sources, HookExecutor executor) {
        this.rules = List.copyOf(rules);
        this.sources = List.copyOf(sources);
        this.executor = executor;
    }

    /**
     * 事件分派（F31）：过滤匹配 hook → 跳过 only_once 已触发 → 求值条件 → 执行动作。
     * 返回拦截判定与注入 prompt 集合。
     */
    @SuppressWarnings("BusyWait")
    public DispatchResult dispatch(Event event, HookRule.Payload payload) {
        boolean blocked = false;
        String blockingHook = null;
        String reason = null;
        List<String> prompts = new ArrayList<>();

        for (HookRule rule : rules) {
            if (rule.event() != event) {
                continue;
            }
            synchronized (onceFired) {
                if (rule.onlyOnce() && onceFired.contains(rule.name())) {
                    continue; // F27：已触发过
                }
            }
            if (rule.condition() != null && !evaluate(rule.condition(), payload)) {
                continue;
            }
            if (rule.onlyOnce()) {
                synchronized (onceFired) {
                    onceFired.add(rule.name());
                }
            }

            if (rule.async()) {
                // F28：后台 virtual thread 执行，不等结果、无法表达拦截
                Thread.ofVirtual().start(() -> {
                    HookExecutor.ExecutionResult r = executor.run(rule, payload, false);
                    if (r.error() != null) {
                        System.err.println("[hook " + rule.name() + "] " + event.wireName()
                                + " failed: " + r.error().getMessage());
                    }
                });
                continue;
            }

            HookExecutor.ExecutionResult r = executor.run(rule, payload, event.isBlocking());
            if (r.error() != null) {
                System.err.println("[hook " + rule.name() + "] " + event.wireName()
                        + " failed: " + r.error().getMessage()); // F29：失败不中断
                continue;
            }
            if (r.prompt() != null) {
                prompts.add(r.prompt()); // F20：按声明顺序拼接
            }
            if (!blocked && event.isBlocking() && r.blocked()) {
                // F31/F32：拦截命中，短路后续 hook
                blocked = true;
                blockingHook = rule.name();
                reason = r.reason();
                break;
            }
        }
        return new DispatchResult(blocked, reason, blockingHook, prompts);
    }

    /** 条件求值（F11~F14）：all_of 全真 / any_of 任一真。 */
    private static boolean evaluate(Condition cond, HookRule.Payload payload) {
        boolean any = false;
        for (Condition.AtomCondition atom : cond.atoms()) {
            String value = payload.getByPath(atom.field());
            boolean hit = atom.matcher().match(value);
            if (cond.mode() == Condition.CombineMode.ALL_OF && !hit) {
                return false;
            }
            if (cond.mode() == Condition.CombineMode.ANY_OF && hit) {
                any = true;
            }
        }
        return cond.mode() == Condition.CombineMode.ALL_OF || any;
    }

    /** /clear、/resume 进新会话时清空 only_once 集合（F27/N5）。 */
    public void resetForNewSession() {
        synchronized (onceFired) {
            onceFired.clear();
        }
    }

    public List<String> sources() {
        return sources;
    }

    public List<HookRule> rules() {
        return rules;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }
}
