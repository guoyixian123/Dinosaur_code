# 权限系统 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/mewcode/permission/Mode.java` | `Mode` 四档 enum + `displayName`/`parse` |
| 新建 | `src/main/java/com/mewcode/permission/Decision.java` | `enum Decision { ALLOW, DENY, ASK }` |
| 新建 | `src/main/java/com/mewcode/permission/Category.java` | `enum Category { READ, WRITE, EXEC }` |
| 新建 | `src/main/java/com/mewcode/permission/Outcome.java` | `enum Outcome { DENY_ONCE, ALLOW_ONCE, ALLOW_FOREVER }` |
| 新建 | `src/main/java/com/mewcode/permission/Blacklist.java` | 内置危险命令正则集 + `hitsBlacklist`（不可配，N1） |
| 新建 | `src/main/java/com/mewcode/permission/Sandbox.java` | `resolveRoot`、`sandboxOK`、`evalSymlinksOrAncestor`（N2） |
| 新建 | `src/main/java/com/mewcode/permission/Rule.java` | `record Rule`、`parseRule`、`matchPattern`（glob） |
| 新建 | `src/main/java/com/mewcode/permission/RuleSet.java` | 单层规则集 + `match()`（deny 先于 allow） |
| 新建 | `src/main/java/com/mewcode/permission/Settings.java` | `record Settings`、`loadSettings`（SnakeYAML Engine）、`toRuleSet`、`FriendlyNames`、`Categorizer`、`TargetExtractor` |
| 新建 | `src/main/java/com/mewcode/permission/PermissionEngine.java` | `PermissionEngine`、`create`、`check` 前四层流水线、`modeFallback`、`startMode`、`CheckResult` record |
| 新建 | `src/main/java/com/mewcode/permission/Persister.java` | `ruleFor`、`persistLocalAllow`（写本地层文件） |
| 新建 | `src/test/java/dev/mewcode/permission/*Test.java` | 黑名单/沙箱(含祖先回退)/规则/优先级/矩阵/加载降级/解析失败 单测（JUnit 5） |
| 改   | `src/main/java/com/mewcode/agent/Agent.java` | 删 `Mode`（迁 permission）；`Agent` 加 `engine`；`executeBatched(+mode)` 接入 `check`；`requestApproval`；`ApprovalRequest`/`AgentEvent.Approval`；Deny 用 `ToolResult` 构造 |
| 新建 | `src/main/java/com/mewcode/agent/ApprovalRequest.java` | `record ApprovalRequest(String name, String args, String reason, BlockingQueue<Outcome> respond)`（可作为 `Agent` 嵌套 record） |
| 改   | `src/test/java/dev/mewcode/agent/AgentTest.java` | 权限集成(Allow/Deny/Ask/会话/永久)、保序回灌、只读并发不退化、取消、模式迁移 |
| 改   | `src/main/java/com/mewcode/tui/MewCodeModel.java` | `mode`→`permission.Mode`、加 `engine`/`pending`/`approveCursor`；构造增参；`APPROVING` 分派；全局 Ctrl+C/Esc 覆盖 approving；`ReverseTab`（Shift+Tab）循环模式(`nextMode`) |
| 改   | `src/main/java/com/mewcode/tui/AgentEvent 队列.java` | `handleEvent` 处理 `Approval`；`updateApproving`；`sendOutcome`；`submit` 保留 `/plan`·`/do`（去掉 `/mode`）；`beginTurn` 传 engine |
| 改   | `src/main/java/com/mewcode/tui/Styles.java + MarkdownRenderer.java` | `statusBar` 左侧常驻模式（取代 provider 名）；待批准块渲染（多行三选菜单 + 光标高亮） |
| 改   | `src/test/java/dev/mewcode/tui/MewCodeModelTest.java` | `ReverseTab` 循环切换、approval 态按键回传、Esc 取消兜底、状态栏显示模式、模式跨轮保持；既有 `/plan`·`/do` 用例适配新 `Mode` 枚举 |
| 改   | `src/main/java/com/mewcode/MewCode.java` | 构造 `PermissionEngine.create(root)` 注入 `MewCodeModel` |
| 改   | `src/main/java/com/mewcode/smoke/MewCode.java` | 新增 `cwd`、构造引擎、`Mode.BYPASS` 运行；`new Agent(...)` 增参 |
| 改   | `build.gradle.kts` | 加 `com.fasterxml.jackson.core:jackson-databind` 依赖（解析 `ToolCall.input` JSON） |
| 改   | `.gitignore` | 追加 `.mewcode/settings.local.yaml` |
| 新建 | `.mewcode/settings.yaml.example` | 权限配置示例（defaultMode + allow/deny） |

---

## T1: permission 基础类型
**文件：** `src/main/java/com/mewcode/permission/{Mode,Decision,Category,Outcome}.java`
**依赖：** 无
**步骤：**
1. `enum Mode { DEFAULT, ACCEPT_EDITS, PLAN, BYPASS; }`；`displayName()` 返回 `"default"/"acceptEdits"/"plan"/"bypassPermissions"`；`static Optional<Mode> parse(String s)` 大小写不敏感识别四档名，未知返回 `Optional.empty()`。
2. `enum Decision { ALLOW, DENY, ASK }`。
3. `enum Category { READ, WRITE, EXEC }`。
4. `enum Outcome { DENY_ONCE, ALLOW_ONCE, ALLOW_FOREVER }`（人在回路三选一）。

**验证：** `./gradlew -q -DskipTests compile` 成功；`Mode.parse("default"/"acceptEdits"/"plan"/"bypassPermissions"/含大小写变体)` 均得 `Optional.of(对应档)`；`Mode.parse("x")` 得 `Optional.empty()`。

## T2: 危险命令黑名单
**文件：** `src/main/java/com/mewcode/permission/Blacklist.java`
**依赖：** 无
**步骤：**
1. `final class Blacklist { private Blacklist() {} ... }`；`private static final List<Pattern> PATTERNS = List.of(Pattern.compile(...), ...);` 编译一组高危模式（见 plan：`rm -rf /|~|$HOME|/*`、`dd of=/dev/`、fork bomb、`mkfs.`、`> /dev/sd|nvme|disk`、`chmod -R 777 /` 等）。
2. `public static boolean hitsBlacklist(String command)`：任一 `Pattern.matcher(command).find()` 即真。
3. Javadoc 顶部声明「启发式、非完备、不可配置放开」（N1）。

**验证：** 单测：`rm -rf /`、`rm -fr ~`、`:(){ :|:& };:`、`dd if=/dev/zero of=/dev/sda` 命中；`rm -rf ./build`、`git status`、`ls -la` 不命中。

## T3: 路径沙箱
**文件：** `src/main/java/com/mewcode/permission/Sandbox.java`
**依赖：** 无
**步骤：**
1. `static Path resolveRoot(Path root) throws IOException`：`root.toAbsolutePath().toRealPath()`。
2. `static Path evalSymlinksOrAncestor(Path abs) throws IOException`：对存在的目标 `toRealPath()`；不存在则逐级取最近**已存在祖先**目录 `toRealPath()` 后用 `.resolve(剩余段)` 拼回（覆盖「新建文件、含未创建中间目录」）。
3. `boolean sandboxOK(Path root, String path)`：空 path 视为 root；相对路径用 `root.resolve(path)` 解析为绝对；`Path resolved = evalSymlinksOrAncestor(abs)`；返回 `resolved.equals(root) || resolved.startsWith(root)`（`Path.startsWith` 按段比对，无需手拼分隔符）。

**验证：** 单测（`@TempDir` 造 root + 内外文件 + 符号链接 `Files.createSymbolicLink`）：root 内文件通过；**root 内但含多级未创建中间目录的新建文件路径通过**（专测祖先回退分支）；`/etc/passwd`、`../outside`、root 内指向 root 外目录的软链接被拒。

## T4: 规则与匹配
**文件：** `src/main/java/com/mewcode/permission/{Rule,RuleSet}.java`
**依赖：** 无
**步骤：**
1. `record Rule(String tool, String pattern, boolean allow) {}`；`final class RuleSet { private final List<Rule> allow; private final List<Rule> deny; ... }`。
2. `Optional<Rule> Rule.parse(String s, boolean allow)`：解析 `Tool(pattern)` 或 `Tool`；取友好名与括号内模式（可含空格/`*`/`**`）；非法（空、括号不配对）返回 `Optional.empty()`。
3. `boolean matchPattern(String pattern, String target)`：`pattern.isEmpty()`→true；命令串整串走「命令 glob」（`*` 匹配任意字符含空格，`**` 等价 `*`）；文件路径按 `/` 分段走 `*`（段内）/`**`（跨段）。命令分支可把 `**` 折叠成 `*` 再走「单星 glob」；路径分支递归匹配 `segments`。
4. `Optional<Decision> RuleSet.match(String friendly, String target)`：先遍历 `deny`（`tool.equals(friendly) && matchPattern(...)` 命中）→`Optional.of(DENY)`；再 `allow`→`Optional.of(ALLOW)`；否则 `Optional.empty()`。

**验证：** 单测：`Rule.parse("Bash(git *)")`、`Rule.parse("Read")` 正确；`matchPattern("git *","git status")` 真、`"git *","npm i"` 假；`matchPattern("src/**","src/a/b.go")` 真、`"src/**","docs/x"` 假；同层 deny 与 allow 同时命中时 `RuleSet.match` 返回 `Optional.of(DENY)`。

## T5: 配置加载与映射
**文件：** `src/main/java/com/mewcode/permission/Settings.java`
**依赖：** T1, T4
**步骤：**
1. `record Settings(String defaultMode, List<String> allow, List<String> deny) { static Settings empty() {...} }`。
2. `static Settings loadSettings(Path path)`：文件不存在→`Settings.empty()`、不抛；用 `org.snakeyaml.engine.v2.api.Load(LoadSettings.builder().build())` 把文件解析为 `Map<String,Object>`，读 `defaultMode`、`permissions.allow`、`permissions.deny`；解析失败→`Settings.empty()`（调用方降级，N5）。
3. `static RuleSet toRuleSet(Settings s)`：`s.allow()` 各条 `Rule.parse(x, true)`、`s.deny()` 各条 `Rule.parse(x, false)`，非法条目跳过；分别入 `RuleSet` 的 allow/deny 列表。
4. `static String friendlyName(String internal)`：`bash→Bash, read_file→Read, write_file→Write, edit_file→Edit, glob→Glob, grep→Grep`；未知原样返回。（可放 `FriendlyNames` 同包工具类）
5. `static Category categorize(String internal, boolean readOnly)`：`readOnly`→`READ`（优先）；否则 `write_file/edit_file→WRITE`、其余（含 `Bash`、未知工具）→`EXEC`（N7 最严）。（可放 `Categorizer`）
6. `record TargetInfo(String target, boolean isFile, boolean ok) {}`；`static TargetInfo extractTarget(ToolCallComplete tc)`：内部用 Jackson `ObjectMapper.readTree(call.input())`——`read_file/write_file/edit_file` 取 `path`（isFile=true）；`glob/grep` 取 `path`（**搜索根目录**，空→`"."`，isFile=true；注：`pattern`/`Glob` 字段不参与沙箱）；`Bash` 取 `command`（isFile=false）；未知工具→`new TargetInfo("", false, false)`；**`readTree` 失败或缺必填字段→`ok=false`**。

**验证：** 单测：缺失文件得空 `Settings` 且不抛；非法 YAML 得 `Settings.empty()`、不致命；`toRuleSet` 跳过非法条；`friendlyName`/`categorize`（含未知工具→EXEC、readOnly 优先）/`extractTarget`（各工具字段、解析失败 ok=false）各分支正确。

## T6: 引擎与前四层流水线
**文件：** `src/main/java/com/mewcode/permission/PermissionEngine.java`
**依赖：** T1, T2, T3, T4, T5
**步骤：**
1. `final class PermissionEngine { private final Path root; private final List<Pattern> blacklist; private final RuleSet user, project, local; private final Path localPath; private final Mode startMode; ... }`；嵌套 `public record CheckResult(Decision decision, String reason) {}`。
2. `static PermissionEngine create(Path root)`：
   - `try { resolveRoot(root); } catch(IOException e) { 把 e 打到 stderr「权限引擎降级:...」；`root`、`startMode=DEFAULT`、四层规则空，仍返回非 null 引擎 }`（Main 注入永不为 null，check 不抛 NPE）。
   - 加载三层：user=`<user.home>/.mewcode/settings.yaml`、project=`<root>/.mewcode/settings.yaml`、local=`<root>/.mewcode/settings.local.yaml`；各 `loadSettings`→`toRuleSet`；**单个文件读/解析失败仅降级跳过该文件（视为空），绝不抛致命异常**。
   - `localPath = root.resolve(".mewcode/settings.local.yaml")`。
   - `startMode`：依次取 local/project/user 的 `defaultMode`（`Mode.parse` 成功者优先 local），皆无→`Mode.DEFAULT`。
3. `static Decision modeFallback(Mode mode, Category cat)`：F5 矩阵——`cat == READ || mode == BYPASS` → `ALLOW`；`mode == ACCEPT_EDITS && cat == WRITE` → `ALLOW`；其余（default/plan 的 WRITE/EXEC、acceptEdits 的 EXEC）→ `ASK`。**只产 Allow/Ask**。
4. `CheckResult check(Mode mode, ToolCallComplete tc, boolean readOnly)`：
   - `Category cat = Categorizer.categorize(tc.toolName(), readOnly);` `String friendly = FriendlyNames.friendlyName(tc.toolName());` `TargetInfo ti = TargetExtractor.extractTarget(call);`
   - ① `cat == EXEC && ti.target() != null && !ti.target().isEmpty() && Blacklist.hitsBlacklist(ti.target())` → `new CheckResult(DENY, "命中危险命令黑名单：…")`。
   - ② `ti.isFile()`：`!ti.ok()` → `new CheckResult(DENY, "无法解析文件路径参数，安全拒绝")`；否则 `!Sandbox.sandboxOK(root, ti.target())` → `new CheckResult(DENY, "路径在项目目录之外：" + ti.target())`。
   - ③ 依 `local, project, user` 顺序 `match(friendly, ti.target())`，命中即返回 `new CheckResult(d, "匹配规则：…")`。
   - ④ `Decision d = modeFallback(mode, cat);` `d == ALLOW` → `new CheckResult(ALLOW, "")`；`d == ASK` → `new CheckResult(ASK, mode.displayName() + " 模式下 " + 类别 + " 类操作需确认")`。
5. `public Mode startMode() { return startMode; }`。

**验证：** 单测：逐层短路（黑名单先于沙箱/规则；deny 规则先于模式；allow 规则不进模式）；跳层放行（非 EXEC 不被黑名单拦、Bash 不被沙箱拦）；模式矩阵逐档逐类断言（含 plan 行 WRITE/EXEC→Ask）；三级优先级（本地 deny 盖项目 allow 等）；`resolveRoot` 失败仍得非 null 引擎。

## T7: 永久规则写入
**文件：** `src/main/java/com/mewcode/permission/Persister.java`
**依赖：** T5, T6
**步骤：**
1. `static Optional<RuleString> ruleFor(ToolCallComplete tc)`：据 `extractTarget`+`friendlyName` 生成**精确**规则——`Bash`→`Bash(<command>)`；文件类→`Write(<relpath>)`/`Read(<relpath>)` 等（relpath=相对 root 的 slash 路径）；Bash 命令串经 `escapeGlob` 转义字面 glob 元字符（`*`/`?`/`[`/`]`）防止规则被泛化；解析失败/未知→`Optional.empty()`。（`RuleString` 是包内小 record，含 `String text`）
2. `void PermissionEngine.persistLocalAllow(ToolCallComplete tc) throws IOException`：`loadSettings(localPath)`（缺失则空）→ 追加规则串到 `permissions.allow`（去重）→ 用 SnakeYAML Engine `Dump(DumpSettings.builder().build()).dumpToString(map)` 序列化 → 确保 `localPath.getParent()` 存在（`Files.createDirectories`）→ `Files.writeString(localPath, yaml)`；同步把 `Rule` 并入 `local` 内存 `RuleSet`。

**验证：** 单测（`@TempDir` 作 root）：`persistLocalAllow` 后 `localPath` 文件含该 allow 条、再 `create` 重载仍 `ALLOW`；幂等：重复 `persistLocalAllow` 不抛错且不重复写文件。

## T8: agent 接入权限（模式迁移 + 判定 + 人在回路）
**文件：** `src/main/java/com/mewcode/agent/Agent.java`、`src/main/java/com/mewcode/agent/ApprovalRequest.java`
**依赖：** T6, T7
**步骤：**
1. **模式迁移**：删除 agent 内 `Mode`/`NORMAL`/`PLAN` 定义，import `com.mewcode.permission.Mode`，全部改用 `Mode.DEFAULT`/`Mode.PLAN`；`run` 形参 `Mode mode`；`mode == Mode.PLAN` 处不变（defs 选只读、`PlanReminder` 注入）。
2. `Agent` 加 `private final PermissionEngine engine;`；构造增参 `new Agent(provider, registry, version, engine)`。
3. 新增 `record ApprovalRequest(String name, String args, String reason, BlockingQueue<Outcome> respond) {}`（独立文件或 Agent 内嵌）；`AgentEvent` sealed interface 加 `record Approval(ApprovalRequest request) implements AgentEvent {}`。
4. `private Outcome requestApproval(ToolCallComplete tc, String reason, BlockingQueue<AgentEvent> pub)`：`BlockingQueue<Outcome> respond = new ArrayBlockingQueue<>(1);` `pub.submit(new AgentEvent.Approval(new ApprovalRequest(tc.toolName(), argsPreview(call.input()), reason, respond)));` `try { return respond.take(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }`（`null` 表示取消）。
5. `executeBatched(calls, mode, pub)`（增 `mode` 形参）接入。**Deny 结果统一用 `new ToolResult(calls.get(k).id(), reason, /*isError*/true)` 构造**（agent 包无 `errResult` 工厂）：
   - 只读批：每个 `k` 先 `CheckResult cr = engine.check(mode, calls.get(k), true);`；按调用序发 `PhaseStart`；`cr.decision() == DENY`→ `results[k] = new ToolResult(..., cr.reason(), true)`、`done[k] = true`、**不纳入并发**；否则照旧并发（virtual thread）。并发执行完按调用序发 `PhaseEnd`（**Deny 项也发，isError=true**，与有副作用 Deny 一致）。
   - 串行有副作用：`CheckResult cr = engine.check(mode, calls.get(i), false);`；`ALLOW`→执行；`DENY`→`new ToolResult(..., cr.reason(), true)`；`ASK`→`Outcome o = requestApproval(calls.get(i), cr.reason(), pub);`；`o == null`→取消收尾（`completed = false`，沿用既有取消路径）；按 `o`：`ALLOW_ONCE`→执行；`ALLOW_FOREVER`→`try { engine.persistLocalAllow(calls.get(i)); } catch (IOException e) { /* 仅记不阻断 */ }` +执行；`DENY_ONCE`→被拒结果。
6. `run` 调 `executeBatched(calls, mode, pub)`。

**验证：** `./gradlew -q -DskipTests compile`（配合 T9）；轻量自检：表驱动断言 `requestApproval` 在线程已中断时返回 `null`、不阻塞。

## T9: agent 单测
**文件：** `src/test/java/dev/mewcode/agent/AgentTest.java`
**依赖：** T8
**步骤：**
1. 既有 ch04/ch05 用例：`new Agent(...)` 增 `engine` 实参（`PermissionEngine.create(tempDir)`）；`Mode.NORMAL`→`Mode.DEFAULT`；fake `Provider` 签名不变。
2. 新增：
   - **Deny 回灌不中断**：构造 deny（沙箱外路径或会话 deny）→ 模型请求该工具 → 工具结果 `isError=true`、Loop 继续到次轮（脚本化 fake provider）。
   - **保序回灌**：单批含「被拒调用 + 放行调用」→ 断言结果按原 `calls` 下标序、各自 `toolCallId` 正确配对（被拒 isError、放行正常），不串位。
   - **Ask 人在回路**：default 下请求 `WriteFile` → 收 `AgentEvent.Approval` → 向 `respond` `offer(ALLOW_ONCE/DENY_ONCE)`，断言执行/回灌生效。
   - **永久放行**：送 `ALLOW_FOREVER`，断言 `localPath` 文件被写、含 allow 条。
   - **只读并发不退化**：一批只读不产生任何 `Approval` 事件；被沙箱拦的只读得 errResult、其余仍并发完成。
   - **取消**：在 `Approval` 等待中调用 agent 的 `cancel()`（中断 worker 线程）→ Loop 干净收尾、历史合法、无 virtual thread 泄漏（JUnit 超时 + 线程计数双保险）。
   - **plan 迁移**：`Mode.PLAN` 仍只放只读工具、注入计划提醒（沿用 ch05 断言，枚举换名）。

**验证：** `./gradlew test -Dtest='com.mewcode.agent.*'` 通过；可在 surefire 加 `-XX:+UnlockDiagnosticVMOptions` + 线程 dump 守护超时；新增的取消用例必须在 5 秒内退出。

## T10: TUI 接入（模式切换 + 待批准态）
**文件：** `src/main/java/com/mewcode/tui/MewCodeModel.java`、`src/main/java/com/mewcode/tui/AgentEvent 队列.java`、`src/main/java/com/mewcode/tui/Styles.java + MarkdownRenderer.java`
**依赖：** T8
**步骤：**
1. `MewCodeModel.java`：`Mode mode` 字段改 `permission.Mode mode`；加 `PermissionEngine engine`、`ApprovalRequest pending`、`int approveCursor`（待批准菜单光标）；构造 `MewCodeModel(List<ProviderConfig> providers, String version, ToolToolRegistry registry, PermissionEngine engine)`（**保持原有抛 IOException 风格，仅末尾增形参**）存引擎、`mode = engine.startMode()`；`SessionState.APPROVING` 常量；`onUnhandledInput`（或全局 KeyStroke 拦截）在 `APPROVING` 分派 `updateApproving`；**全局 Ctrl+C/Esc 分派条件 `state == STREAMING` 改为 `state == STREAMING || state == APPROVING`**，approving 态取消时先 `pending.respond().offer(Outcome.DENY_ONCE)`（容量=1 不阻塞）再 `cancelTurn()`；**新增 `case KeyType.ReverseTab:`（仅 `state == IDLE` 生效）`mode = nextMode(mode);`，在 scrollback Panel 追加 noticeLabel 提示新模式**；`Mode nextMode(Mode m)` 即 `Mode.values()[(m.ordinal() + 1) % 4]`，循环 DEFAULT→ACCEPT_EDITS→PLAN→BYPASS→DEFAULT（四档全循环，含 bypass）。
2. `AgentEvent 队列.java`：
   - `beginTurn`：`new Agent(provider, registry, version, engine)`。
   - `handleEvent`：`case AgentEvent.Approval(var req) -> { pending = req; approveCursor = 0; state = APPROVING; }`（**不再立即续读事件**——agent 正阻塞等回传，订阅仍持有，用户选完后下一次 `onNext` 自然到达）。
   - `updateApproving(KeyStroke key)`：维护 `approveCursor`（0/1/2）；`ArrowUp`/`k`、`ArrowDown`/`j` 循环移光标；`Enter`/`Character ' '` 提交当前光标项；数字键 `'1'`/`'2'`/`'3'` 直选；`'y'`=ALLOW_ONCE、`'n'`/`'d'`=DENY_ONCE 便捷键。索引→`Outcome` 经 `outcomeForIndex`（0=ALLOW_ONCE、1=ALLOW_FOREVER、2=DENY_ONCE）。选定后 `pending.respond().offer(outcome)`，`state = STREAMING`、清 `pending`。
   - `submit`：保留 `/plan`(→`Mode.PLAN`)`/do`(→`Mode.DEFAULT`，注入执行指令)`/exit`，作为计划工作流专用入口/出口；**不新增 `/mode`**（模式切换统一走 ReverseTab，见步骤 1）。
3. `Styles.java + MarkdownRenderer.java`：
   - `statusBar`：**左侧不再显示 provider 名，改为常驻显示当前权限模式**——`Mode.DEFAULT`→`DEFAULT`（灰/绿）、`Mode.ACCEPT_EDITS`→`ACCEPT EDITS`、`Mode.PLAN`→`PLAN`（黄）、`Mode.BYPASS`→`BYPASS`（红）；右侧模型名 + token 用量不变。
   - `View` 在 `APPROVING` 态：渲染**多行待批准块** `approvalBlock(pending, approveCursor)`——`● <动作名>` + 缩进参数预览 + 灰字原因 + `是否继续?` + 三行菜单（光标项 `> `+高亮、其余 `  `）`1. 允许本次 / 2. 永久允许（写入本地配置） / 3. 拒绝本次` + 底部灰字 `↑↓ 选择 · 回车确认 · Esc 取消`。

**验证：** `./gradlew shadowJar`（配合 T11）。

## T11: TUI 单测
**文件：** `src/test/java/dev/mewcode/tui/MewCodeModelTest.java`
**依赖：** T10
**步骤：**
1. 既有 `/plan`·`/do` 用例适配 `permission.Mode`（`Mode.PLAN`/`Mode.DEFAULT`）。
2. 新增（用伪事件总线 + 在 GUI thread 外手动驱动 `handleEvent`/`onUnhandledInput`，避免实际起 JLine/tui.tea screen）：
   - 连续模拟 `KeyType.ReverseTab`（IDLE 态）→ 断言 `mode` 依次 DEFAULT→ACCEPT_EDITS→PLAN→BYPASS→DEFAULT、停留 IDLE、每次 scrollback 多一行提示。
   - 模拟收到 `AgentEvent.Approval` → `state == APPROVING`、`pending` 已设、`approveCursor == 0`；按 `ArrowDown` 再 `Enter`→`respond` `poll()` 得到 `Outcome.ALLOW_FOREVER`；另测数字键 `'1'`→`ALLOW_ONCE`、`'3'`→`DENY_ONCE`，回 `STREAMING`。
   - approving 态按 `Esc`/`Ctrl+C`→ 触发取消、`respond` `poll()` 得到兜底 `DENY_ONCE`、不退出程序。
   - `statusBar` 左侧在各模式显示对应模式名（DEFAULT/ACCEPT EDITS/PLAN/BYPASS），且**不含 provider 名**。
   - **模式跨轮保持**：`ReverseTab` 切到 `ACCEPT_EDITS` 后再 `beginTurn`，断言 `mode` 仍为 `ACCEPT_EDITS`（不被重置）。

**验证：** `./gradlew test -Dtest='com.mewcode.tui.*'` 通过。

## T12: Main / smoke / 配置文件接线
**文件：** `src/main/java/com/mewcode/MewCode.java`、`src/main/java/com/mewcode/smoke/MewCode.java`、`build.gradle.kts`、`.gitignore`、`.mewcode/settings.yaml.example`
**依赖：** T6, T8, T10
**步骤：**
1. `MewCode.java`：`Path root = Path.of("").toAbsolutePath();`（必要时 `try { root = root.toRealPath(); } catch(IOException ignore) {}`）；`PermissionEngine engine = PermissionEngine.create(root);`（create 内部已把 IO 失败降级 + stderr 警告）；`new MewCodeModel(cfg.providers(), version, registry, engine).run();`。
2. `smoke/MewCode.java`：新增 `Path cwd = Path.of("").toAbsolutePath();`；`PermissionEngine engine = PermissionEngine.create(cwd);`；`new Agent(provider, DefaultToolRegistry.create(), "dev", engine)`；`run(conv, Mode.BYPASS)`。
3. `build.gradle.kts`：加 `<dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>`（版本 2.18.x 对齐 openai-java 用的）。
4. `.gitignore`：在「本地配置」段追加 `.mewcode/settings.local.yaml`。
5. `.mewcode/settings.yaml.example`：示例——`defaultMode: default`；`permissions.allow: ["Bash(git *)", "Bash(./gradlew test)"]`；`permissions.deny: ["Bash(rm *)", "Read(.env)", "Write(.env)"]`；注释说明三层文件与优先级，并注明**只读类默认即 Allow，allow 规则主要用于提前放行 Bash/Write，deny 规则可对只读做围栏（如 Read(.env)）**。

**验证：** `./gradlew shadowJar` 全绿；`./gradlew exec:java -Dexec.mainClass=com.mewcode.smoke.Main` 在含 `WriteFile` 的脚本下**不阻塞、跑完**（确认 `Mode.BYPASS` 跳过 Ask）；`java -jar build/libs/mewcode.jar` 能正常启动进对话。

## T13: 全量编译测试与规范
**文件：** —
**依赖：** T1–T12
**步骤：**
1. `./gradlew spotless:apply`（google-java-format）→ `./gradlew compileJava` (代码风格由 IDE 保证) 无 diff。
2. `./gradlew shadowJar` 无错误；`./gradlew test` 全部通过（permission、agent、tui、config、conversation 单测）。
3. 重点保护：取消/阻塞类用例必须在 surefire `<forkedProcessTimeoutInSeconds>` 内退出（建议 60s 上限）；JUnit 5 `@Timeout(value=5, unit=SECONDS)` 守在每个取消用例上。
4. 确认 `.mewcode/settings.local.yaml` 已被 gitignore（`git check-ignore`）；检索输出无 `api_key` 明文。
5. **tmux 实跑冒烟**（CLAUDE.md 开发原则第 2 条）：default 下写文件触发 Ask 弹窗；Shift+Tab 循环到 `bypassPermissions` 后不再 Ask、状态栏左侧显示 `BYPASS`；`rm -rf /` 在 bypass 下仍被拦。

**验证：** 全部通过。

## 执行顺序

```
T1(类型) ─┬───────────────────────────────────┐
T2(黑名单)─┤                                    │
T3(沙箱) ──┤                                    ├─→ T6(引擎/流水线) ─→ T7(规则写入)
T4(规则) ──┴─→ T5(配置/映射) ───────────────────┘                          │
                                                                            │
                                              T6,T7 ─→ T8(agent 接入) ─┬─→ T9(agent 单测)
                                                                       ├─→ T10(TUI 接入) ─┬─→ T11(TUI 单测)
                                                                       │                  │
                                                          T6,T8,T10 ─→ T12(main/smoke/配置)
全部 ─→ T13(编译/测试/spotless/tmux)
```
（依赖：T5←{T1,T4}；T6←{T1,T2,T3,T4,T5}；T7←{T5,T6}；T8←{T6,T7}；T9←T8；T10←T8；T11←T10；T12←{T6,T8,T10}；T13←全部。）