package dinocode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 搜代码内容工具（F2）：正则检索文件内容，返回 file:line:content 命中列表。
 */
public final class GrepTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESULTS = 100;
    private static final long MAX_LINE_BYTES = 1024 * 1024;

    private record GrepArgs(String pattern, String path, String glob) {
    }

    @Override
    public String name() {
        return "Grep";
    }

    @Override
    public String description() {
        return "在文件内容中检索（Java 正则），返回 file:line:content 命中位置。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", Map.of("type", "string", "description", "Java 正则表达式"));
        props.put("path", Map.of("type", "string", "description", "搜索起点目录，默认当前目录"));
        props.put("glob", Map.of("type", "string", "description", "文件名过滤（如 *.java），可选"));
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
        GrepArgs a;
        try {
            a = JSON.convertValue(args, GrepArgs.class);
        } catch (IllegalArgumentException e) {
            return Result.error("参数错误: " + e.getMessage());
        }
        if (a.pattern() == null || a.pattern().isBlank()) {
            return Result.error("缺少参数 pattern");
        }
        Pattern regex;
        try {
            regex = Pattern.compile(a.pattern());
        } catch (PatternSyntaxException e) {
            return Result.error("正则非法: " + e.getMessage());
        }
        Path root = Path.of(a.path() == null || a.path().isBlank() ? "." : a.path());
        PathMatcher nameFilter = a.glob() == null || a.glob().isBlank()
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + a.glob());

        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            Iterator<Path> it = walk.filter(Files::isRegularFile).iterator();
            while (it.hasNext() && hits.size() < MAX_RESULTS) {
                Path p = it.next();
                if (nameFilter != null && !nameFilter.matches(p.getFileName())) {
                    continue;
                }
                grepFile(p, root, regex, hits);
            }
        } catch (IOException e) {
            return Result.error("遍历失败: " + e.getMessage());
        }

        if (hits.isEmpty()) {
            return Result.ok("无命中");
        }
        String out = String.join("\n", hits);
        if (hits.size() >= MAX_RESULTS) {
            out += "\n[truncated]";
        }
        return Result.ok(out);
    }

    private void grepFile(Path file, Path root, Pattern regex, List<String> hits) throws IOException {
        String rel = root.relativize(file).toString();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
                    continue; // 超长行跳过，避免 OOM
                }
                if (regex.matcher(line).find()) {
                    hits.add(rel + ":" + lineNo + ":" + line);
                    if (hits.size() >= MAX_RESULTS) {
                        return;
                    }
                }
            }
        }
    }
}
