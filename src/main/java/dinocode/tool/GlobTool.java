package dinocode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 按模式找文件工具（F2）：glob 模式返回匹配文件路径列表。
 */
public final class GlobTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESULTS = 100;

    private record GlobArgs(String pattern, String path) {
    }

    @Override
    public String name() {
        return "Glob";
    }

    @Override
    public String description() {
        return "按 glob 模式（如 **/*.java）查找文件，返回匹配的文件路径列表。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", Map.of("type", "string", "description", "glob 模式，如 **/*.java"));
        props.put("path", Map.of("type", "string", "description", "搜索起点目录，默认当前目录"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("pattern"));
        return schema;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Result execute(Map<String, Object> args) {
        GlobArgs a;
        try {
            a = JSON.convertValue(args, GlobArgs.class);
        } catch (IllegalArgumentException e) {
            return Result.error("参数错误: " + e.getMessage());
        }
        if (a.pattern() == null || a.pattern().isBlank()) {
            return Result.error("缺少参数 pattern");
        }
        Path root = Path.of(a.path() == null || a.path().isBlank() ? "." : a.path());
        List<String> matches = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            // NOFOLLOW：文件符号链接不作为 regular file 遍历（同 Grep，防沙箱外内容外泄）
            Iterator<Path> it = walk.filter(p -> Files.isRegularFile(p,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)).iterator();
            while (it.hasNext()) {
                String rel = root.relativize(it.next()).toString();
                if (matchGlob(a.pattern(), rel)) {
                    matches.add(rel);
                }
            }
        } catch (IOException e) {
            return Result.error("遍历失败: " + e.getMessage());
        }
        Collections.sort(matches);
        if (matches.isEmpty()) {
            return Result.ok("无匹配");
        }
        List<String> limited = matches.size() > MAX_RESULTS ? matches.subList(0, MAX_RESULTS) : matches;
        String out = String.join("\n", limited);
        if (matches.size() > MAX_RESULTS) {
            out += "\n[truncated]";
        }
        return Result.ok(out);
    }

    /** 支持 ** 跨任意层级的 glob 段匹配。 */
    static boolean matchGlob(String pattern, String path) {
        return matchSegments(pattern.split("/"), path.split("/"), 0, 0);
    }

    private static boolean matchSegments(String[] pp, String[] sp, int i, int j) {
        if (i == pp.length) {
            return j == sp.length;
        }
        if ("**".equals(pp[i])) {
            // ** 匹配 0 个或多个段
            return matchSegments(pp, sp, i + 1, j)
                    || (j < sp.length && matchSegments(pp, sp, i, j + 1));
        }
        if (j == sp.length) {
            return false;
        }
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + pp[i]);
        return m.matches(Path.of(sp[j])) && matchSegments(pp, sp, i + 1, j + 1);
    }
}
