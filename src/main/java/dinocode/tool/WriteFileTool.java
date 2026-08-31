package dinocode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写文件工具（F2）：创建/覆盖文件，父目录不存在时自动创建。
 */
public final class WriteFileTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record WriteFileArgs(String path, String content) {
    }

    @Override
    public String name() {
        return "WriteFile";
    }

    @Override
    public String description() {
        return "写入（覆盖）指定路径的文件内容。父目录不存在时自动创建。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", Map.of("type", "string", "description", "要写入的文件路径"));
        props.put("content", Map.of("type", "string", "description", "写入的完整文件内容"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("path", "content"));
        return schema;
    }

    @Override
    public Result execute(Map<String, Object> args) {
        WriteFileArgs a;
        try {
            a = JSON.convertValue(args, WriteFileArgs.class);
        } catch (IllegalArgumentException e) {
            return Result.error("参数错误: " + e.getMessage());
        }
        if (a.path() == null || a.path().isBlank()) {
            return Result.error("缺少参数 path");
        }
        if (a.content() == null) {
            return Result.error("缺少参数 content");
        }
        Path p = Path.of(a.path());
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, a.content());
        } catch (IOException e) {
            return Result.error("写入失败: " + e.getMessage());
        }
        int bytes = a.content().getBytes(StandardCharsets.UTF_8).length;
        return Result.ok("已写入 " + a.path() + "（" + bytes + " 字节）");
    }
}
