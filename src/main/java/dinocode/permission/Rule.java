package dinocode.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 权限规则（ch06 F3 + ch12 F1~F4）：「工具名(模式)」声明 allow/deny。
 * 工具名用友好名（Bash/Read/Write/Edit/Glob/Grep）；模式段经 Matcher 编译——
 * ch12 扩展为四种类型（=exact / ~regex / !not / 缺省 glob），空模式表示匹配该工具全部调用。
 */
record Rule(String tool, String pattern, boolean allow) {

    /**
     * 解析 "Bash(git *)" / "Read" / "Bash(=git status)" / "Bash(~^npm)" / "Bash(!~^rm)" 形式。
     * 非法（空、括号不配对、友好名空、模式编译失败）一律返回 empty——
     * 调用方 {@link Settings#toRuleSet()} 负责 stderr 报告（F4 有声降级）。
     *
     * @param allow 规则落点（allow 列表传 true，deny 列表传 false）
     */
    static Optional<Rule> parse(String s, boolean allow) {
        if (s == null) {
            return Optional.empty();
        }
        String text = s.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        int open = text.indexOf('(');
        if (open < 0) {
            // 无模式段：匹配该工具全部调用
            return text.contains(")") || text.isBlank()
                    ? Optional.empty()
                    : Optional.of(new Rule(text, "", allow));
        }
        if (!text.endsWith(")")) {
            return Optional.empty();
        }
        String tool = text.substring(0, open).strip();
        String pattern = text.substring(open + 1, text.length() - 1).strip();
        if (tool.isEmpty()) {
            return Optional.empty();
        }
        // ch12：模式段过 Matcher 编译校验（失败返回 empty，F4 由调用方报告）
        try {
            Matchers.compile(pattern, tool.equals("Bash"));
        } catch (Matchers.MatcherCompileException e) {
            return Optional.empty();
        }
        return Optional.of(new Rule(tool, pattern, allow));
    }

    /** 编译校验并返回失败原因；合法返回 null（F4 有声降级用）。 */
    static String validate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        int open = s.indexOf('(');
        if (open < 0 || !s.endsWith(")")) {
            return null;
        }
        try {
            Matchers.compile(s.substring(open + 1, s.length() - 1).strip(),
                    s.substring(0, open).strip().equals("Bash"));
            return null;
        } catch (Matchers.MatcherCompileException e) {
            return e.getMessage();
        }
    }

    /**
     * glob 匹配：{@code *} 匹配任意字符序列；{@code **} 跨目录段语义仅对文件路径有意义，
     * 命令串中 {@code **} 等价 {@code *}。空模式恒匹配。
     */
    static boolean matchPattern(String pattern, String target) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        String t = target == null ? "" : target;
        if (t.contains("/") || pattern.contains("/")) {
            return matchPathSegments(splitSegments(pattern), splitSegments(t));
        }
        // 命令串：** 折叠为 * 后走单星 glob
        return matchGlob(pattern.replace("**", "*"), t);
    }

    /** 路径 glob（ch12 Matcher.Glob 用）：* 段内、** 跨段。 */
    static boolean matchPathPattern(String pattern, String target) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        return matchPathSegments(splitSegments(pattern), splitSegments(target == null ? "" : target));
    }

    /** 路径按 / 分段，* 段内、** 跨段。 */
    private static boolean matchPathSegments(List<String> pat, List<String> seg) {
        if (pat.isEmpty()) {
            return seg.isEmpty();
        }
        String head = pat.get(0);
        if ("**".equals(head)) {
            // ** 可吞 0..n 段
            for (int i = 0; i <= seg.size(); i++) {
                if (matchPathSegments(pat.subList(1, pat.size()), seg.subList(i, seg.size()))) {
                    return true;
                }
            }
            return false;
        }
        if (seg.isEmpty() || !matchGlob(head, seg.get(0))) {
            return false;
        }
        return matchPathSegments(pat.subList(1, pat.size()), seg.subList(1, seg.size()));
    }

    /**
     * 单星 glob：未转义的 {@code *} 匹配任意字符序列（含空格），
     * {@code \x} 表示字面 x（转义 {@code *}/[ 等 glob 元字符，Persister 用）。
     */
    static boolean matchGlob(String pattern, String target) {
        // 解析：按未转义 * 切成字面段；\x 折叠为字面 x
        List<String> literals = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                cur.append(pattern.charAt(++i));
            } else if (c == '*') {
                literals.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        literals.add(cur.toString());
        if (literals.size() == 1) {
            return literals.get(0).equals(target);
        }
        // 首段前缀锚定、末段后缀锚定、中段按序出现
        String first = literals.get(0);
        if (!target.startsWith(first)) {
            return false;
        }
        int pos = first.length();
        String last = literals.get(literals.size() - 1);
        int end = target.length() - last.length();
        if (end < pos || !target.substring(end).equals(last)) {
            return false;
        }
        for (int i = 1; i < literals.size() - 1; i++) {
            String mid = literals.get(i);
            int idx = target.indexOf(mid, pos);
            if (idx < 0 || idx > end) {
                return false;
            }
            pos = idx + mid.length();
        }
        return true;
    }

    private static List<String> splitSegments(String s) {
        List<String> out = new ArrayList<>();
        for (String seg : s.split("/")) {
            if (!seg.isEmpty()) {
                out.add(seg);
            }
        }
        return out;
    }
}
