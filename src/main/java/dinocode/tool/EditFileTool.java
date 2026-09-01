package dinocode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 改文件工具（F2）：对原文片段做唯一匹配替换；0 处或多于 1 处返回可区分错误。
 */
public final class EditFileTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record EditFileArgs(String path, String oldString, String newString) {
    }

    @Override
    public String name() {
        return "EditFile";
    }

    @Override
    public String description() {
        return "对文件中的 old_string 做唯一匹配替换为 new_string。old_string 必须唯一（匹配 0 处或 >1 处会报错）。"
                + "编辑前请先用 ReadFile 读取目标文件，确认 old_string 唯一。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", Map.of("type", "string", "description", "要修改的文件路径"));
        props.put("old_string", Map.of("type", "string", "description", "要被替换的原文字段（须在文件中唯一出现）"));
        props.put("new_string", Map.of("type", "string", "description", "替换后的新文字段"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("path", "old_string", "new_string"));
        return schema;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(Map<String, Object> args) {
        EditFileArgs a;
        try {
            a = JSON.convertValue(args, EditFileArgs.class);
        } catch (IllegalArgumentException e) {
            return Result.error("参数错误: " + e.getMessage());
        }
        if (a.path() == null || a.path().isBlank()) {
            return Result.error("缺少参数 path");
        }
        if (a.oldString() == null || a.oldString().isEmpty()) {
            return Result.error("缺少参数 old_string");
        }
        if (a.newString() == null) {
            return Result.error("缺少参数 new_string");
        }
        Path p = Path.of(a.path());
        String content;
        try {
            content = Files.readString(p);
        } catch (IOException e) {
            return Result.error("读取失败: " + e.getMessage());
        }
        int n = content.split(Pattern.quote(a.oldString()), -1).length - 1;
        if (n == 0) {
            return Result.error("未找到匹配的内容");
        }
        if (n > 1) {
            return Result.error("匹配到 " + n + " 处，old_string 不唯一，请提供更长上下文使其唯一");
        }
        try {
            Files.writeString(p, content.replace(a.oldString(), a.newString()));
        } catch (IOException e) {
            return Result.error("写入失败: " + e.getMessage());
        }
        // ch16：diff 摘要（含 @@DIF 标记）替代一句话，供 Renderer 渲染红绿 diff
        return Result.ok(DiffView.editSummary(a.path(), content, a.oldString(), a.newString()));
    }
}
