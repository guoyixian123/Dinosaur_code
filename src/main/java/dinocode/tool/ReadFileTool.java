package dinocode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读文件工具（F2）：带行号返回文本内容，有行/字节上限。
 */
public final class ReadFileTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record ReadFileArgs(String path) {
    }

    @Override
    public String name() {
        return "ReadFile";
    }

    @Override
    public String description() {
        return "读取指定路径的文本文件内容，返回带行号的文本（便于引用）。文件不存在或不可读时返回结构化错误。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", Map.of("type", "string", "description", "要读取的文件路径"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("path"));
        return schema;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Result execute(Map<String, Object> args) {
        ReadFileArgs a;
        try {
            a = JSON.convertValue(args, ReadFileArgs.class);
        } catch (IllegalArgumentException e) {
            return Result.error("参数错误: " + e.getMessage());
        }
        if (a.path() == null || a.path().isBlank()) {
            return Result.error("缺少参数 path");
        }
        Path p = Path.of(a.path());
        if (Files.isDirectory(p)) {
            return Result.error("不是文件: " + a.path());
        }
        if (!Files.exists(p)) {
            return Result.error("文件不存在: " + a.path());
        }
        String content;
        try {
            content = Files.readString(p);
        } catch (IOException e) {
            return Result.error("读取失败: " + e.getMessage());
        }
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%6d\t%s%n", i + 1, lines[i]));
        }
        return Result.ok(Truncate.byLinesAndBytes(sb.toString(), 2000, 256 * 1024));
    }
}
