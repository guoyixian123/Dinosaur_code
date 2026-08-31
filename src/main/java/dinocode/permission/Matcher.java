package dinocode.permission;

/**
 * 规则/条件匹配统一接口（ch12 F1）：四种实现——exact / glob（缺省）/ regex / not。
 * sealed 限制扩展；Hook 条件与权限规则共用同一套匹配语义（G4/N7）。
 */
public sealed interface Matcher permits Matcher.Exact, Matcher.Glob, Matcher.Regex, Matcher.Not {

    boolean match(String s);

    /** 调试与 /hooks 输出用。 */
    String describe();

    /** 精确匹配：整串相等（F3）。 */
    record Exact(String value) implements Matcher {
        @Override
        public boolean match(String s) {
            return s != null && s.equals(value);
        }

        @Override
        public String describe() {
            return "=" + value;
        }
    }

    /** glob 匹配（缺省类型）：沿用 ch06 的 * / ** 语义；空模式恒匹配。 */
    record Glob(String pattern, boolean command) implements Matcher {
        @Override
        public boolean match(String s) {
            if (pattern == null || pattern.isEmpty()) {
                return true; // ch06 语义：空模式恒匹配
            }
            if (s != null && (s.contains("/") || pattern.contains("/"))) {
                return Rule.matchPathPattern(pattern, s);
            }
            return Rule.matchGlob(pattern.replace("**", "*"), s == null ? "" : s);
        }

        @Override
        public String describe() {
            return pattern;
        }
    }

    /** 正则匹配（F3）：加载期编译缓存（F15）；find 语义（子串匹配）。 */
    record Regex(java.util.regex.Pattern compiled, String source) implements Matcher {
        @Override
        public boolean match(String s) {
            return s != null && compiled.matcher(s).find();
        }

        @Override
        public String describe() {
            return "~" + source;
        }
    }

    /** 反向匹配（F3）：对 inner 取反；支持嵌套（如 !=value、!~regex）。 */
    record Not(Matcher inner) implements Matcher {
        @Override
        public boolean match(String s) {
            return !inner.match(s);
        }

        @Override
        public String describe() {
            return "!" + inner.describe();
        }
    }
}
