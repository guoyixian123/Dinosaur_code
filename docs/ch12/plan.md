# Hook 生命周期挂钩系统 Plan

## 架构概览

本章拆为两个层次实现：

1. **权限匹配器升级层（permission 包内改造）**——把 Pattern 形态从字符串升级到结构化 Matcher 接口；新增 exact/regex/not 三种实现，glob 保留作为缺省类型。改造对外仅暴露语法升级和 stderr 错误回退,运行时 Allow/Deny 语义不变。

2. **Hook 主体层（新建 `com.mewcode.hook` 包）**——加载 YAML 规则、提供事件分派引擎、四类动作执行器；通过 11 个事件 emit 点接入 agent / tui。

模块构成：

- `permission.Matcher`(新)：匹配接口 + 四种实现的工厂（sealed interface + records）
- `hook.HookLoader`(新)：YAML 解析 / 字段校验 / matcher 编译 / 双层文件合并
- `hook.HookEngine`(新)：事件分派、only_once 集合、动作执行器协调
- `hook.HookExecutor`(新)：四类动作的执行入口（shell / prompt / http / subagent stub）
- `hook.ConditionEvaluator`(薄包装)：复用 `permission.Matcher`，做字段路径取值与匹配组合
- `agent`/`tui` 改动：在生命周期 11 个时刻调 `HookEngine.dispatch`
- `command`：新增 `/hooks` 内置命令

## 核心数据结构

### permission.Matcher

```java
// Matcher 是规则匹配的统一接口；四种实现都是 record，sealed permits 限制扩展。
package com.mewcode.permission;

public sealed interface Matcher permits ExactMatcher, GlobMatcher, RegexMatcher, NotMatcher {
    boolean match(String s);
    String describe(); // 调试 / /hooks 输出用
}

public record ExactMatcher(String value) implements Matcher {
    public boolean match(String s) { return s.equals(value); }
    public String describe() { return "=" + value; }
}

public record GlobMatcher(String pattern, boolean command) implements Matcher {
    public boolean match(String s) {
        return command ? GlobMatch.matchCommand(pattern, s) : GlobMatch.matchPath(pattern, s);
    }
    public String describe() { return pattern; }
}

public record RegexMatcher(java.util.regex.Pattern compiled, String source) implements Matcher {
    public boolean match(String s) { return compiled.matcher(s).find(); }
    public String describe() { return "~" + source; }
}

public record NotMatcher(Matcher inner) implements Matcher {
    public boolean match(String s) { return !inner.match(s); }
    public String describe() { return "!" + inner.describe(); }
}

// 工厂：解析单条匹配描述串，返回 Matcher 或抛出 MatcherCompileException。
// 描述串规则：
//   "=value"  -> ExactMatcher
//   "~regex"  -> RegexMatcher
//   "!inner"  -> NotMatcher(compile(inner))
//   "value"   -> GlobMatcher（缺省，沿用现有 GlobMatch.matchPath/matchCommand 语义）
// Bash 工具沿用整串通配（matchCommand），其它沿用 matchPath。
// 调用方在 RuleSet 那侧通过 friendly 名分流到对应底层匹配函数；matcher 这边只关心模式串。
public final class Matchers {
    public static Matcher compile(String pattern, boolean command) throws MatcherCompileException { ... }
}
```

### permission.PermissionRule(改造)

```java
public record PermissionRule(
        String tool,    // 不变
        Matcher matcher, // 替换原 String pattern；null 表示「该工具全匹配」
        boolean allow,
        String raw      // 原始模式串，仅供错误日志与调试
) {}
```

`PermissionRule.parse(String)` 升级：识别前缀，调用 `Matchers.compile` 构造 matcher。失败时抛 `RuleParseException`；调用方（`SettingsLoader.toRuleSet`）捕获后写 stderr 并跳过。

### hook.HookRule

```java
package com.mewcode.hook;

import java.time.Duration;

public record HookRule(
        String name,
        Event event,           // 11 选 1 枚举
        Condition condition,   // null 表示无条件
        Action action,
        boolean onlyOnce,
        boolean async,
        Duration timeout,      // null 用默认 30s
        String source          // 来源文件路径，供 /hooks 显示
) {}

public enum Event {
    SESSION_START, SESSION_END, SESSION_RESUME,
    USER_PROMPT_SUBMIT, STOP, PRE_USER_MESSAGE,
    PRE_TOOL_USE, POST_TOOL_USE,
    PRE_COMPACT, POST_COMPACT,
    NOTIFICATION;

    public boolean isBlocking() { return this == PRE_TOOL_USE || this == USER_PROMPT_SUBMIT; }
    public static java.util.Optional<Event> parse(String s) { ... } // 大小写不敏感、连接符宽松
    public String wireName() { ... } // 序列化成 "SessionStart" / "PreToolUse" 等驼峰
}
```

### hook.Condition

```java
public record Condition(CombineMode mode, java.util.List<AtomCondition> atoms) {}

public enum CombineMode { ALL_OF, ANY_OF } // 二选一不混用

public record AtomCondition(
        String field,             // 形如 "tool_input.path"
        com.mewcode.permission.Matcher matcher // 复用四种匹配类型
) {}
```

### hook.Action

```java
public sealed interface Action permits Action.Shell, Action.Prompt, Action.Http, Action.Subagent {
    record Shell(String command) implements Action {}
    record Prompt(String text) implements Action {}
    record Http(
            String url,
            String method,                    // 默认 POST
            java.util.Map<String, String> headers,
            String bodyTemplate               // null 时序列化 payload 为 JSON
    ) implements Action {}
    record Subagent(String agentName, String prompt) implements Action {}
}
```

### hook.Payload

```java
// Payload 是事件分派时携带的上下文数据；条件求值与动作输入都用它。
// 用 LinkedHashMap 装载，JSON 序列化时按 key 字典序排序（N6）。
public final class Payload {
    private final java.util.Map<String, Object> data;
    public Payload(java.util.Map<String, Object> data) { this.data = data; }
    public String getByPath(String path) { ... } // "tool_input.command" 拆分递归取值
    public String toSortedJson() { ... }         // 字典序键的 JSON 字符串
}
```

通用字段约定：`event`、`session_id`、`cwd`、`mode`，加上各事件特化字段。`getByPath("tool_input.command")` 支持嵌套字段访问。

### hook.HookEngine

```java
public final class HookEngine {
    private final java.util.List<HookRule> rules;          // 按加载顺序
    private final java.util.List<String> sources;          // 加载来源文件，供 /hooks 显示

    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.HashSet<String> onceFired = new java.util.HashSet<>();

    private final HookExecutor executor;

    public HookEngine(java.util.List<HookRule> rules, java.util.List<String> sources, HookExecutor executor) { ... }

    public DispatchResult dispatch(java.util.concurrent.CancellationException ctxCanceller, Event event, Payload payload) { ... }
    public void resetForNewSession() { ... }
    public java.util.List<String> sources() { ... }
    public java.util.List<HookRule> rules() { ... }
}

public record DispatchResult(
        boolean blocked,
        String reason,
        String blockingHookName,
        java.util.List<String> injectedPrompts // prompt 动作产生的文本，按声明序
) {
    public static DispatchResult empty() { ... }
}
```

`dispatch` 内部流程：
1. 过滤匹配 event 的 rule
2. 跳过 onceFired 中已触发的 only_once rule
3. 串行求值 condition
4. 命中条件后按 action 类型分发到 `HookExecutor`
5. async rule 起 virtual thread、立即往下走
6. 同步 rule 等结果，拦截类事件下若 result 表达 block，累加到 DispatchResult，跳过后续同事件 rule
7. prompt 类 rule 把 text 累加到 injectedPrompts

### HookExecutor

```java
public final class HookExecutor {
    private final java.net.http.HttpClient httpClient; // 默认 timeout=30s，可被 rule 的 timeout 覆盖

    public HookExecutor() { ... }

    public ExecutionResult run(HookRule rule, Payload payload, boolean blocking, java.time.Duration deadline) { ... }
}

public record ExecutionResult(
        boolean blocked,
        String reason,
        String prompt,           // 仅 prompt 动作非空
        Throwable error          // hook 自身失败（不拦截）
) {
    public static ExecutionResult empty() { ... }
}
```

`run` 内按 `rule.action()` 模式匹配（`switch`）调对应的 private `runShell` / `runPrompt` / `runHttp` / `runSubagent`。

## 模块设计

### 模块 A：permission.Matcher

**职责：** 提供四种匹配类型的统一接口；`Matchers.compile` 解析前缀。
**对外接口：** `Matcher` sealed interface、`Matchers.compile(String pattern, boolean command)`。
**依赖：** Java 标准库 `java.util.regex`。
**改动文件：** `src/main/java/com/mewcode/permission/PermissionRule.java`(扩展 parse / match)、新增 `src/main/java/com/mewcode/permission/Matcher.java` 及四个 record 实现、新增 `Matchers.java` 工厂。

### 模块 B：permission 错误日志

**职责：** `PermissionRule.parse` 失败时 stderr 打印失败规则与原因，原本静默跳过改为有声跳过。
**对外接口：** `SettingsLoader.toRuleSet` 内部行为变化，外部 API 不变。
**依赖：** 模块 A。

### 模块 C：hook.HookLoader

**职责：** 扫描两层 YAML 文件、解析顶层 `hooks:` 数组、字段校验、Matcher 编译、合并去重。
**对外接口：** `HookEngine load(Path projectRoot)`——返回引擎；所有错误走 stderr 不抛出。
**依赖：** 模块 A、`org.yaml:snakeyaml`、`hook.HookEngine`。
**校验项：** name 必填 + 跨文件冲突、event 枚举、condition 顶层 all_of/any_of 互斥、action 类型枚举与子字段、async + 拦截事件冲突、Matcher 编译失败。

### 模块 D：hook.HookEngine

**职责：** dispatch 流程编排、only_once 集合管理、resetForNewSession。
**对外接口：** 见上一节 HookEngine 结构。
**依赖：** 模块 E。

### 模块 E：hook.HookExecutor

**职责：** 四类动作的执行——shell（`ProcessBuilder` + stdin JSON + exit code 2 拦截）、prompt（直接返回 InjectedPrompt）、http（`java.net.http.HttpClient` POST JSON + decision=block 解析）、subagent（stub 占位日志）。
**对外接口：** `run(rule, payload, blocking, deadline) ExecutionResult`。
**依赖：** Java 标准库 `java.lang.ProcessBuilder`、`java.net.http.HttpClient`、`com.fasterxml.jackson` 或手写 JSON（与项目其它处一致）；模板渲染用简易 `${field}` 替换。

### 模块 F：hook.ConditionEvaluator

**职责：** 把 `permission.Matcher` 应用到 payload 的字段路径上。
**对外接口：** `boolean evaluate(Condition cond, Payload payload)`、`String getByPath(Payload payload, String path)`。
**依赖：** 模块 A。

### 模块 G：agent 接入

**职责：** 在 `Agent.run` 等关键路径调 `HookEngine.dispatch`；处理 PreToolUse 拦截、注入 reminder。
**对外接口：** `Agent.Builder.hookEngine(HookEngine)`；agent 私有方法 `dispatchHook(Event event, Payload payload) DispatchResult`。
**依赖：** 模块 D。
**改动文件：** `src/main/java/com/mewcode/agent/Agent.java`、`src/main/java/com/mewcode/agent/SessionRuntime.java`(加 `List<String> pendingReminders`、resetForNewSession 清空)。

### 模块 H：tui 接入

**职责：** SessionStart / SessionEnd / SessionResume / UserPromptSubmit / Notification 五个事件在 TUI 侧 emit；UserPromptSubmit 拦截集成到 `MewCodeModel.submit()` 流程。
**对外接口：** `MewCodeModel` 上私有方法 `dispatchSessionStart` / `dispatchSessionEnd` 等。
**依赖：** 模块 D。
**改动文件：** `src/main/java/com/mewcode/tui/MewCodeModel.java`、`src/main/java/com/mewcode/tui/AgentEvent 队列.java`、`src/main/java/com/mewcode/tui/Commands.java`(/clear、/resume 触发 SessionEnd + SessionStart/Resume)。

### 模块 I：/hooks 命令

**职责：** 输出已加载 hook 列表 + 加载来源文件。
**对外接口：** 注册到 `command.BuiltinCommands.register`。
**依赖：** `MewCodeModel` 暴露 `hookSources()` / `hookRules()` 查询方法（通过 `CommandUi` 接口）。

### 模块 J：Main wiring

**职责：** 在 `MewCode.java` 中调 `HookLoader.load(projectRoot)`，把 HookEngine 注入 agent 与 `MewCodeModel`。
**改动文件：** `src/main/java/com/mewcode/MewCode.java`、`src/main/java/com/mewcode/tui/MewCodeModel.Params`(Builder 加 hookEngine 字段)。

## 模块交互

**启动期数据流：**

```
MewCode.main()
  ├─ PermissionEngine.create(root)          # 用升级后的 PermissionRule.parse（stderr 报错）
  ├─ HookEngine engine = HookLoader.load(root)  # 扫描两层 YAML、构造 HookEngine
  └─ new MewCodeModel.Builder()
          .hookEngine(engine)
          .agent(Agent.builder()...hookEngine(engine).build())
          .build()
```

**SessionStart emit 时机：**

```
Main 完成 wiring → new MewCodeModel(params) → app.run() → init() 渲染 banner
                                                       │
                                                       └─ 首条 user 输入到达前
                                                          init() 末尾调 dispatchSessionStart()
```

实际接入：`MewCodeModel.init()` 末尾调 `dispatchSessionStart()`，该方法同步调 `HookEngine.dispatch`、收集 `injectedPrompts` 注入到 `runtime.pendingReminders`、然后返回。

**UserPromptSubmit 路径：**

```java
void submit() {
    String text = textBox.getText().strip();
    if (isSlash(text)) { dispatchSlash(text); return; }
    DispatchToolResult result = hookEngine.dispatch(null, Event.USER_PROMPT_SUBMIT,
            basePayload(Event.USER_PROMPT_SUBMIT, Map.of("prompt", text)));
    if (result.blocked()) {
        // 输入框下方显示 [hook <name>] reason，不消费输入
        view.appendError("[hook " + result.blockingHookName() + "] " + result.reason());
        return;
    }
    runtime.appendReminders(result.injectedPrompts());
    conversation.addUser(text);
    beginTurn();
}
```

**PreToolUse 拦截路径：**

```java
void executeBatched(List<ToolCall> calls, PermissionMode mode, BlockingQueue<AgentEvent> events) {
    for (ToolCallComplete tc : calls) {
        DispatchToolResult result = hookEngine.dispatch(null, Event.PRE_TOOL_USE,
                basePayload(Event.PRE_TOOL_USE, Map.of("tool_name", tc.toolName(), "tool_input", call.input())));
        if (result.blocked()) {
            emit(events, new PhaseStart(call.id()));  // 用户仍能看到工具被尝试
            results.put(call.id(), hookBlockedResult(call.id(), result.blockingHookName(), result.reason()));
            emit(events, new PhaseEnd(call.id(), /*isError=*/true));
            continue;
        }
        runtime.appendReminders(result.injectedPrompts());
        // ... 原有的权限 check + 执行流程
        runtime.appendReminders(postToolUseDispatch.injectedPrompts());
    }
}
```

**Reminder 注入路径：**

```
Agent.run() 第 iter 轮 streamOnce 之前：
    String reminder = planReminder
                    + String.join("\n\n", runtime.takeReminders());  // 取出并清空 runtime.pendingReminders
    streamOnce(..., reminder, ...);
```

## 文件组织

```
mewcode/
├── build.gradle.kts
├── src/main/java/com/mewcode/
│   ├── permission/
│   │   ├── Matcher.java               # 新增：sealed interface
│   │   ├── ExactMatcher.java          # 新增：record
│   │   ├── GlobMatcher.java           # 新增：record
│   │   ├── RegexMatcher.java          # 新增：record
│   │   ├── NotMatcher.java            # 新增：record
│   │   ├── Matchers.java              # 新增：compile 工厂 + 异常
│   │   ├── PermissionRule.java        # 改造：parse 识别前缀、record 持有 Matcher
│   │   ├── SettingsLoader.java        # 改造：toRuleSet 报 stderr
│   │   └── ...
│   ├── hook/                          # 全新包
│   │   ├── package-info.java          # 包注释
│   │   ├── Event.java                 # 11 个枚举值 + isBlocking + parse
│   │   ├── HookRule.java              # record
│   │   ├── Condition.java             # record
│   │   ├── AtomCondition.java         # record
│   │   ├── CombineMode.java           # enum
│   │   ├── Action.java                # sealed interface + 4 个 record
│   │   ├── Payload.java               # 字典序 JSON + getByPath
│   │   ├── ConditionEvaluator.java    # evaluate + getByPath
│   │   ├── HookLoader.java            # YAML 解析、字段校验、双层合并
│   │   ├── HookEngine.java            # dispatch 主流程 + only_once 集合
│   │   ├── HookExecutor.java          # 四类 action 执行器
│   │   └── DispatchResult.java        # record
│   ├── agent/
│   │   ├── Agent.java                 # 增 dispatchHook 与 PreToolUse/PostToolUse/Stop/PreCompact 等 emit
│   │   ├── Agent$Builder.java         # Builder.hookEngine(...)
│   │   ├── SessionRuntime.java        # 加 pendingReminders、hookEngine 字段
│   │   └── ...
│   ├── command/
│   │   ├── BuiltinCommands.java       # 加 /hooks 命令注册
│   │   └── CommandUi.java             # 接口加 hookSources/hookRules
│   ├── tui/
│   │   ├── MewCodeModel.java                # Params 加 hookEngine、持有；init 触发 SessionStart
│   │   ├── AgentEvent 队列.java            # 不直接动，由 MewCodeModel 触发
│   │   ├── Commands.java              # /clear / /resume 触发 SessionEnd + SessionStart/Resume
│   │   └── HooksCommand.java          # 新增：/hooks handler、Model 的 hook 查询方法
│   └── MewCode.java                      # 加 HookLoader.load(root) 与 wiring
└── src/test/java/dev/mewcode/
    ├── permission/
    │   ├── MatchersTest.java
    │   └── PermissionRuleTest.java
    └── hook/
        ├── HookLoaderTest.java
        ├── HookEngineTest.java
        └── HookExecutorTest.java
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 匹配前缀语法 | `=` 精确、`!` 反向、`~` 正则、无前缀=glob | 单字符前缀让既有 `Bash(git *)` 这种写法继续 work；用户写新形式时直观（=foo 一眼就是精确） |
| 反向类型嵌套 | `!=value`、`!~regex`、`!glob` 都合法 | 反向是一元运算，对内层 matcher 取反；嵌套写法直接，不需要 `not()` 函数语法 |
| Matcher 用 sealed interface + record | 而非 enum + switch | record 自动实现 equals/hashCode/toString；sealed 让 switch 模式匹配能穷尽四种类型；新增类型时编译器强制处理 |
| Hook 包独立 | `com.mewcode.hook` | 与 `com.mewcode.permission` 平级；hook 依赖 permission.Matcher，但 permission 不依赖 hook，无循环 |
| Event 用 enum + wireName | 而非 String 常量 | enum 享受类型安全与穷尽 switch；YAML 写的字符串通过 `Event.parse` 转换；JSON 序列化用 `wireName()` 输出 "PreToolUse" 这种 |
| Payload 内部 Map\<String, Object\> | 而非具体 record | 11 个事件字段差异大；Map + getByPath 灵活；JSON 序列化时按 key 字典序排序便于脚本 grep |
| Reminder 注入用 SessionRuntime 而非 HookEngine 状态 | `runtime.pendingReminders` | 与现有 plan reminder 同一注入点；下一轮自动清空；不污染 HookEngine |
| PreToolUse 拦截位置 | 权限 check 之前 | 让用户能用 hook 早于权限引擎做安全策略；hook 拦截后甚至不调权限 check |
| shell 用 `sh -c` | 而非 `ProcessBuilder` 直接 args 数组 | 用户写 hook 时常用 `\|`、`>` 这种 shell 语法；与 ch08 bash 工具一致；用 `ProcessBuilder("sh", "-c", command).redirectErrorStream(false)` 包装 |
| HTTP 默认 POST + JSON body | 而非 GET | hook 多是「事件通知」语义，POST 更合理；用户需要 GET 时显式声明 method |
| HTTP body 用 `${field}` 占位符 | 不开放函数 | 简易模板已经够覆盖字段插值；开放函数容易出注入风险；用正则替换或 `StringSubstitutor` 实现 |
| subagent 占位仅打日志 | 不报错也不阻塞 | spec 明确本期不实现，但配置应能加载——避免用户写早期配置后续章节直接生效 |
| only_once 用内存 HashSet | 不写盘 | spec N5 明确本期不持久化；HashSet 在 runtime 里，与 ActiveSkills 同生命周期 |
| 事件分派同步串行 | 多 hook 不并发 | 拦截语义需要顺序；同步 stderr 日志顺序也确定；async hook 单独起 virtual thread 但 dispatch 不等 |
| 拦截类 sync timeout 不全局上限 | 单条 hook timeout 累加 | 用户配的 timeout 自己负责；全局上限会引入复杂语义 |
| `/hooks` 命令风格 | 与 `/skill` 对齐 | 已加载条目按事件分组、每条一行；末尾标加载来源 |
| 加载来源记录 | `HookEngine.sources : List<String>` | YAML 文件路径列表，`/hooks` 命令展示 |
| async hook 用 virtual thread | 而非平台线程池 | Java 21 virtual thread 启停开销低、适合"起一发就忘"语义；与项目其它地方一致 |
| HttpClient 选 `java.net.http.HttpClient` | 而非 OkHttp 等三方 | JDK 内置、零额外依赖；天然支持 `Duration` timeout 与 sendAsync |
| JSON 序列化 | 与项目其它处共用同一 ObjectMapper（Jackson）或简易手写 | 保持依赖一致；key 字典序通过 `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS` 或 TreeMap 实现 |