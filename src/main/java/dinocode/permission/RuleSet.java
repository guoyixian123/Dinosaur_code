package dinocode.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 单层规则集（ch06 F3/F4）：同层内 deny 优先于 allow。
 */
final class RuleSet {

    private final List<Rule> allow;
    private final List<Rule> deny;

    RuleSet() {
        this(List.of(), List.of());
    }

    RuleSet(List<Rule> allow, List<Rule> deny) {
        this.allow = List.copyOf(allow);
        this.deny = List.copyOf(deny);
    }

    /**
     * 先 deny 再 allow；命中返回裁决，未命中返回 empty（继续下一层）。
     * ch12：模式段经 Matcher 编译匹配（exact/regex/not/glob）。
     *
     * @param friendly 友好工具名（Bash/Read/Write/Edit/Glob/Grep）
     * @param target   命令串或项目相对路径
     */
    Optional<Decision> match(String friendly, String target) {
        return match(friendly, target, new ArrayList<>());
    }

    /** 带错误收集的匹配（ch12 F4：解析失败的规则跳过并报告）。 */
    Optional<Decision> match(String friendly, String target, List<String> errors) {
        for (Rule r : deny) {
            if (r.tool().equals(friendly) && ruleMatches(r, target, errors)) {
                return Optional.of(Decision.DENY);
            }
        }
        for (Rule r : allow) {
            if (r.tool().equals(friendly) && ruleMatches(r, target, errors)) {
                return Optional.of(Decision.ALLOW);
            }
        }
        return Optional.empty();
    }

    /** 单条规则匹配；模式编译失败时报告错误并视为未命中（F4）。 */
    private static boolean ruleMatches(Rule r, String target, List<String> errors) {
        try {
            Matcher m = Matchers.compile(r.pattern(), r.tool().equals("Bash"));
            return m.match(target);
        } catch (Matchers.MatcherCompileException e) {
            if (errors != null) {
                errors.add("rule \"" + r.tool() + "(" + r.pattern() + ")\" parse failed: " + e.getMessage());
            }
            return false;
        }
    }

    /** 含某条等价规则（去重用）。 */
    boolean containsEquivalent(Rule rule) {
        List<Rule> all = new ArrayList<>(allow);
        all.addAll(deny);
        return all.stream().anyMatch(r ->
                r.tool().equals(rule.tool()) && r.pattern().equals(rule.pattern()));
    }

    /** 返回追加一条 allow 规则后的新集合（规则集不可变）。 */
    RuleSet withAllow(Rule rule) {
        List<Rule> next = new ArrayList<>(allow);
        next.add(rule);
        return new RuleSet(next, deny);
    }
}
