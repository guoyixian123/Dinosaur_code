package dinocode.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dinocode.core.ToolCall;

/**
 * 友好名映射、类别判定与目标提取（ch06 F3/F2/N7）。
 */
final class Mappings {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Mappings() {
    }

    /** 内部工具名 → 用户友好名；未知原样返回。 */
    static String friendlyName(String internal) {
        return switch (internal == null ? "" : internal) {
            case "Bash" -> "Bash";
            case "ReadFile" -> "Read";
            case "WriteFile" -> "Write";
            case "EditFile" -> "Edit";
            case "Glob" -> "Glob";
            case "Grep" -> "Grep";
            default -> internal == null ? "" : internal;
        };
    }

    /**
     * 类别判定（N7 最严）：readOnly 标志优先 → READ；write/edit → WRITE；
     * 其余（含 Bash、未知工具、只读标志缺失）→ EXEC。
     */
    static Category categorize(String internal, boolean readOnly) {
        if (readOnly) {
            return Category.READ;
        }
        return switch (internal == null ? "" : internal) {
            case "WriteFile", "EditFile" -> Category.WRITE;
            default -> Category.EXEC;
        };
    }

    /**
     * 目标提取结果。
     *
     * @param target 命令串（Bash）或文件路径（文件类）；解析失败为 ""
     * @param isFile 文件类工具为 true（参与沙箱）
     * @param ok     参数解析成功且必填字段齐备
     */
    record TargetInfo(String target, boolean isFile, boolean ok) {
    }

    /**
     * 从工具调用参数提取判定目标（F2/plan 决策表）：
     * read/write/edit 取 path；glob/grep 取 path（搜索根，空→"."）；Bash 取 command。
     * 未知工具 / JSON 不可解析 / 缺必填字段 → ok=false。
     */
    static TargetInfo extractTarget(ToolCall call) {
        String name = call == null ? "" : call.name();
        JsonNode args;
        try {
            args = JSON.readTree(call == null || call.arguments() == null || call.arguments().isBlank()
                    ? "{}" : call.arguments());
        } catch (Exception e) { // JsonProcessingException / IO 等一律按解析失败处理（N7）
            args = null;
        }
        boolean fileTool = switch (name) {
            case "ReadFile", "WriteFile", "EditFile", "Glob", "Grep" -> true;
            default -> false;
        };
        if (args == null || !args.isObject()) {
            return new TargetInfo("", fileTool, false);
        }
        switch (name) {
            case "ReadFile", "WriteFile", "EditFile" -> {
                JsonNode path = args.path("path");
                if (path.isTextual() && !path.asText().isBlank()) {
                    return new TargetInfo(path.asText(), true, true);
                }
                return new TargetInfo("", true, false);
            }
            case "Glob", "Grep" -> {
                // 沙箱只围栏搜索根 path（pattern 不参与——已知盲区，见 plan 决策表）
                JsonNode path = args.path("path");
                String root = path.isTextual() && !path.asText().isBlank() ? path.asText() : ".";
                return new TargetInfo(root, true, true);
            }
            case "Bash" -> {
                JsonNode command = args.path("command");
                return new TargetInfo(
                        command.isTextual() ? command.asText() : "",
                        false,
                        command.isTextual());
            }
            default -> {
                return new TargetInfo("", false, false); // 未知工具
            }
        }
    }

    /** 类别的中文展示名（供 Ask 原因文案）。 */
    static String categoryLabel(Category cat) {
        return switch (cat) {
            case READ -> "只读";
            case WRITE -> "文件写";
            case EXEC -> "命令执行";
        };
    }
}
