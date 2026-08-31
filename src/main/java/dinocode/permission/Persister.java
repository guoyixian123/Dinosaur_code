package dinocode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dinocode.core.ToolCall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 永久放行规则写入（ch06 F8/T7）：人在回路选「永久」时，
 * 生成<b>精确匹配</b>规则写入本地层配置文件（不自动泛化）。
 */
final class Persister {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private Persister() {
    }

    /** 为该次调用生成精确规则串；解析失败/未知工具 → empty。 */
    static Optional<String> ruleFor(PermissionEngine engine, ToolCall call) {
        Mappings.TargetInfo ti = Mappings.extractTarget(call);
        if (!ti.ok()) {
            return Optional.empty();
        }
        String friendly = Mappings.friendlyName(call.name());
        if (ti.isFile()) {
            String rel = relPath(engine.root(), ti.target());
            return rel.isEmpty() ? Optional.empty() : Optional.of(friendly + "(" + rel + ")");
        }
        // Bash：转义字面 glob 元字符，防止规则被泛化
        return Optional.of(friendly + "(" + escapeGlob(ti.target()) + ")");
    }

    /** 读本地层文件 → 追加精确 allow 规则（去重）→ 写回 → 并入内存规则集。 */
    static void persistLocalAllow(PermissionEngine engine, ToolCall call) throws IOException {
        Optional<String> text = ruleFor(engine, call);
        if (text.isEmpty()) {
            return;
        }
        Rule rule = Rule.parse(text.get(), true)
                .orElseThrow(() -> new IOException("永久规则编译失败: " + text.get()));
        if (engine.loadLocal().toRuleSet().containsEquivalent(rule)) {
            engine.mergeLocalAllow(rule); // 已在文件中，仅补内存
            return;
        }
        Settings current = engine.loadLocal();
        List<String> allow = new java.util.ArrayList<>(current.allow());
        allow.add(text.get());

        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("allow", allow);
        if (!current.deny().isEmpty()) {
            permissions.put("deny", current.deny());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        if (current.defaultMode() != null) {
            root.put("defaultMode", current.defaultMode());
        }
        root.put("permissions", permissions);

        Path localPath = engine.localPath();
        if (localPath.getParent() != null) {
            Files.createDirectories(localPath.getParent());
        }
        Files.writeString(localPath, YAML.writeValueAsString(root));
        engine.mergeLocalAllow(rule);
    }

    /** 项目相对 slash 路径；项目外/无法相对化返回原串。 */
    private static String relPath(Path root, String target) {
        try {
            Path p = Path.of(target);
            if (!p.isAbsolute()) {
                return p.toString().replace('\\', '/');
            }
            Path normalized = p.toAbsolutePath().normalize();
            if (normalized.startsWith(root)) {
                return root.relativize(normalized).toString().replace('\\', '/');
            }
            return target;
        } catch (RuntimeException e) {
            return target;
        }
    }

    /** 转义 glob 元字符（*、?、[），保证规则为字面精确匹配。 */
    static String escapeGlob(String command) {
        return command.replace("*", "\\*").replace("?", "\\?").replace("[", "\\[");
    }
}
