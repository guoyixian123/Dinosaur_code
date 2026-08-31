package dinocode.permission;

import dinocode.core.ToolCall;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 权限引擎（ch06 F1–F6）：前四层判定流水线——黑名单 → 沙箱 → 规则引擎 → 模式兜底。
 * 返回 ASK 表示请走第五层（agent 编排的人在回路）。
 *
 * <p>判定与 provider 无关（N6）；配置降级安全（N5）；未知情形一律最严处理（N7）。
 */
public final class PermissionEngine {

    /** 判定结果：裁决 + 可读原因（供 Deny 回灌与 Ask 展示）。 */
    public record CheckResult(Decision decision, String reason) {
        public static final CheckResult ALLOW = new CheckResult(Decision.ALLOW, "");
    }

    private static final PrintStream WARN = System.err;

    private final Path root;
    private final List<Pattern> blacklist;
    private RuleSet user;
    private RuleSet project;
    private RuleSet local;
    private final Path localPath;
    private final Mode startMode;
    /** 全放行标志（测试/无权限场景）：check 直接 Allow、持久化 no-op。 */
    private final boolean skipChecks;

    private PermissionEngine(Path root, List<Pattern> blacklist, RuleSet user, RuleSet project,
                             RuleSet local, Path localPath, Mode startMode) {
        this(root, blacklist, user, project, local, localPath, startMode, false);
    }

    private PermissionEngine(Path root, List<Pattern> blacklist, RuleSet user, RuleSet project,
                             RuleSet local, Path localPath, Mode startMode, boolean skipChecks) {
        this.root = root;
        this.blacklist = blacklist;
        this.user = user;
        this.project = project;
        this.local = local;
        this.localPath = localPath;
        this.startMode = startMode;
        this.skipChecks = skipChecks;
    }

    /**
     * 构造引擎：解析项目根、加载三层配置、确定启动模式。
     * 致命错误（项目根不可解析）也返回非 null 的空规则安全引擎（stderr 警告），
     * Main 注入永不为 null、check 不抛 NPE（plan 决策）。
     */
    public static PermissionEngine create(Path root) {
        Path resolved;
        try {
            resolved = Sandbox.resolveRoot(root);
        } catch (IOException e) {
            WARN.println("权限引擎降级: 项目根解析失败 (" + e.getMessage() + ")，沙箱以原始路径运行");
            resolved = root.toAbsolutePath();
        }
        // 三层配置：用户级 → 项目级 → 本地级；单文件失败降级为空（N5）
        Settings userSettings = Settings.load(Path.of(System.getProperty("user.home"), ".dino", "settings.yaml"));
        Settings projectSettings = Settings.load(resolved.resolve(".dino").resolve("settings.yaml"));
        Settings localSettings = Settings.load(resolved.resolve(".dino").resolve("settings.local.yaml"));
        // 启动默认模式：本地 > 项目 > 用户（F4/AC18）
        Mode mode = parseMode(localSettings)
                .or(() -> parseMode(projectSettings))
                .or(() -> parseMode(userSettings))
                .orElse(Mode.DEFAULT);
        Path localPath = resolved.resolve(".dino").resolve("settings.local.yaml");
        return new PermissionEngine(resolved, List.of(), userSettings.toRuleSet(),
                projectSettings.toRuleSet(), localSettings.toRuleSet(), localPath, mode);
    }

    private static java.util.Optional<Mode> parseMode(Settings s) {
        return Mode.parse(s.defaultMode());
    }

    /** 全放行引擎（测试/无权限场景便捷构造）：黑名单、沙箱、规则全部短路放行。 */
    public static PermissionEngine allowAll() {
        return new PermissionEngine(
                Sandbox.rootUnchecked(Path.of("")), List.of(),
                new RuleSet(), new RuleSet(), new RuleSet(), null, Mode.BYPASS, true);
    }

    /**
     * 前四层判定（F6，逐层短路；被跳过的层视为未拦、继续下一层）。
     *
     * @param mode      当前权限模式
     * @param call      待判定的工具调用
     * @param readOnly  调用方按批类型给定（等价 registry.isReadOnly）
     */
    public CheckResult check(Mode mode, ToolCall call, boolean readOnly) {
        if (skipChecks) {
            return CheckResult.ALLOW;
        }
        Category cat = Mappings.categorize(call.name(), readOnly);
        String friendly = Mappings.friendlyName(call.name());
        Mappings.TargetInfo ti = Mappings.extractTarget(call);

        // ① 黑名单（仅命令执行类；N1 不可绕过，bypass 也拦）
        if (cat == Category.EXEC && friendly.equals("Bash")
                && ti.target() != null && !ti.target().isEmpty() && Blacklist.hits(ti.target())) {
            return new CheckResult(Decision.DENY, "命中危险命令黑名单: " + Blacklist.describe(ti.target()));
        }
        // ② 沙箱（仅文件类；N2 先解析符号链接再比对）
        if (ti.isFile()) {
            if (!ti.ok()) {
                return new CheckResult(Decision.DENY, "无法解析文件路径参数，安全拒绝");
            }
            if (!Sandbox.ok(root, ti.target())) {
                return new CheckResult(Decision.DENY, "路径在项目目录之外: " + ti.target());
            }
        }
        // ③ 规则引擎：本地 > 项目 > 用户，就近命中即止（F4）
        for (Layer layer : List.of(new Layer("本地", local), new Layer("项目", project), new Layer("用户", user))) {
            var hit = layer.rules().match(friendly, ti.target());
            if (hit.isPresent()) {
                Decision d = hit.get();
                if (d == Decision.DENY) {
                    return new CheckResult(Decision.DENY, "匹配 " + layer.label() + " deny 规则: "
                            + friendly + "(" + ti.target() + ")");
                }
                return CheckResult.ALLOW;
            }
        }
        // ④ 模式兜底（F5 矩阵：只产 Allow/Ask）
        Decision d = modeFallback(mode, cat);
        if (d == Decision.ALLOW) {
            return CheckResult.ALLOW;
        }
        return new CheckResult(Decision.ASK,
                mode.displayName() + " 模式下 " + Mappings.categoryLabel(cat) + " 类操作需确认");
    }

    /** 模式兜底矩阵（F5）；值域严格 {ALLOW, ASK}。 */
    static Decision modeFallback(Mode mode, Category cat) {
        if (cat == Category.READ || mode == Mode.BYPASS) {
            return Decision.ALLOW;
        }
        if (mode == Mode.ACCEPT_EDITS && cat == Category.WRITE) {
            return Decision.ALLOW;
        }
        return Decision.ASK;
    }

    public Mode startMode() {
        return startMode;
    }

    /** 项目根（绝对、已解析符号链接）。 */
    Path root() {
        return root;
    }

    /**
     * 永久放行（ch06 F8）：生成精确规则写入本地层配置文件并并入内存规则集。
     * 失败仅抛 IOException（agent 侧只记录不阻断执行）。
     */
    public void persistLocalAllow(ToolCall call) throws IOException {
        if (skipChecks) {
            return;
        }
        Persister.persistLocalAllow(this, call);
    }

    // ---------- Persister 回调（包内协作） ----------

    Path localPath() {
        return localPath;
    }

    Settings loadLocal() {
        return Settings.load(localPath);
    }

    void mergeLocalAllow(Rule rule) {
        local = local.withAllow(rule);
    }

    private record Layer(String label, RuleSet rules) {
    }

    /** 测试注入规则用（包内可见）。 */
    static PermissionEngine forTest(Path root, RuleSet user, RuleSet project, RuleSet local) {
        List<Pattern> none = new ArrayList<>();
        return new PermissionEngine(root, none, user, project, local,
                root.resolve(".dino").resolve("settings.local.yaml"), Mode.DEFAULT);
    }
}
