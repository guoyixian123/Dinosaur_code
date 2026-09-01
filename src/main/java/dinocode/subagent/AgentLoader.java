package dinocode.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 子 Agent 定义加载器（ch13 F3/T2~T3）：builtin → 用户级 → 项目级三层，
 * 同名后注册覆盖前者；目录缺失与单文件解析失败静默跳过（N1 同源容错）。
 */
public final class AgentLoader {

    private static final Logger LOG = Logger.getLogger(AgentLoader.class.getName());
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** model 字段合法值（T2）。 */
    public static final Set<String> VALID_MODELS = Set.of("", "inherit", "haiku", "sonnet", "opus");

    private final Map<String, SubAgentSpec> agents = new LinkedHashMap<>();

    /** 三层加载：builtin → ~/.dino/agents/*.md → <root>/.dino/agents/*.md（同名后注册覆盖）。 */
    public static AgentLoader loadAll(Path projectRoot) {
        AgentLoader loader = new AgentLoader();
        loader.loadBuiltins();
        loader.loadDir(Path.of(System.getProperty("user.home", "."), ".dino", "agents"));
        loader.loadDir(projectRoot.toAbsolutePath().resolve(".dino").resolve("agents"));
        return loader;
    }

    private AgentLoader() {
    }

    private void loadBuiltins() {
        for (SubAgentSpec spec : List.of(SubAgentSpec.GENERAL_PURPOSE, SubAgentSpec.PLAN, SubAgentSpec.EXPLORE)) {
            agents.put(spec.name(), spec);
        }
    }

    /** 扫描目录下所有 .md 定义；目录不存在静默跳过；单文件失败静默跳过。 */
    private void loadDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.getFileName().toString().endsWith(".md"))::iterator) {
                try {
                    SubAgentSpec spec = parseAgentFile(file);
                    agents.put(spec.name(), spec); // 同名覆盖（F3）
                } catch (Exception e) {
                    LOG.warning("[subagent] 跳过定义文件 " + file.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warning("[subagent] 扫描目录失败 " + dir + ": " + e.getMessage());
        }
    }

    /**
     * 解析单个 Markdown 定义文件（T2）：frontmatter（--- 包裹的 YAML）+ body。
     * 缺 name/description 抛 IllegalArgumentException（含路径与字段名）。
     */
    public static SubAgentSpec parseAgentFile(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String metaText = null;
        String body = content;
        String stripped = content.strip();
        if (stripped.startsWith("---")) {
            int end = content.indexOf("\n---", 3);
            if (end >= 0) {
                metaText = content.substring(content.indexOf("---") + 3, end).strip();
                body = content.substring(content.indexOf("\n---", 3) + 4).strip();
            }
        }
        Map<String, Object> meta = metaText == null ? Map.of() : parseYaml(metaText);
        String name = getString(meta, "name");
        String description = getString(meta, "description");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(file + ": 缺少 name 字段");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException(file + ": 缺少 description 字段");
        }
        String model = getString(meta, "model");
        if (model != null && !VALID_MODELS.contains(model.toLowerCase())) {
            throw new IllegalArgumentException(file + ": 非法 model '" + model
                    + "'，支持: " + VALID_MODELS);
        }
        String override = body.isBlank() ? null : body;
        return new SubAgentSpec(
                name,
                description,
                getStringList(meta, "tools"),
                getStringList(meta, "disallowed_tools"),
                override,
                intOr(meta.get("max_turns"), 200),
                model == null ? "" : model.toLowerCase());
    }

    private static Map<String, Object> parseYaml(String yaml) {
        try {
            return YAML.readValue(yaml, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalArgumentException("frontmatter 解析失败: " + e.getMessage(), e);
        }
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
            return out;
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s.split("\\s*,\\s*"));
        }
        return List.of();
    }

    private static int intOr(Object v, int fallback) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException ignored) {
                // 非法数字用缺省
            }
        }
        return fallback;
    }

    // ---------- 查询 ----------

    public SubAgentSpec get(String name) {
        return agents.get(name);
    }

    public List<SubAgentSpec> list() {
        return List.copyOf(agents.values());
    }

    public List<String> listNames() {
        return List.copyOf(agents.keySet());
    }
}
