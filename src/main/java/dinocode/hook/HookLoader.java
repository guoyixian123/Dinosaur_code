package dinocode.hook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dinocode.permission.Matcher;
import dinocode.permission.Matchers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Hook 规则加载器（ch12 F6~F8/G2/N1）：扫描两层 YAML、字段校验、Matcher 编译、合并。
 * 所有加载错误一律 stderr 输出后跳过出错规则，不阻断进程启动。
 */
public final class HookLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> BLOCKING_EVENTS = Set.of("PRE_TOOL_USE", "USER_PROMPT_SUBMIT");

    private HookLoader() {
    }

    /**
     * 加载两层配置（F6/F7）：项目级 {@code <root>/.dino/hooks.yaml} + 用户级
     * {@code ~/.dino/hooks.yaml}，叠加合并（无覆盖概念）；同名 hook 冲突时跳过后到者。
     */
    public static HookEngine load(Path projectRoot) {
        List<HookRule> rules = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        Path userFile = Path.of(System.getProperty("user.home", ".")).resolve(".dino").resolve("hooks.yaml");
        Path projectFile = projectRoot.toAbsolutePath().resolve(".dino").resolve("hooks.yaml");
        for (Path file : List.of(userFile, projectFile)) {
            List<Map<String, Object>> raw = loadFile(file);
            if (raw.isEmpty()) {
                continue;
            }
            sources.add(file.toString());
            for (Map<String, Object> entry : raw) {
                parseRule(entry, file.toString()).ifPresent(rule -> {
                    if (!seenNames.add(rule.name())) {
                        // F7：两层同名冲突 → 跳过后到者
                        System.err.println("[hook] 名称冲突，跳过后加载的: " + rule.name()
                                + " (" + file + ")");
                        return;
                    }
                    rules.add(rule);
                });
            }
        }
        return new HookEngine(rules, sources);
    }

    /** 单文件加载：缺失 → 空；解析失败 → stderr + 空（N9）。 */
    private static List<Map<String, Object>> loadFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            Map<String, Object> root = YAML.readValue(file.toFile(),
                    new TypeReference<Map<String, Object>>() {
                    });
            Object hooks = root == null ? null : root.get("hooks");
            if (!(hooks instanceof List<?> list)) {
                if (root != null && !root.isEmpty()) {
                    System.err.println("[hook] " + file + ": hooks 不是数组，已跳过该文件");
                }
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
            return out;
        } catch (IOException e) {
            System.err.println("[hook] " + file + " 解析失败: " + e.getMessage() + "，已跳过该文件");
            return List.of();
        }
    }

    /** 单条规则解析与校验；任何错误 stderr + empty（N1/AC8/AC11/AC16）。 */
    static Optional<HookRule> parseRule(Map<String, Object> entry, String source) {
        String name = text(entry.get("name"));
        if (name == null || name.isBlank()) {
            System.err.println("[hook] 缺少 name，跳过: " + entry);
            return Optional.empty();
        }
        Optional<Event> event = Event.parse(text(entry.get("event")));
        if (event.isEmpty()) {
            System.err.println("[hook] \"" + name + "\": unknown event \""
                    + text(entry.get("event")) + "\", skipped"); // AC11
            return Optional.empty();
        }
        // F28/AC8：拦截事件不允许 async
        boolean async = Boolean.TRUE.equals(entry.get("async"))
                || "true".equalsIgnoreCase(text(entry.get("async")));
        if (async && event.get().isBlocking()) {
            System.err.println("[hook] \"" + name + "\": async not allowed for blocking events, skipped");
            return Optional.empty();
        }
        // 条件（F11~F14/AC16）
        Condition condition = null;
        Object condObj = entry.get("if");
        if (condObj instanceof Map<?, ?> condMap) {
            try {
                condition = parseCondition((Map<String, Object>) condMap);
            } catch (Exception e) {
                System.err.println("[hook] \"" + name + "\": 条件解析失败: " + e.getMessage() + ", skipped");
                return Optional.empty();
            }
        }
        // 动作（F16）
        Object actionObj = entry.get("action");
        if (!(actionObj instanceof Map<?, ?> actionMapRaw)) {
            System.err.println("[hook] \"" + name + "\": 缺少 action，skipped");
            return Optional.empty();
        }
        Map<String, Object> actionMap = (Map<String, Object>) actionMapRaw;
        Action action;
        try {
            action = parseAction(actionMap);
        } catch (Exception e) {
            System.err.println("[hook] \"" + name + "\": " + e.getMessage() + ", skipped");
            return Optional.empty();
        }
        // timeout（F8：时长字符串如 30s / 5s / 1m）
        Duration timeout = parseTimeout(text(entry.get("timeout")));
        boolean onlyOnce = "true".equalsIgnoreCase(text(entry.get("only_once")));

        return Optional.of(new HookRule(name, event.get(), condition, action, onlyOnce, async, timeout, source));
    }

    /** 条件解析：顶层 all_of / any_of 二选一（F11/AC16）。 */
    private static Condition parseCondition(Map<String, Object> map) throws Exception {
        boolean hasAll = map.containsKey("all_of");
        boolean hasAny = map.containsKey("any_of");
        if (hasAll == hasAny) {
            throw new Exception("if 中必须恰好出现 all_of 或 any_of 之一");
        }
        String key = hasAll ? "all_of" : "any_of";
        Object atomsObj = map.get(key);
        if (!(atomsObj instanceof List<?> list) || list.isEmpty()) {
            throw new Exception(key + " 必须是非空数组");
        }
        Condition.CombineMode mode = hasAll
                ? Condition.CombineMode.ALL_OF
                : Condition.CombineMode.ANY_OF;
        List<Condition.AtomCondition> atoms = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> atomMap)) {
                throw new Exception(key + " 元素必须是对象");
            }
            String field = text(((Map<String, Object>) atomMap).get("field"));
            Object matchObj = ((Map<String, Object>) atomMap).get("match");
            if (field == null || field.isBlank() || !(matchObj instanceof Map<?, ?> matchRaw)) {
                throw new Exception("原子条件需要 field 与 match");
            }
            atoms.add(new Condition.AtomCondition(field, compileMatcher((Map<String, Object>) matchRaw)));
        }
        return new Condition(mode, atoms);
    }

    /** 结构化 match 解析（F14）：{type, value} / {type: not, inner}；正则编译失败即错。 */
    private static Matcher compileMatcher(Map<String, Object> match) throws Exception {
        String type = text(match.get("type"));
        String typeNorm = type == null ? "glob" : type.toLowerCase();
        return switch (typeNorm) {
            case "exact" -> new Matcher.Exact(requireValue(match, typeNorm));
            case "glob" -> new Matcher.Glob(requireValue(match, typeNorm), false);
            case "regex" -> {
                String source = requireValue(match, typeNorm);
                try {
                    yield new Matcher.Regex(java.util.regex.Pattern.compile(source), source);
                } catch (java.util.regex.PatternSyntaxException e) {
                    throw new Exception("regex 编译失败: " + source);
                }
            }
            case "not" -> {
                Object inner = match.get("inner");
                if (!(inner instanceof Map<?, ?> innerMap)) {
                    throw new Exception("not 缺少 inner");
                }
                yield new Matcher.Not(compileMatcher((Map<String, Object>) innerMap));
            }
            default -> throw new Exception("未知 match type: " + type);
        };
    }

    private static String requireValue(Map<String, Object> match, String type) throws Exception {
        Object value = match.get("value");
        if (value == null) {
            throw new Exception("match type " + type + " 缺少 value");
        }
        return value.toString();
    }

    /** 动作解析（F16~F26）：type 必为 shell/prompt/http/subagent 之一，字段完整性校验。 */
    private static Action parseAction(Map<String, Object> map) throws Exception {
        String type = text(map.get("type"));
        if (type == null) {
            throw new Exception("action 缺少 type");
        }
        return switch (type.toLowerCase()) {
            case "shell" -> {
                String command = text(map.get("command"));
                if (command == null || command.isBlank()) {
                    throw new Exception("shell 动作缺少 command");
                }
                yield new Action.Shell(command);
            }
            case "prompt" -> {
                String t = text(map.get("text"));
                if (t == null || t.isBlank()) {
                    throw new Exception("prompt 动作缺少 text");
                }
                yield new Action.Prompt(t);
            }
            case "http" -> {
                String url = text(map.get("url"));
                if (url == null || url.isBlank()) {
                    throw new Exception("http 动作缺少 url");
                }
                Map<String, String> headers = Map.of();
                Object h = map.get("headers");
                if (h instanceof Map<?, ?> hm) {
                    Map<String, String> out = new java.util.LinkedHashMap<>();
                    hm.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
                    headers = out;
                }
                yield new Action.Http(url, text(map.get("method")) == null ? "POST" : text(map.get("method")),
                        headers, text(map.get("body")));
            }
            case "subagent" -> {
                String agentName = text(map.get("agent_name"));
                String prompt = text(map.get("prompt"));
                if (agentName == null || prompt == null) {
                    throw new Exception("subagent 动作需要 agent_name 与 prompt");
                }
                yield new Action.Subagent(agentName, prompt); // F26 占位
            }
            default -> throw new Exception("未知 action type: " + type);
        };
    }

    /** 时长解析：30s / 5s / 1m / 500ms；非法用默认。 */
    private static Duration parseTimeout(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            String t = s.strip().toLowerCase();
            if (t.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(t.substring(0, t.length() - 2)));
            }
            if (t.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(t.substring(0, t.length() - 1)));
            }
            if (t.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(t.substring(0, t.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(t));
        } catch (NumberFormatException e) {
            return null; // 非法时长用默认 30s
        }
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
