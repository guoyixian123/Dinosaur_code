package dinocode.permission;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Matcher 编译工厂（ch12 F2/F3）：解析带前缀的模式串。
 * {@code =value} 精确、{@code ~regex} 正则、{@code !inner} 反向（可嵌套）、无前缀 glob（缺省）。
 */
public final class Matchers {

    private Matchers() {
    }

    /** 模式串编译失败（正则非法、not 缺 inner 等）。 */
    public static final class MatcherCompileException extends Exception {
        public MatcherCompileException(String message) {
            super(message);
        }
    }

    /**
     * 编译单条模式串。
     *
     * @param pattern 原始模式串（可含前缀）
     * @param command true=命令串匹配（Bash），false=路径匹配——保留参数以兼容未来的语义分叉
     */
    public static Matcher compile(String pattern, boolean command) throws MatcherCompileException {
        if (pattern == null || pattern.isEmpty()) {
            return new Matcher.Glob("", command); // 空模式恒匹配（ch06 语义）
        }
        char head = pattern.charAt(0);
        String rest = pattern.substring(1);
        return switch (head) {
            case '=' -> new Matcher.Exact(rest);
            case '~' -> compileRegex(rest);
            case '!' -> {
                if (rest.isEmpty()) {
                    throw new MatcherCompileException("not 缺少 inner 模式");
                }
                yield new Matcher.Not(compile(rest, command)); // 嵌套：!=v / !~r / !glob
            }
            default -> new Matcher.Glob(pattern, command);
        };
    }

    private static Matcher compileRegex(String source) throws MatcherCompileException {
        try {
            return new Matcher.Regex(Pattern.compile(source), source);
        } catch (PatternSyntaxException e) {
            throw new MatcherCompileException("regex 编译失败: " + source + " (" + e.getMessage() + ")");
        }
    }
}
