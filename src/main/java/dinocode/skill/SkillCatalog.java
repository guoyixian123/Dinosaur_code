package dinocode.skill;

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
import java.util.stream.Collectors;

/**
 * 技能编目（ch11 F1~F6/T6）：两层目录扫描、单技能解析、按需热重载、活跃技能上下文。
 *
 * <p>两层 tier：用户级 {@code ~/.dino/skills/} → 项目级 {@code <work>/.dino/skills/}，
 * 同名后注册者胜出（N6）。phase-1 只读元数据加快启动（N2）；{@link #getFull} 按需重读
 * body（热更新，F4），读失败保留旧缓存。
 */
public final class SkillCatalog {

    private static final Logger LOG = Logger.getLogger(SkillCatalog.class.getName());
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** 技能元数据（F5）。 */
    public record SkillMeta(
            String name,
            String description,
            String whenToUse,
            List<String> tags,
            List<String> allowedTools,
            String mode,        // inline | fork（兼容 context: fork）
            String model,
            String forkContext) { // none | recent | full

        public boolean isFork() {
            return "fork".equalsIgnoreCase(mode);
        }
    }

    /** 一个技能：元数据 + 正文（phase-1 时 body 可未加载）。 */
    public record Skill(SkillMeta meta, String promptBody, Path sourceDir, boolean bodyLoaded) {

        /** 返回带新 body 的副本（F4）。 */
        public Skill withBody(String body) {
            return new Skill(meta, body, sourceDir, true);
        }
    }

    /** name → Skill；tier 来源 name → dir。全部保序（F1）。 */
    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final Map<String, Path> sources = new LinkedHashMap<>();

    /** 注册（同名覆盖，N6）。 */
    public void register(Skill skill, Path sourceDir) {
        skills.put(skill.meta().name(), skill);
        sources.put(skill.meta().name(), sourceDir);
    }

    public Skill get(String name) {
        return skills.get(name);
    }

    /** phase-2 按需加载：sourceDir 非空时重读 body（F4/N2）；读失败保留旧缓存。 */
    public Skill getFull(String name) {
        Skill current = skills.get(name);
        if (current == null) {
            return null;
        }
        Path dir = sources.get(name);
        if (dir == null) {
            return current; // 内置/无来源：直接返回
        }
        try {
            String body = readBody(dir);
            Skill reloaded = current.withBody(body);
            skills.put(name, reloaded);
            return reloaded;
        } catch (IOException e) {
            LOG.warning("[skill] 重读 body 失败，保留旧缓存 " + name + ": " + e.getMessage());
            return current;
        }
    }

    public List<Skill> list() {
        return List.copyOf(skills.values());
    }

    public Set<String> names() {
        return Set.copyOf(skills.keySet());
    }

    public Path source(String name) {
        return sources.get(name);
    }

    /** 整体刷新（远程安装后调用，F13）。 */
    public void reload(Path workDir) {
        skills.clear();
        sources.clear();
        loadCatalog(workDir);
    }

    /** 两层目录加载（F2）：先用户级再项目级，后者覆盖前者。 */
    public void loadCatalog(Path workDir) {
        Path userSkills = Path.of(System.getProperty("user.home", ".")).resolve(".dino").resolve("skills");
        Path projectSkills = workDir.toAbsolutePath().resolve(".dino").resolve("skills");
        loadTier(userSkills, "user");
        loadTier(projectSkills, "project");
    }

    /** 只加载单个项目目录（Main 直接调用的简化入口）。 */
    public void loadFromDirectory(Path dir) {
        loadTier(dir.toAbsolutePath(), "project");
    }

    /** 单层扫描（N1 容错：缺目录/坏技能只跳过自身）。 */
    private void loadTier(Path dir, String tier) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path sub : (Iterable<Path>) stream.filter(Files::isDirectory)::iterator) {
                try {
                    Skill skill = loadSkill(sub);
                    if (skill != null) {
                        register(skill, sub);
                    }
                } catch (Exception e) {
                    LOG.warning("[skill] 跳过技能 " + sub.getFileName() + " (" + tier + "): " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warning("[skill] 扫描目录失败 " + dir + ": " + e.getMessage());
        }
    }

    /**
     * 单技能加载策略两选一（F3）：优先 skill.yaml + prompt.md，否则 SKILL.md。
     * phase-1 只加载元数据（N2），body 延迟到 getFull。
     */
    Skill loadSkill(Path dir) throws IOException {
        Path yaml = dir.resolve("skill.yaml");
        Path prompt = dir.resolve("prompt.md");
        if (Files.isRegularFile(yaml) && Files.isRegularFile(prompt)) {
            return loadFromYamlAndPrompt(dir, yaml, prompt);
        }
        Path skillMd = dir.resolve("SKILL.md");
        if (Files.isRegularFile(skillMd)) {
            return parseSkillMD(dir, skillMd);
        }
        return null;
    }

    /** skill.yaml 元数据 + prompt.md 正文（正文延迟加载）。 */
    private Skill loadFromYamlAndPrompt(Path dir, Path yaml, Path prompt) throws IOException {
        Map<String, Object> map = YAML.readValue(yaml.toFile(), new TypeReference<Map<String, Object>>() {
        });
        SkillMeta meta = metaFromMap(map, dir);
        return new Skill(meta, "", dir, false); // body 延迟
    }

    /**
     * SKILL.md：可选 YAML frontmatter（--- 包裹），缺描述回退 body 首行非标题（F3/N3）。
     * 解析失败降级为「无 frontmatter」（N3）。
     */
    private Skill parseSkillMD(Path dir, Path file) throws IOException {
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
        SkillMeta meta;
        if (metaText != null) {
            try {
                Map<String, Object> map = YAML.readValue(metaText, new TypeReference<Map<String, Object>>() {
                });
                meta = metaFromMap(map, dir);
            } catch (Exception e) {
                meta = metaFromMap(Map.of(), dir); // N3：降级为无 frontmatter
            }
        } else {
            meta = metaFromMap(Map.of(), dir);
        }
        if ((meta.description() == null || meta.description().isBlank()) && !body.isBlank()) {
            // 缺描述回退：body 第一行非标题行
            for (String line : body.split("\n")) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    meta = new SkillMeta(meta.name(), t, meta.whenToUse(), meta.tags(),
                            meta.allowedTools(), meta.mode(), meta.model(), meta.forkContext());
                    break;
                }
            }
        }
        return new Skill(meta, body, dir, true); // SKILL.md body 已在内存
    }

    /** 元数据字段映射（F5）：name 缺省取目录名小写连字符化；mode 缺省 inline；兼容 context: fork。 */
    private SkillMeta metaFromMap(Map<String, Object> map, Path dir) {
        String dirName = dir.getFileName().toString();
        String name = text(map.get("name"));
        if (name == null || name.isBlank()) {
            name = dirName.toLowerCase().replace(' ', '-');
        }
        String mode = text(map.get("mode"));
        if (mode == null || mode.isBlank()) {
            mode = text(map.get("context")); // 向后兼容 context: fork
        }
        return new SkillMeta(
                name,
                text(map.get("description")),
                text(map.get("when_to_use")) != null ? text(map.get("when_to_use")) : text(map.get("whenToUse")),
                stringList(map.get("tags")),
                stringList(map.get("allowed_tools")),
                mode == null || mode.isBlank() ? "inline" : mode.toLowerCase(),
                text(map.get("model")),
                text(map.get("fork_context")) != null ? text(map.get("fork_context")).toLowerCase() : "none");
    }

    private static String text(Object o) {
        return o == null ? null : o.toString();
    }

    private static List<String> stringList(Object o) {
        if (o instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toList());
        }
        return List.of();
    }

    /** phase-2 body 读取：SKILL.md 去 frontmatter / prompt.md 全文。 */
    private static String readBody(Path dir) throws IOException {
        Path prompt = dir.resolve("prompt.md");
        if (Files.isRegularFile(prompt)) {
            return Files.readString(prompt, StandardCharsets.UTF_8);
        }
        Path skillMd = dir.resolve("SKILL.md");
        if (Files.isRegularFile(skillMd)) {
            String content = Files.readString(skillMd, StandardCharsets.UTF_8);
            String stripped = content.strip();
            if (stripped.startsWith("---")) {
                int end = content.indexOf("\n---", 3);
                if (end >= 0) {
                    return content.substring(content.indexOf("\n---", 3) + 4).strip();
                }
            }
            return content;
        }
        throw new IOException("目录中无 prompt.md 或 SKILL.md");
    }

    /**
     * 活跃技能上下文（F6/T6）：系统提示注入用——{@code ## Active Skills} 段 +
     * 每个技能 {@code ### name} + body；空集合返回空串。
     */
    public String buildActiveContext(Set<String> activeSkillNames) {
        if (activeSkillNames == null || activeSkillNames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## Active Skills\n");
        boolean any = false;
        for (String name : activeSkillNames) {
            Skill skill = getFull(name);
            if (skill == null) {
                continue;
            }
            any = true;
            sb.append("\n### ").append(name).append("\n\n")
                    .append(skill.promptBody() == null ? "" : skill.promptBody()).append('\n');
        }
        return any ? sb.toString() : "";
    }
}
