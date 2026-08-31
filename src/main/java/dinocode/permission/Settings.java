package dinocode.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个权限配置文件（ch06 F4）：defaultMode + permissions.allow/deny。
 *
 * <p>加载<b>绝不致错</b>（N5）：文件缺失或格式非法都降级为空 Settings，
 * 不额外放开任何权限。
 */
record Settings(String defaultMode, List<String> allow, List<String> deny) {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    static final Settings EMPTY = new Settings(null, List.of(), List.of());

    Settings {
        allow = allow == null ? List.of() : List.copyOf(allow);
        deny = deny == null ? List.of() : List.copyOf(deny);
    }

    static Settings empty() {
        return EMPTY;
    }

    /** 加载单个文件；缺失/解析失败/结构非法 → 空 Settings，不抛（N5）。 */
    static Settings load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return empty();
        }
        JsonNode root;
        try {
            root = YAML.readTree(path.toFile());
        } catch (IOException | RuntimeException e) {
            return empty(); // 格式非法：降级为空，不致引擎构造失败
        }
        if (root == null || !root.isObject()) {
            return empty();
        }
        String mode = root.path("defaultMode").isTextual()
                ? root.path("defaultMode").asText() : null;
        return new Settings(mode, stringList(root.path("permissions").path("allow")),
                stringList(root.path("permissions").path("deny")));
    }

    private static List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText().strip());
            }
        }
        return out;
    }

    /** 解析为规则集；非法条目跳过并降级（N5）。 */
    RuleSet toRuleSet() {
        List<Rule> allowRules = new ArrayList<>();
        List<Rule> denyRules = new ArrayList<>();
        for (String s : allow()) {
            Rule.parse(s, true).ifPresent(allowRules::add);
        }
        for (String s : deny()) {
            Rule.parse(s, false).ifPresent(denyRules::add);
        }
        return new RuleSet(allowRules, denyRules);
    }
}
