# 权限系统 Plan

> 技术栈：Java 21（virtual thread / record / sealed interface / pattern matching）；沿用 anthropic-java / openai-java（本章**不改 provider 适配层**）。权限判定全部落在 agent 编排层与新增的 permission 包，与协议无关。构建仍走 Gradle。

## 架构概览

ch06 新增一个 **permission 包**承载前四层防御与配置加载，并在 **agent 包**把判定接入工具执行链、由 agent 编排承载第五层人在回路；**tui 包**新增「待批准」交互态承载人在回路的 UI；**Main** 负责装配引擎并注入。**不改 llm / provider 适配层**（N6 跨协议一致天然成立）。

> 五层边界澄清：`PermissionEngine.check` 实现**前四层**（黑名单/沙箱/规则/模式兜底），以返回 `Ask` 作为「请走第五层」的信号；**第五层人在回路由 agent 在 Ask 后编排驱动**（发 Approval 事件、阻塞等决策）。二者合称五层。

- **permission 包（新增）**：定义 `Mode`（四档枚举）、`Decision`（Allow/Deny/Ask）、`Category`（READ/WRITE/EXEC）；实现前四层判定 `PermissionEngine.check`；持有黑名单正则集、沙箱（项目根 + 符号链接解析）、三级规则集（user/project/local 三个配置文件）、模式兜底矩阵、友好名映射与路径提取。对外暴露 `check`、本地规则持久化、配置加载。仅依赖 `llm`（取 `ToolCall`）与 JDK 标准库 + SnakeYAML Engine + Jackson（解析 ToolCall.input 的 JSON）。
- **agent 包（改造）**：原 `agent.Mode` 枚举迁移到 permission 包（`NORMAL`→`DEFAULT`，新增 `ACCEPT_EDITS`/`BYPASS`）；`Agent` 持有 `PermissionEngine`；`executeBatched` 在执行每个工具前调用 `PermissionEngine.check`——Allow 执行、Deny 直接产被拒结果、Ask 通过 `BlockingQueue<AgentEvent>` 发出 `ApprovalRequest` 事件并**阻塞**等 TUI 回传决策（通过 `BlockingQueue<Outcome>` 单元素回传）；新增 `ApprovalRequest` 事件类型与决策回传通道。plan 档的只读工具集与提醒沿用 ch04（键 `mode == permission.Mode.PLAN`）。
- **tui 包（改造）**：`MewCodeModel.mode` 改为 `permission.Mode`，持有 `PermissionEngine`；新增 `APPROVING` 态与待批准请求渲染/按键处理；**全局 Ctrl+C/Esc 分派从仅 `STREAMING` 扩展到 `STREAMING || APPROVING`**（见下，否则 approving 态 Ctrl+C 会退出整个程序）；**新增全局 `Shift+Tab` 按键循环切换权限模式**（仅 IDLE 态生效）；状态栏左侧改为**常驻显示当前权限模式（取代 provider 名）**；把会话/永久放行的规则写入交给引擎（经 agent 在 Loop 内应用，TUI 只回传用户选择）。
- **Main（改造）**：用项目根（`Path.of("").toAbsolutePath().toRealPath()`）构造 `PermissionEngine.create`、注入 `MewCodeModel`。
- **smoke（改造）**：非交互，以 `Mode.BYPASS` 运行（无法人在回路、避免阻塞在 Ask），构造一个根于 cwd 的引擎。

数据流（单个工具调用）：
```
agent.executeBatched(calls, mode)
  └→ readOnly 实参由批类型决定（只读批=true / 串行批=false，等价于 registry.isReadOnly(name)）
     Decision d = engine.check(mode, call, readOnly);   // 前四层，短路：
       ① 黑名单(仅 EXEC 类)  → 命中 Deny
       ② 沙箱(仅文件类)      → 逃逸 Deny
       ③ 规则引擎(三级)      → 命中 allow→Allow / deny→Deny
       ④ 模式兜底矩阵        → Allow 或 Ask
  d.kind==Allow → tool.execute
  d.kind==Deny  → new ToolResult(call.id(), reason, /*isError*/true) 回灌
  d.kind==Ask   → (第五层) emit AgentEvent.Approval(name, args, reason, respond)
                  → 阻塞 respond.take()
                  用户三选一(↑↓+回车 / 数字键 1·2·3) → ALLOW_ONCE(执行) /
                            ALLOW_FOREVER(engine.persistLocalAllow+执行) / DENY_ONCE(回灌)
```

## 核心数据结构

### permission.Mode（迁移自 agent + 扩展）
```java
package com.mewcode.permission;

public enum Mode {
    DEFAULT,        // 只读 Allow / 文件写 Ask / 命令执行 Ask
    ACCEPT_EDITS,   // 文件写 Allow / 命令执行 Ask
    PLAN,           // 仅只读工具可见（沿用 ch04）；矩阵同 default 作防御兜底
    BYPASS;         // 全 Allow（黑名单/沙箱仍拦）

    public String displayName() { ... } // "default"/"acceptEdits"/"plan"/"bypassPermissions"
    public static java.util.Optional<Mode> parse(String s) { ... } // 大小写不敏感识别四档名
}
```

### permission.Decision / Category / Outcome
```java
public enum Decision { ALLOW, DENY, ASK }

public enum Category { READ, WRITE, EXEC }

public enum Outcome {
    DENY_ONCE,      // 拒绝本次
    ALLOW_ONCE,     // 允许本次（不留规则）
    ALLOW_FOREVER   // 永久允许（+写本地层文件，精确匹配）
}
```

### permission.Rule / RuleSet
```java
public record Rule(
        String tool,    // 友好名：Bash/Read/Write/Edit/Glob/Grep
        String pattern, // 模式段；"" 表示匹配该工具全部调用
        boolean allow   // true=allow，false=deny
) {}

public final class RuleSet {
    private final java.util.List<Rule> allow;
    private final java.util.List<Rule> deny;

    /** 先 deny 再 allow；返回 (命中?, 命中时的 Decision)；未命中返回空 Optional。 */
    public java.util.Optional<Decision> match(String friendly, String target) { ... }
}
```

### permission.Settings（单个 YAML 文件结构，F4）
```java
// 通过 SnakeYAML Engine 解析为 Map<String,Object>，再手动绑定到下面的 record：
public record Settings(
        String defaultMode,                // 可选：default/acceptEdits/plan/bypassPermissions
        java.util.List<String> allow,      // permissions.allow
        java.util.List<String> deny        // permissions.deny
) {
    public static Settings empty() { return new Settings(null, java.util.List.of(), java.util.List.of()); }
}
```

### permission.PermissionEngine（核心，前四层 + 配置）
```java
public final class PermissionEngine {
    private final Path root;                          // 项目根（绝对、已 toRealPath）
    private final java.util.List<java.util.regex.Pattern> blacklist; // 内置危险命令正则（不可配，N1）
    private final RuleSet user;
    private final RuleSet project;
    private final RuleSet local;
    private final Path localPath;                     // 永久放行的写入目标（本地层文件）
    private final Mode startMode;                     // 启动默认模式（取自配置）
    // ... 构造与方法见下
}
```

### permission.Outcome（人在回路三选一结果）
见上文 enum；TUI 经 `BlockingQueue<Outcome>` 回传给 agent。

### agent.ApprovalRequest / AgentEvent（新增，人在回路 F8）
```java
public record ApprovalRequest(
        String name,    // 工具内部名（用于展示 ● name(args)）
        String args,    // 参数预览
        String reason,  // 触发 Ask 的原因（模式 + 类别）
        java.util.concurrent.BlockingQueue<Outcome> respond  // 容量=1：TUI 回传用户选择，agent 单次接收
) {}

// AgentEvent（原 ch04 sealed interface StreamEvent 的兄弟）新增成员：
//   record Approval(ApprovalRequest request) implements AgentEvent {}
//
// 消费者收到 Approval 后必须把用户选择放进 request.respond()，否则 agent 永远阻塞；
// 取消路径下由 TUI 兜底放入 DENY_ONCE 解阻塞。
```

## 核心接口

### permission 包
```java
/**
 * 构造：解析项目根、加载三层配置、编译黑名单、确定启动模式。
 * 即使发生致命错误（仅当项目根不可解析时），也返回非 null 的"空规则安全引擎"
 * （root 退化为传入值、四层规则空、startMode=DEFAULT）+ 把异常作为 warning 抛出/打日志；
 * 配置文件格式错误绝不致错，只降级该文件为空。
 */
public static PermissionEngine create(Path root) { ... }

/**
 * 前四层判定（agent 每次执行工具前调用）；readOnly 由调用方按批类型给定（等价 registry.isReadOnly）。
 * 返回 CheckResult(Decision, String reason)；reason 文案见下「Decision→reason 来源表」。
 */
public CheckResult check(Mode mode, ToolCallComplete tc, boolean readOnly) { ... }

public record CheckResult(Decision decision, String reason) {}

/** 永久放行由 agent 在人在回路后应用（生成精确规则）： */
public void persistLocalAllow(ToolCallComplete tc) throws java.io.IOException { ... }

public Mode startMode() { return startMode; }
```

**check → reason 文案来源表**（统一文案，供 Deny 回灌与 Ask 展示一致）：

| 裁决来源 | reason 文案（示例） |
|---|---|
| 黑名单命中 | `命中危险命令黑名单：<命令片段>` |
| 沙箱逃逸 | `路径在项目目录之外：<target>` |
| deny 规则命中 | `匹配 deny 规则：<Tool(pattern)>` |
| 模式兜底 Ask | `<mode> 模式下 <category> 类操作需确认` |
| Allow（各来源） | `""`（空，无需展示） |

**内部辅助方法**（标注所属文件）：
```java
// Settings.java / FriendlyNames.java：
static String friendlyName(String internal)         // bash→Bash, read_file→Read, write_file→Write, edit_file→Edit, glob→Glob, grep→Grep；未知原样
static Category categorize(String internal, boolean readOnly) // 见下判定表
static TargetInfo extractTarget(ToolCallComplete tc)      // 见下：内部 ObjectMapper.readTree(call.input())
record TargetInfo(String target, boolean isFile, boolean ok) {}

// Rule.java：
static java.util.Optional<Rule> parseRule(String s)
static boolean matchPattern(String pattern, String target) // glob：* 任意串；** 仅文件路径跨段

// PermissionEngine.java：
static Decision modeFallback(Mode mode, Category cat)  // F5 矩阵；只读/bypass→ALLOW，否则 ALLOW|ASK

// Blacklist.java：
static boolean hitsBlacklist(String command)

// Sandbox.java：
boolean sandboxOK(String path)
```

**`categorize` 判定表**（区分 WRITE 与 EXEC 靠内部名，因二者 readOnly 均为 false）：

| 内部工具名 | Category | 说明 |
|---|---|---|
| read_file / glob / grep（或 readOnly==true） | READ | 只读 |
| write_file / edit_file | WRITE | 文件写 |
| bash | EXEC | 命令执行 |
| 未知/未注册工具（readOnly==false） | **EXEC** | N7 最严：归命令执行类，触发模式 Ask；但**黑名单层只对真正的 bash 命令短路**，未知工具因 `extractTarget` 取不到 command 而 isFile=false、target=""，不会被黑名单/沙箱拦，落到规则→模式兜底（EXEC→Ask）。`readOnly==true` 一律 READ，优先于名字判定。 |

**`extractTarget` 解析与失败归属**（blocker 修复，关 N7/AC15）：
- 内部对 `call.input()`（`String`，承载 JSON 文本）用 Jackson `ObjectMapper.readTree(...)` 解析：`read_file/write_file/edit_file` 取 `path`；`glob/grep` 取 `path`（**搜索根目录**，空→`"."`；注意：glob/grep 真正遍历目标是 `pattern`/`Glob` 字段，沙箱只围栏其搜索根 `path`——见决策表「glob/grep 沙箱盲区」）；`Bash` 取 `command`（isFile=false）。
- 返回 `TargetInfo(target, isFile, ok)`：`ok==false` 表示解析失败或缺必填字段。
- **失败归属**：
  - 文件类工具 `ok==false`（input JSON 不可解析 / 缺 path）→ `check` 在沙箱层**直接判 Deny**（`无法解析文件路径参数，安全拒绝`），不静默放行。
  - bash `ok==false`（缺 command）→ command 视为空串，**不命中黑名单**（不短路），落到规则→模式兜底（EXEC→Ask），由人在回路兜底，绝不直接 Allow。
  - 未知工具 → `isFile=false`、走 EXEC 类模式兜底 Ask。

### agent 包（签名变更）
```java
public Agent(LlmClient client, ToolToolRegistry registry, String version, PermissionEngine engine) { ... }
public BlockingQueue<AgentEvent> run(ConversationManager conv, Mode mode) { ... }   // Mode 改 permission.Mode
```

### tui 包
```java
// 现有签名扩展：末尾增 engine 形参（保持 (MewCodeModel, throws IOException) 风格）：
public MewCodeModel(List<ProviderConfig> providers, String version, ToolToolRegistry registry, PermissionEngine engine) { ... }
```

## 模块设计

### permission 包
**职责：** 前四层判定、配置加载与合并、黑名单、沙箱、规则匹配、模式矩阵、永久规则写入。
**关键点：**
- **`check` 流水线（F6，短路）**：
  1. `cat == EXEC && target != null && !target.isEmpty() && hitsBlacklist(target)` → `DENY`（N1，最高优先，bypass 也拦）。
  2. 文件类（`isFile`）：`!ok` → `DENY`（路径参数不可解析）；否则 `!sandboxOK(target)` → `DENY`（N2）。
  3. 规则引擎：按 `local → project → user` 顺序，每层 `match(friendly, target)`；命中 allow→`ALLOW`、deny→`DENY`，**就近命中即返回**。
  4. 未命中 → `modeFallback(mode, cat)` → `ALLOW` 或 `ASK`。
- **黑名单（F1/N1）**：包内 `Blacklist` 类持一个 `private static final List<Pattern> PATTERNS = List.of(Pattern.compile(...), ...);`，匹配命令串。示例模式：`rm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+(/|~|\$HOME|/\*)`、`dd\s+.*of=/dev/`、`:\(\)\s*\{.*\|.*&\s*\}`（fork bomb）、`mkfs\.`、`>\s*/dev/(sd|hd|nvme|disk)`、`chmod\s+-R\s+0?777\s+/` 等。Javadoc 标明「启发式、非完备、不可配置放开」。
- **沙箱（F2/N2）**：`sandboxOK(path)`——空 path 视为 root；用 `Path.of(path)`，若 relative 用 `root.resolve(path)`；`Path resolved = evalSymlinksOrAncestor(abs)`（存在则 `abs.toRealPath()`；不存在则逐级回退到最近**已存在祖先**目录 `toRealPath()` 后用 `.resolve(剩余段)` 拼回）；`return resolved.equals(root) || resolved.startsWith(root)`（`Path.startsWith` 天然按段比对，避免误把 `<root>foo` 当 `<root>/foo`）。
- **规则解析**：`Rule.parse("Bash(git *)")` → `new Rule("Bash", "git *", true/false)`；`"Read"` → `pattern == ""`（全匹配）。加载时 allow/deny 两列分别解析；非法条目跳过并降级（N5）。
- **匹配（`matchPattern`）**：命令用「命令 glob」——`*` 匹配任意字符（含空格），其余字面，`**` 等价 `*`；文件路径用 `*`（段内）/`**`（跨段）匹配，目标为项目相对 slash 路径。`pattern.isEmpty()` 恒匹配。实现用「先把 `**` 折叠成 `*`（命令）」或「按 `/` 分段递归匹配（路径）」两条分支，避免直接编译成无界正则爆炸。
- **`persistLocalAllow`**（人在回路「永久」调用）：据 `extractTarget` + `friendlyName` 生成**精确**规则（`Bash(<command>)` 或 `Write(<relpath>)` 等，无通配）；Bash 命令串经 `escapeGlob` 转义字面 `*`/`?` 等防止规则被泛化；用 SnakeYAML Engine 读 `localPath`（缺失则空 Settings）→ 追加规则串到 `permissions.allow`（去重）→ 用 `Dump` 写回 → 同步把 `Rule` 并入 `local` 内存 `RuleSet`。失败仅抛 `IOException`（agent 侧捕获只记录不阻断执行）。
- **配置加载**：`loadSettings(Path path)`：文件不存在→`Settings.empty()`、不抛；`yaml.loadFromInputStream` 失败→返回 `Settings.empty()` 并把异常作为 warning 上抛给 `create` 决定（**不致命**）。`create` 顺序加载 user/project/local，`startMode` 依次取 local/project/user 的 `defaultMode`（`Mode.parse` 成功者，local 优先），皆空→`Mode.DEFAULT`。**唯一可能抛出的情形是 `resolveRoot` 失败**（IOException），此时仍返回非 null 空规则安全引擎并把异常打到 stderr。
**依赖：** `llm`（`ToolCall`）、`org.yaml:snakeyaml`、`com.fasterxml.jackson.core:jackson-databind`（解析 `ToolCall.input()` 的 JSON）、JDK 标准库（`java.util.regex` / `java.nio.file`）。不依赖 agent/tool/tui。

### agent 包（Agent.java）
**职责：** 在工具执行链接入前四层判定；承载第五层人在回路；模式类型迁移。
**关键点：**
- `Mode` 相关枚举从 agent 删除，改用 `permission.Mode`；`run` 形参与 `mode == Mode.PLAN` 判断更新；`defs` 选择、`PlanReminder` 注入逻辑不变（仅枚举换名）。无论 plan 来自 `/plan` 命令还是 `defaultMode=plan` 配置，agent 一律按 `mode == Mode.PLAN` 应用只读工具集 + 计划提醒。
- `Agent` 加 `private final PermissionEngine engine;` 字段；构造增参。
- **被拒结果构造**：agent 包内无 `errResult` 工厂（那是 tool 包未导出方法）；Deny 分支直接 `new ToolResult(calls.get(k).id(), reason, /*isError*/true)`（与既有 `executeBatched` 结果构造一致）。
- 新增 `record ApprovalRequest(String name, String args, String reason, BlockingQueue<Outcome> respond) {}`；`AgentEvent` 加 `record Approval(ApprovalRequest request) implements AgentEvent {}`。
- `executeBatched(calls, mode, publisher)`（增 `mode` 形参）：
  - **只读批**：对区间内每个 `calls.get(k)` 先 `CheckResult cr = engine.check(mode, calls.get(k), true)`；按调用序发 `PhaseStart`；`cr.decision() == DENY`→ 预置 `results[k] = new ToolResult(..., reason, /*isError*/true)`、`done[k] = true`、**不纳入并发执行**；`cr.decision() == ALLOW`→ 纳入并发（只读永不 Ask，N3 并发不退化）。并发执行完后按调用序发 `PhaseEnd`（Deny 项 `isError=true`、Allow 项为真实结果），**Deny 与 Allow 项的开始/结束事件均按调用序**，与有副作用 Deny 行为一致。
  - **有副作用串行**：`CheckResult cr = engine.check(mode, calls.get(i), false)`；`ALLOW`→执行；`DENY`→`new ToolResult(..., reason, isError=true)`；`ASK`→`Outcome o = requestApproval(calls.get(i), cr.reason(), publisher)` 拿 `Outcome`：取消（中断/`null`）→取消收尾（`completed = false`，沿用既有取消路径）；`ALLOW_ONCE`→执行；`ALLOW_FOREVER`→`engine.persistLocalAllow(calls.get(i))`（IOException 仅记不阻断）+执行；`DENY_ONCE`→被拒结果。
- `Outcome requestApproval(ToolCallComplete tc, String reason, BlockingQueue<AgentEvent> publisher)`：
  ```java
  BlockingQueue<Outcome> respond = new ArrayBlockingQueue<>(1);
  publisher.submit(new AgentEvent.Approval(
      new ApprovalRequest(tc.toolName(), argsPreview(call.input()), reason, respond)));
  try {
      return respond.take();          // 阻塞等 TUI；中断由外层 cancel 路径触发
  } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return null;                    // 由调用方走取消收尾
  }
  ```

### tui 包（MewCodeModel.java / AgentEvent 队列.java / Styles.java + MarkdownRenderer.java；（provider 选择逻辑集成在 MewCodeModel 内） 不动）
**职责：** 新增待批准交互态；模式切换命令；状态栏模式徽标；全局取消覆盖 approving 态。
**关键点：**
- `MewCodeModel`：`Mode mode`→`permission.Mode mode`（初值 `engine.startMode()`）；加 `PermissionEngine engine`、`ApprovalRequest pending`、`int approveCursor`。
- 构造 `MewCodeModel(providers, version, registry, engine)`（保持现有抛 `IOException` 风格）：存引擎、置初始模式。
- **全局按键分派（blocker 修复）**：在 JLine/tui.tea 的全局 `KeyStroke` 拦截器（顶层 `WindowListener.onUnhandledInput` 或 `BasicWindow` keyboard handler）里，`Ctrl+C`/`Esc` 的 `state == STREAMING` 条件改为 `state == STREAMING || state == APPROVING`；在 approving 态触发取消时，先向 `pending.respond()` `offer(Outcome.DENY_ONCE)`（容量=1 不阻塞，兜底解 agent 阻塞），再调 `cancelTurn()`。
- `handleEvent` 处理 `AgentEvent.Approval`：保存 `pending`、`approveCursor = 0`、切 `APPROVING` 状态，**不再立即请求下一个事件**（agent 正阻塞等回传，订阅仍持有，下次 `onNext` 在用户选完后自然到达）。
- `updateApproving(KeyStroke key)`：维护 `approveCursor`（0/1/2）；`ArrowUp`/`k`、`ArrowDown`/`j` 循环移动光标；`Enter` 提交当前光标项；数字键 `'1'`/`'2'`/`'3'` 直选并提交；另 `y`=允许本次、`n`/`d`=拒绝本次 便捷键。索引→`Outcome` 由 `outcomeForIndex` 显式映射（0=ALLOW_ONCE、1=ALLOW_FOREVER、2=DENY_ONCE）。选定后回 `STREAMING`、清 `pending`，`pending.respond().offer(outcome)`（agent `take()` 即解阻塞）。
- `View` / `APPROVING` 渲染：**多行待批准块**——`● <动作名>` + 缩进参数预览、灰字触发原因、`是否继续?`、三行菜单（当前光标项以 `> ` + 高亮色 `Label`，其余 `  ` 前缀）`1. 允许本次 / 2. 永久允许（写入本地配置） / 3. 拒绝本次`、底部灰字 `↑↓ 选择 · 回车确认 · Esc 取消`；`approvalBlock(req, cursor)` 据 `cursor` 高亮当前项。
- **Shift+Tab 循环切换**：在顶层 `KeyStroke` 拦截器加 `case KeyType.ReverseTab`（JLine/tui.tea 把 `Shift+Tab` 映射成 `ReverseTab`），仅 `state == IDLE` 生效，streaming/approving 态忽略；`mode = nextMode(mode)`，`nextMode` 即 `Mode.values()[(mode.ordinal() + 1) % 4]`，循环 DEFAULT→ACCEPT_EDITS→PLAN→BYPASS→DEFAULT（四档全循环，含 bypass，用户拍板）；在 scrollback Panel 追加一行 noticeLabel 提示新模式。切到/切出 plan 时同样作用于 agent（`mode == PLAN` 即只读 defs + PlanReminder），但 Shift+Tab **不**注入 `/do` 的执行指令。
- `submit`：保留 `/plan`(→`Mode.PLAN`)`/do`(→`Mode.DEFAULT`，固定回 default 并注入执行指令)`/exit`，作为计划工作流的专用入口/出口；**不再新增 `/mode` 命令**（模式切换统一走 Shift+Tab）。
- `statusBar`：左侧改为**常驻显示当前权限模式**（取代 provider 名）：`Mode.DEFAULT`→`DEFAULT`（灰/绿）、`Mode.ACCEPT_EDITS`→`ACCEPT EDITS`、`Mode.PLAN`→`PLAN`（黄）、`Mode.BYPASS`→`BYPASS`（红）；右侧保留模型名 + token 用量不变。可在启动提示行（`Prompt` 的 ready hint）补「Shift+Tab 切换权限模式」。

### Main / smoke
- `MewCode.java`：`Path root = Path.of("").toAbsolutePath();`，能 `toRealPath()` 就用，失败保留 `root`；`PermissionEngine engine = PermissionEngine.create(root);`（create 内部已把 IO 失败降级为空规则引擎，只在 stderr 打 `权限引擎降级:...`）；`new MewCodeModel(cfg.providers(), version, registry, engine).run()`。
- `smoke/MewCode.java`：新增 `Path cwd = Path.of("").toAbsolutePath();`；`PermissionEngine engine = PermissionEngine.create(cwd);`；`new Agent(provider, DefaultToolRegistry.create(), "dev", engine)`；`agent.run(conv, Mode.BYPASS)`。确认 smoke 现有用例文件操作目标均在 cwd 子树内（否则会被沙箱拦）。

## 模块交互

```
Main → PermissionEngine.create(root) → new MewCodeModel(..., engine)
TUI ─按 Shift+Tab→ mode 循环切换 DEFAULT→ACCEPT_EDITS→PLAN→BYPASS→DEFAULT（跨轮保持）
TUI ─beginTurn→ new Agent(provider, registry, version, engine).run(conv, mode)
  agent.executeBatched(calls, mode):
    CheckResult cr = engine.check(mode, call, readOnly(批类型));   // 前四层
    ALLOW → tool.execute
    DENY  → ToolResult(..., reason, isError=true)  ──回灌──→ conv.addToolResults
    ASK   → AgentEvent.Approval(ApprovalRequest{..., respond}) ──→ TUI(APPROVING)   // 第五层（三选一菜单）
                                                       ←── respond.offer(outcome) ──
            ALLOW_FOREVER → engine.persistLocalAllow(call) (写本地层文件)
            → 执行(ALLOW_ONCE/ALLOW_FOREVER) 或 回灌(DENY_ONCE)
```

依赖方向（无环）：`tui → {agent, permission, config, llm, ...}`；`agent → {permission, llm, tool, conversation, prompt}`；`permission → llm`。`llm` 不变、不 import permission。

## 文件组织

```
mewcode/
├── src/main/java/com/mewcode/permission/
│   ├── Mode.java               — 新：Mode 四档 + displayName/parse；Decision/Category/Outcome 同包定义或拆分文件
│   ├── Decision.java           — 新：enum Decision { ALLOW, DENY, ASK }
│   ├── Category.java           — 新：enum Category { READ, WRITE, EXEC }
│   ├── Outcome.java            — 新：enum Outcome { DENY_ONCE, ALLOW_ONCE, ALLOW_FOREVER }
│   ├── PermissionEngine.java   — 新：create、check 前四层流水线、modeFallback、startMode、CheckResult record
│   ├── Blacklist.java          — 新：内置危险命令正则集 + hitsBlacklist（不可配，N1）
│   ├── Sandbox.java            — 新：sandboxOK、evalSymlinksOrAncestor、resolveRoot（N2）
│   ├── Rule.java               — 新：record Rule / parseRule / matchPattern(glob)
│   ├── RuleSet.java            — 新：RuleSet 持有 allow/deny + match()
│   ├── Settings.java           — 新：record Settings、loadSettings(SnakeYAML)、toRuleSet、FriendlyNames、Categorizer、TargetExtractor
│   └── Persister.java          — 新：persistLocalAllow、ruleFor（写本地层文件）
├── src/test/java/dev/mewcode/permission/
│   └── *Test.java              — 新：黑名单/沙箱(含祖先回退)/规则/优先级/矩阵/加载降级/解析失败 单测（JUnit 5）
├── src/main/java/com/mewcode/agent/
│   ├── Agent.java              — 改：删 Mode（迁 permission）；Agent 加 engine；executeBatched(+mode) 接入 check；requestApproval；ApprovalRequest record；AgentEvent.Approval；Deny 用 ToolResult 构造
│   ├── ApprovalRequest.java    — 新：record（也可作 Agent 内部 record）
│   └── ...（原文件保持）
├── src/test/java/dev/mewcode/agent/
│   └── AgentTest.java          — 改/新：权限集成(Allow/Deny/Ask/会话/永久)、保序、只读并发不退化、取消、模式迁移
├── src/main/java/com/mewcode/tui/
│   ├── MewCodeModel.java             — 改：mode→permission.Mode、加 engine/pending/approveCursor；构造增参；APPROVING 分派；全局 Ctrl+C/Esc 覆盖 approving；Shift+Tab (ReverseTab) 循环模式(nextMode)
│   ├── AgentEvent 队列.java         — 改：handleEvent 处理 Approval；updateApproving；sendOutcome；submit 保留 /plan·/do（去掉 /mode）；beginTurn 传 engine
│   └── Styles.java + MarkdownRenderer.java               — 改：statusBar 左侧常驻模式(取代 provider 名)；待批准块渲染
├── src/test/java/dev/mewcode/tui/
│   └── MewCodeModelTest.java         — 改：Shift+Tab 循环切换、approval 态按键回传、Esc 取消兜底、状态栏常驻模式、模式跨轮保持
├── src/main/java/com/mewcode/config/  — 不改（provider 配置与 permission settings 分离）
├── src/main/java/com/mewcode/MewCode.java                — 改：构造 PermissionEngine 注入 MewCodeModel
├── src/main/java/com/mewcode/smoke/MewCode.java          — 改：cwd + 构造引擎、Mode.BYPASS 运行
├── build.gradle.kts                                            — 改：加 jackson-databind 依赖（解析 ToolCall.input JSON）
├── .gitignore                                         — 改：加 .mewcode/settings.local.yaml
└── .mewcode/settings.yaml.example                     — 新：权限配置示例（defaultMode + allow/deny）
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 权限判定落点 | 独立 permission 包(前四层) + agent 编排层(第五层) | 与 provider 解耦（N6 免费）；逻辑内聚、可单测；不污染 tool/llm |
| 五层短路 | `check` 顺序 黑名单→沙箱→规则→模式 单方法 early-return；Ask 作第五层信号 | 满足 F6；黑名单/沙箱按类别跳过；规则就近命中即返回；人在回路在 agent |
| 黑名单不可配 | 包内 `Blacklist.PATTERNS = List.of(...)` 编译好的常量、无加载入口 | N1：任何配置/模式都碰不到它；bypass 也拦 |
| 黑名单完备性 | 启发式、Javadoc 显式声明非完备 | 不可能穷尽危险命令；防御纵深由沙箱+规则+人在回路补 |
| 沙箱解析顺序 | 先 `toRealPath()`（或最近祖先）再 `Path.startsWith` 比对 | N2：防软链接逃逸；新建文件按已存在祖先判，避免误判 |
| 沙箱不管命令执行 | Bash 不做路径围栏 | 无法可靠静态解析任意命令的文件访问；交黑名单+规则+模式 |
| glob/grep 沙箱盲区 | `extractTarget` 取其搜索根 `path` 做围栏；`pattern` 不参与沙箱 | glob/grep 真正遍历目标是 pattern，但任意 pattern 的越界遍历由工具内部 `Files.walkFileTree`（不跟随目录软链接）限制；沙箱对 glob/grep 为**尽力围栏搜索根**，登记为已知盲区 |
| Mode 归属 | 迁到 permission 包、四档统一 enum | 用户拍板「统一一个模式轴」；mode 是权限概念，agent/tui 共用 |
| 模式切换方式 | Shift+Tab（JLine/tui.tea `ReverseTab`）循环四档（含 bypass）；保留 /plan·/do | 用户拍板用 Shift+Tab、四档全循环；/plan·/do 保留计划工作流的执行语义；不再设 /mode 命令 |
| 状态栏左侧内容 | 常驻显示当前权限模式，取代 provider 名 | 用户拍板「别展示 provider 名、展示权限模式」；右侧模型名+用量不变 |
| plan 语义 | 沿用 ch04 硬限制（只读工具集+提醒）+ /do | 用户拍板；矩阵 plan 行仅防御性兜底；/plan 与 defaultMode=plan 都按 Mode.PLAN 应用 |
| 模式兜底值域 | 只产 Allow/Ask（无 Deny 档） | 用户拍板矩阵；Deny 仅来自黑名单/沙箱/deny 规则/人在回路 |
| 规则优先级 | 会话>本地>项目>用户；同层 deny 优先 allow | 用户拍板「越靠近会话越优先」；deny 优先更安全 |
| 永久放行落点 | 写本地层 `.mewcode/settings.local.yaml`（gitignore） | 用户拍板；不进 git、不影响队友（对齐 Claude Code don't-ask-again） |
| 自动规则泛化 | 不泛化，只生成精确规则 | 自动猜泛化模式有误放行风险；泛化交用户手写 |
| 规则名 | 友好名 Bash/Read/Write/Edit/Glob/Grep ↔ 内部名映射 | 用户示例即友好名；对齐 Claude Code 习惯，规则更可读 |
| 参数解析失败归属 | 文件类不可解析→Deny；bash 缺 command→落 Ask；未知工具→EXEC/Ask | N7/AC15 安全默认，绝不静默 Allow |
| 人在回路选项集 | 三选一（允许本次/永久/拒绝）+ 菜单式 ↑↓·回车·数字键直选、默认高亮允许本次 | 用户拍板 1:1 复刻 Claude Code；永久=精确写本地配置；砍掉本会话 Outcome（会话级层移除，规则只走三个文件层） |
| 人在回路回路 | `AgentEvent.Approval(ApprovalRequest{respond})` + agent 用 `BlockingQueue.take()` 阻塞等回传 | JLine/tui.tea 的 GUI 线程不能阻塞，agent 跑在 virtual thread 内可 `take()` 阻塞；中断由 cancel 路径触发解阻塞（N4） |
| respond 队列 | `ArrayBlockingQueue<>(1)` | TUI `offer(...)` 送决策永不阻塞；取消竞态下兜底 offer DENY_ONCE 不泄漏 |
| approving 态取消 | 全局 Ctrl+C/Esc 分派覆盖 APPROVING | 否则 approving 态 Ctrl+C 走默认 quit handler 退出程序，违 N4 |
| 会话/永久规则写入方 | agent 在 Loop 内调引擎（TUI 只回传 Outcome） | 引擎状态变更集中一处；职责清晰 |
| 只读权限检查 | 批内逐个 check，但只读永不 Ask | N3：保留 ch04 并发；只读最多被沙箱/deny 规则拦为 Deny，无需交互 |
| settings 与 config 分离 | 新 settings.yaml(.local) 而非塞进 config.yaml | 权限配置与 provider 凭据职责不同；config.yaml 已精确 gitignore（含密钥），settings 项目级需可提交 |
| smoke 运行模式 | Mode.BYPASS、根于 cwd | 非交互无法人在回路；bypass 跳过 Ask（黑名单/沙箱仍在），用例文件操作须落 cwd 内 |
| create 失败处理 | 致命错(仅 resolveRoot)也返回非 null 空规则安全引擎 + stderr 警告 | Main 注入永不为 null、check 不抛 NPE；配置格式错只降级不致错（N5） |
| ToolCall.input 解析 | Jackson `ObjectMapper.readTree` | ch04 的 ToolCall 已以 JSON 文本承载参数；Jackson 已在 SDK 依赖里出现（openai-java 用），加显式依赖更稳；不引入 gson 等额外库 |