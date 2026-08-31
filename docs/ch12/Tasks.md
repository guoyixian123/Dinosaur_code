# Hook 生命周期挂钩系统 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/mewcode/permission/Matcher.java` | sealed Matcher 接口 |
| 新建 | `src/main/java/com/mewcode/permission/ExactMatcher.java` | record 实现 |
| 新建 | `src/main/java/com/mewcode/permission/GlobMatcher.java` | record 实现 |
| 新建 | `src/main/java/com/mewcode/permission/RegexMatcher.java` | record 实现 |
| 新建 | `src/main/java/com/mewcode/permission/NotMatcher.java` | record 实现 |
| 新建 | `src/main/java/com/mewcode/permission/Matchers.java` | `compile` 工厂 + `MatcherCompileException` |
| 新建 | `src/test/java/dev/mewcode/permission/MatchersTest.java` | 四种 type × 边界条件覆盖 |
| 修改 | `src/main/java/com/mewcode/permission/PermissionRule.java` | `parse` 识别前缀、record 持有 Matcher 替代 String pattern、`match` 改造 |
| 修改 | `src/test/java/dev/mewcode/permission/PermissionRuleTest.java` | 扩展用例覆盖新语法 |
| 修改 | `src/main/java/com/mewcode/permission/SettingsLoader.java` | `toRuleSet` 改造：失败 rule 走 stderr |
| 修改 | `src/test/java/dev/mewcode/permission/SettingsLoaderTest.java` | 验证 stderr 报错与跳过逻辑 |
| 新建 | `src/main/java/com/mewcode/hook/package-info.java` | 包注释 |
| 新建 | `src/main/java/com/mewcode/hook/Event.java` | 11 个枚举 + `isBlocking` + `parse` |
| 新建 | `src/main/java/com/mewcode/hook/CombineMode.java` | enum |
| 新建 | `src/main/java/com/mewcode/hook/AtomCondition.java` | record |
| 新建 | `src/main/java/com/mewcode/hook/Condition.java` | record |
| 新建 | `src/main/java/com/mewcode/hook/Action.java` | sealed Action + 4 个嵌套 record |
| 新建 | `src/main/java/com/mewcode/hook/HookRule.java` | record |
| 新建 | `src/main/java/com/mewcode/hook/Payload.java` | 字典序 JSON + `getByPath` |
| 新建 | `src/main/java/com/mewcode/hook/ConditionEvaluator.java` | `evaluate` / `getByPath` |
| 新建 | `src/main/java/com/mewcode/hook/HookLoader.java` | YAML 解析、双层合并、字段校验 |
| 新建 | `src/test/java/dev/mewcode/hook/HookLoaderTest.java` | 字段校验、加载错误、合并测试 |
| 新建 | `src/main/java/com/mewcode/hook/HookEngine.java` | HookEngine + dispatch 主流程 + only_once |
| 新建 | `src/main/java/com/mewcode/hook/DispatchResult.java` | record |
| 新建 | `src/test/java/dev/mewcode/hook/HookEngineTest.java` | 各事件 dispatch、拦截、reminder、once 覆盖 |
| 新建 | `src/main/java/com/mewcode/hook/HookExecutor.java` | 四类 action 执行器 |
| 新建 | `src/main/java/com/mewcode/hook/ExecutionResult.java` | record |
| 新建 | `src/test/java/dev/mewcode/hook/HookExecutorTest.java` | shell exit2、http block、prompt、subagent stub |
| 修改 | `src/main/java/com/mewcode/agent/SessionRuntime.java` | 加 `pendingReminders` + `hookEngine` 字段 + `resetForNewSession` 清空 |
| 修改 | `src/test/java/dev/mewcode/agent/SessionRuntimeTest.java` | 验证 `pendingReminders` 行为 |
| 修改 | `src/main/java/com/mewcode/agent/Agent.java` | `Builder.hookEngine`、11 个 emit 点（部分由 tui 触发，agent 负责 PreUserMessage/PreToolUse/PostToolUse/PreCompact/PostCompact/Stop/Notification） |
| 修改 | `src/test/java/dev/mewcode/agent/AgentTest.java` | 拦截路径测试 |
| 新建 | `src/main/java/com/mewcode/tui/HooksCommand.java` | `/hooks` 命令 handler、MewCodeModel 的 hook 查询方法 |
| 修改 | `src/main/java/com/mewcode/tui/MewCodeModel.java` | `Params/Builder` 加 hookEngine、持有；`init` 触发 SessionStart |
| 修改 | `src/main/java/com/mewcode/tui/AgentEvent 队列.java` | `submit()` 内 UserPromptSubmit dispatch + 拦截集成 |
| 修改 | `src/main/java/com/mewcode/tui/Commands.java` | `/clear`、`/resume` 触发 SessionEnd + SessionStart/Resume |
| 修改 | `src/main/java/com/mewcode/command/BuiltinCommands.java` | 加 `/hooks` 内置命令 |
| 修改 | `src/main/java/com/mewcode/command/CommandUi.java` | UI 接口加 hook 查询方法 |
| 修改 | `src/main/java/com/mewcode/MewCode.java` | 加 `HookLoader.load(root)` 与 wiring；SessionEnd 兜底 |
| 修改 | `build.gradle.kts` | 如尚未引入 snakeyaml 与 jackson-databind，需补齐（多半已有） |

## T1: 实现 permission.Matcher 接口与四种类型

**文件：** `src/main/java/com/mewcode/permission/Matcher.java`、`ExactMatcher.java`、`GlobMatcher.java`、`RegexMatcher.java`、`NotMatcher.java`、`Matchers.java`
**依赖：** 无
**步骤：**
1. 新建 `Matcher.java`，声明 `public sealed interface Matcher permits ExactMatcher, GlobMatcher, RegexMatcher, NotMatcher { boolean match(String s); String describe(); }`
2. 实现 4 个 record：
   - `ExactMatcher(String value)`：`match` 返回 `s.equals(value)`
   - `GlobMatcher(String pattern, boolean command)`：`command` 为 true 调 `GlobMatch.matchCommand`，否则 `GlobMatch.matchPath`；`describe` 返回 `pattern`
   - `RegexMatcher(Pattern compiled, String source)`：`match` 返回 `compiled.matcher(s).find()`
   - `NotMatcher(Matcher inner)`：`match` 返回 `!inner.match(s)`
3. 实现工厂 `Matchers.compile(String pattern, boolean command)`：
   - 空串 → 抛 `MatcherCompileException("empty matcher pattern")`
   - `=value` → `new ExactMatcher(value)`
   - `~regex` → `Pattern.compile(regex)`，`PatternSyntaxException` 包装抛出
   - `!inner` → 递归 `compile(inner, command)` 包装成 `NotMatcher`
   - 其它 → `new GlobMatcher(pattern, command)`
4. `GlobMatch` 工具类如已存在则直接复用；不存在则把现有 wildcard / matchPath 逻辑迁入 `GlobMatch`
5. 写 Javadoc 解释每个 Matcher 类型的语义

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T2: matcher 单元测试

**文件：** `src/test/java/dev/mewcode/permission/MatchersTest.java`
**依赖：** T1
**步骤：**
1. JUnit 5；`@ParameterizedTest` + `@MethodSource` 覆盖 4 种类型各自的命中/不命中用例
2. `=git status` 命中 `git status`、不命中 `git status -s`
3. `~^npm (install|test)$` 命中 `npm install`、不命中 `npm run dev`
4. `!=foo` 不命中 `foo`、命中 `bar`
5. `!~^rm` 命中 `ls -lh`、不命中 `rm -rf .`
6. `!git *` 命中 `npm install`、不命中 `git status`（嵌套 not + glob）
7. 编译失败：`~[invalid` 应抛 `MatcherCompileException`
8. 空串：`""` 应抛异常
9. 每个用例附 `assertAll` 描述

**验证：** `./gradlew test -Dtest=MatchersTest` 通过

## T3: 升级 permission.PermissionRule 与 parse

**文件：** `src/main/java/com/mewcode/permission/PermissionRule.java`
**依赖：** T1
**步骤：**
1. `PermissionRule` 改为 record：`record PermissionRule(String tool, Matcher matcher, boolean allow, String raw)`
2. 静态 `parse(String s)` 签名改：抛 `RuleParseException`——返回受检异常让 `SettingsLoader.toRuleSet` 写日志
3. parse 内部：剥出 tool 与 pattern 后调 `Matchers.compile(pattern, "Bash".equals(tool))`；空 pattern 仍按 null matcher 表示"全匹配"
4. 改造 `matches(String target)` 实例方法：`matcher == null` 返回 true（全匹配），否则 `matcher.match(target)`
5. `RuleSet` 内部对 `PermissionRule` 集合的处理保持原行为，仅调用点改为 `matches`
6. 旧的 `escapeGlob` 等辅助方法保留不变（供 ch08 自动生成的精确规则使用）
7. Javadoc 更新说明四种语法

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T4: 升级 SettingsLoader 错误日志

**文件：** `src/main/java/com/mewcode/permission/SettingsLoader.java`
**依赖：** T3
**步骤：**
1. `toRuleSet` 改造：`PermissionRule.parse` 抛 `RuleParseException` 时调
   `System.err.printf("rule %s parse failed: %s%n", quoted(str), ex.getMessage());`
2. 失败的 rule 不进入 RuleSet，其它 rule 不受影响——加注释说明
3. 复用项目里已有的日志辅助；若无则直接 `System.err`

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T5: 扩展 PermissionRuleTest 与 SettingsLoaderTest

**文件：** `src/test/java/dev/mewcode/permission/PermissionRuleTest.java`、`SettingsLoaderTest.java`
**依赖：** T3、T4
**步骤：**
1. PermissionRuleTest：补充用例
   - `Bash(=git status)` 精确匹配
   - `Bash(~^npm.*)` 正则匹配
   - `Bash(!~^rm)` 反向正则
   - `Write(**/*.java)` glob 沿用（确认向后兼容）
2. SettingsLoaderTest：构造一份含非法 rule 的 yaml 临时文件，验证 `toRuleSet` 返回的 RuleSet 不含该 rule（用 `System.setErr(new PrintStream(buf))` 捕获 stderr 验证含 `parse failed`，再断言 allow/deny 列表长度）
3. 旧的 `GlobMatchTest`（如存在）保持调用底层函数测试，或改造成调用 Matcher 形式

**验证：** `./gradlew test -Dtest=PermissionRuleTest,SettingsLoaderTest` 全部通过

## T6: hook 包基础数据结构

**文件：** `src/main/java/com/mewcode/hook/package-info.java`、`Event.java`、`CombineMode.java`、`AtomCondition.java`、`Condition.java`、`Action.java`、`HookRule.java`、`Payload.java`
**依赖：** 无
**步骤：**
1. `package-info.java`：包级 Javadoc，描述本包职责
2. `Event.java`：
   - `public enum Event { SESSION_START, SESSION_END, SESSION_RESUME, USER_PROMPT_SUBMIT, STOP, PRE_USER_MESSAGE, PRE_TOOL_USE, POST_TOOL_USE, PRE_COMPACT, POST_COMPACT, NOTIFICATION; }`
   - `boolean isBlocking()` 返回 `this == PRE_TOOL_USE || this == USER_PROMPT_SUBMIT`
   - 静态 `Optional<Event> parse(String s)` 用字符串到枚举的映射表（含 "SessionStart" 等驼峰写法）
   - 实例 `String wireName()` 返回驼峰名（"SessionStart" 等），供 JSON 序列化与 stderr 日志
3. `CombineMode.java`：`enum CombineMode { ALL_OF, ANY_OF }`
4. `AtomCondition.java`：`record AtomCondition(String field, Matcher matcher)`
5. `Condition.java`：`record Condition(CombineMode mode, List<AtomCondition> atoms)`
6. `Action.java`：`sealed interface Action permits Action.Shell, Action.Prompt, Action.Http, Action.Subagent` + 4 个嵌套 record
7. `HookRule.java`：`record HookRule(String name, Event event, Condition condition, Action action, boolean onlyOnce, boolean async, Duration timeout, String source)`
8. `Payload.java`：内部 `Map<String, Object> data`；构造器接受 `Map`；`String getByPath(String path)` 与 `String toSortedJson()`

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T7: hook.ConditionEvaluator 字段路径求值

**文件：** `src/main/java/com/mewcode/hook/ConditionEvaluator.java`
**依赖：** T6、T1
**步骤：**
1. `static String getByPath(Payload p, String path)`：按 `.` 分隔；递归从 Map 取值；中途遇 null/非 Map 返回空串
2. 字段值非字符串时：boolean/数字转字符串（`String.valueOf`）；嵌套对象转 JSON（用项目内 ObjectMapper 或 Payload 自带的 sorted JSON 序列化器）
3. `static boolean evaluate(Condition c, Payload p)`：
   - `c == null` → true
   - 遍历 `c.atoms()`，每条用 `getByPath` + `atom.matcher().match(...)`
   - `ALL_OF` 要求全部 true、`ANY_OF` 要求至少一个 true

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T8: hook.HookLoader YAML 解析

**文件：** `src/main/java/com/mewcode/hook/HookLoader.java`
**依赖：** T6、T7、T1
**步骤：**
1. 定义 YAML 中间结构：直接用 SnakeYAML Engine 解析成 `Map<String, Object>`，再手动绑定到 `HookRule`
2. `static HookEngine load(Path projectRoot)` 主入口：
   - 计算两个候选路径：`projectRoot.resolve(".mewcode/hooks.yaml")`、`Path.of(System.getProperty("user.home"), ".mewcode/hooks.yaml")`
   - 文件不存在跳过；存在但解析失败 stderr 输出后跳过
   - 对每个 hook 对象调 `compileRule(source, idx, rawMap)`，返回 `HookRule` 或抛 `HookCompileException`
   - 累积成功的 rule、stderr 输出失败的 rule
   - 跨文件 name 冲突时跳过后者，stderr 提示冲突
3. `compileRule` 内做字段校验：
   - `name` 非空字符串
   - `event` 枚举（`Event.parse`）
   - `action.type` ∈ {shell, prompt, http, subagent}，对应子字段必填（`shell.command`、`prompt.text`、`http.url`、`subagent.agent_name` + `subagent.prompt`）
   - `if` 顶层 `all_of` / `any_of` 互斥
   - 每个 atom 的 `match.type` ∈ {exact, glob, regex, not} 且 `value`/`inner` 字段完整
   - `async` + `event.isBlocking()` → 抛错跳过，stderr 含 `async not allowed for blocking events`
   - `timeout` 字符串解析为 `Duration`：支持 `30s`、`500ms`、`2m` 等；缺省 30s
4. Matcher 编译用 `Matchers.compile`；hook 上下文都是 payload 字段值，统一传 `command=false`（让 glob 走 `matchPath` 语义：段内 `*` 不跨 `/`）
   - **决策修正**：hook 的 matcher 在初始化时统一传 `command=false`；这对 `tool_input.command` 这种字段是有点限制——但用户可以改用 regex 表达 shell 字符串匹配，文档需说清
5. 返回的 `HookEngine` 由 `new HookEngine(rules, sources, new HookExecutor())` 构造（执行器实例延后到 T11 实现完整）

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T9: hook.HookLoader 测试

**文件：** `src/test/java/dev/mewcode/hook/HookLoaderTest.java`
**依赖：** T8
**步骤：**
1. 用 `@TempDir` 场景：写一份合法 hooks.yaml（含 2 条 hook），`HookLoader.load` 返回的 HookEngine 含 2 条 rule
2. 字段缺失：name 空、event 不存在、action.type 无效 → 跳过该条但其它通过
3. all_of + any_of 同时存在 → 跳过该条
4. async + PreToolUse → 跳过该条且 stderr 含 `async not allowed for blocking events`
5. 跨文件同名冲突 → 项目级保留、用户级跳过
6. matcher 编译失败（非法正则） → 跳过该条
7. 用 `tapSystemErr(...)`（System.Lambda 或手写 `setErr` 包装）验证 stderr 输出

**验证：** `./gradlew test -Dtest=HookLoaderTest` 通过

## T10: hook.HookEngine 与 dispatch 主流程

**文件：** `src/main/java/com/mewcode/hook/HookEngine.java`、`DispatchResult.java`
**依赖：** T6、T7
**步骤：**
1. `DispatchResult` record：`(boolean blocked, String reason, String blockingHookName, List<String> injectedPrompts)`；静态 `empty()`
2. `HookEngine` 字段：`rules`、`sources`、`ReentrantLock`、`HashSet<String> onceFired`、`HookExecutor executor`
3. `HookEngine(List<HookRule> rules, List<String> sources, HookExecutor executor)` 构造器
4. `DispatchResult dispatch(Event event, Payload payload)`：
   - 遍历 rules，跳过非本事件
   - 加锁查 `onceFired`，命中跳过
   - `ConditionEvaluator.evaluate`；不通过跳过
   - 命中后：
     - `async=true` 起 virtual thread (`Thread.startVirtualThread(() -> executor.run(...))`)，立即继续（不等结果、不进入 injectedPrompts 与 blocked 判定）
     - 同步：调 `executor.run(rule, payload, event.isBlocking(), rule.timeout())`
   - 同步结果处理：
     - `result.error()` 非 null → stderr 日志 `[hook <name>] <event> failed: <reason>`，继续下一个 rule（不拦截）
     - `result.prompt()` 非空 → 加入 injectedPrompts
     - `result.blocked() && event.isBlocking()` → 设置 DispatchResult.blocked + reason + blockingHookName，break 退出循环
   - 命中且执行无 fatal err 的 rule，若 `onlyOnce` → 加入 `onceFired`
5. `resetForNewSession()`：加锁清空 `onceFired`
6. `sources() / rules()` getter

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T11: hook.HookExecutor 四类动作执行

**文件：** `src/main/java/com/mewcode/hook/HookExecutor.java`、`ExecutionResult.java`
**依赖：** T6
**步骤：**
1. `ExecutionResult` record：`(boolean blocked, String reason, String prompt, Throwable error)`；静态 `empty()`
2. `HookExecutor` 字段：`HttpClient httpClient`（`HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()`）
3. `ExecutionResult run(HookRule rule, Payload payload, boolean blocking, Duration deadline)` 用 `switch (rule.action())` 模式匹配分发到下面四个内部方法
4. `runShell(Action.Shell sa, Payload payload, boolean blocking, Duration timeout)`：
   - `ProcessBuilder pb = new ProcessBuilder("sh", "-c", sa.command())`
   - `pb.redirectErrorStream(false)`，从 stdin 写入 `payload.toSortedJson()` 单行
   - 启动后用 virtual thread 读 stdout / stderr；`process.waitFor(timeout)`；超时 → `process.destroyForcibly()`、返回 `error=TimeoutException`
   - `blocking && exitCode == 2` → `blocked=true、reason=合并 stderr/stdout 去尾`
   - `exitCode == 0` → 空 ExecutionResult
   - 其它非 0 exit → `error=new RuntimeException("exit " + code + ": " + stderr)`
5. `runPrompt(Action.Prompt pa)` → `new ExecutionResult(false, null, pa.text(), null)`
6. `runHttp(Action.Http ha, Payload payload, boolean blocking, Duration timeout)`：
   - method 默认 POST
   - body：缺省时 `payload.toSortedJson()`；否则用 `${field}` 占位符替换 `payload.getByPath(field)`
   - 构造 `HttpRequest`，加 headers，`.timeout(timeout)`
   - `httpClient.send(req, BodyHandlers.ofString())`
   - status 2xx 且 body 解析成 `{"decision":"block","reason":"..."}` → `blocked=true`
   - 网络错/超时/JSON 解析失败 → `error=ex`
7. `runSubagent(Action.Subagent sa)`：仅 `System.err.printf("[hook subagent] not yet implemented, skipped: %s%n", sa.agentName())`，返回 `ExecutionResult.empty()`
8. JSON 解析复用项目里已有的 ObjectMapper；模板替换写一个简单 `String renderTemplate(String tpl, Payload p)` 用正则 `\\$\\{([^}]+)\\}` 匹配并调 `getByPath`

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T12: executor 单元测试

**文件：** `src/test/java/dev/mewcode/hook/HookExecutorTest.java`
**依赖：** T11
**步骤：**
1. shell exit 2 with stderr → `blocked=true` + reason 含 stderr
2. shell exit 0 → 放行不报错
3. shell exit 1 → `error` 非 null 不拦截
4. shell stdin JSON 解析：脚本读 stdin 后 `echo` 出来（用 cat），验证 key 字典序
5. shell timeout：`sleep 2` + timeout 100ms → `error` 含 `TimeoutException` 或类似
6. prompt → `prompt` 字段非空
7. http with `com.sun.net.httpserver.HttpServer` 起本地 server，返回 `{"decision":"block","reason":"x"}` → `blocked=true`
8. http with 5xx → `error` 非 null
9. http 模板 body 含 `${event}` → server 收到正确字段
10. subagent → 用 `tapSystemErr` 验证 stderr 含占位文本

**验证：** `./gradlew test -Dtest=HookExecutorTest` 通过

## T13: hook.HookEngine 测试

**文件：** `src/test/java/dev/mewcode/hook/HookEngineTest.java`
**依赖：** T10、T11
**步骤：**
1. 多 rule 同事件按声明序执行
2. 拦截类事件下首个 `blocked=true` 的 rule 中断后续
3. 非拦截类事件下 `blocked` 字段不传递（fake exit code 2 但 `isBlocking=false` 也不 set `blocked`）
4. prompt rule 的 prompt 累加到 `injectedPrompts`
5. `onlyOnce` 在首次执行后被加入 `onceFired`，第二次 dispatch 跳过
6. `resetForNewSession` 后 `onlyOnce` 重置
7. async rule 不进入 `blocked` 判定（用 `CountDownLatch` 验证 virtual thread 已起）

**验证：** `./gradlew test -Dtest=HookEngineTest` 通过

## T14: agent SessionRuntime 扩展

**文件：** `src/main/java/com/mewcode/agent/SessionRuntime.java`、`src/test/java/dev/mewcode/agent/SessionRuntimeTest.java`
**依赖：** T6、T10
**步骤：**
1. `SessionRuntime` 加字段：`final List<String> pendingReminders`（用 `Collections.synchronizedList(new ArrayList<>())` 或加锁包装）、`HookEngine hookEngine`
2. 构造器初始化空 list
3. `resetForNewSession()` 清空 `pendingReminders`、若 `hookEngine != null` 调 `hookEngine.resetForNewSession()`
4. 新增 `appendReminders(List<String> prompts)` 加锁追加
5. 新增 `List<String> takeReminders()` 加锁取出并清空
6. 测试覆盖：`appendReminders` + `takeReminders` 单线程行为；`resetForNewSession` 清空

**验证：** `./gradlew test -Dtest=SessionRuntimeTest` 通过

## T15: agent.Builder.hookEngine 与 emit 框架

**文件：** `src/main/java/com/mewcode/agent/Agent.java`、`Agent.Builder` 内嵌类
**依赖：** T14
**步骤：**
1. `Agent.Builder` 加方法 `Builder hookEngine(HookEngine e)`，赋值到 `Builder.hookEngine`
2. `Agent` 字段加 `HookEngine hookEngine`，构造时从 Builder 拷贝；同时把 hookEngine 写入 `SessionRuntime`
3. 私有方法 `DispatchResult dispatchHook(Event event, Payload payload)`：
   - `hookEngine == null` → 返回 `DispatchResult.empty()`
   - 调 `hookEngine.dispatch(event, payload)`
   - 把 `injectedPrompts` 调 `runtime.appendReminders`
   - 返回结果（保留 blocked + reason 供 PreToolUse 用）
4. 私有方法 `String buildReminder(PermissionMode mode, int iter)`：
   - 原 planReminder + `String.join("\n\n", runtime.takeReminders())`

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T16: agent 各事件 emit 接入

**文件：** `src/main/java/com/mewcode/agent/Agent.java`
**依赖：** T15
**步骤：**
1. `run` 开始处准备 Stop emit 入口——实际 Stop 在 `Done` 事件 publish 前调用
2. 每轮 iter 顶部、`compactManager.manageContext` 之前调 `dispatchHook(Event.PRE_COMPACT, payload(Map.of("trigger", "auto")))`；`manageContext` 返回后 emit `POST_COMPACT` 带 before/after tokens
3. `emergencyCompactAndDecide`：同样 PRE_COMPACT/POST_COMPACT，`trigger="emergency"`
4. `streamOnce` 调 `provider.stream` 之前 emit `PRE_USER_MESSAGE`，payload 含 conversation 末尾 user 消息
5. 把 reminder 串改造：取 `buildReminder(mode, iter)` 替代原裸的 `Prompts.planReminder(full)`
6. `executeBatched` 改造：
   - 单工具循环开始处 emit `PRE_TOOL_USE`，payload 含 `tool_name`、`tool_input`；`blocked=true` 时构造 `hookBlockedResult`、publish `PhaseStart`/`PhaseEnd`（isError=true），continue
   - tool 拿到 result 后、publish `PhaseEnd` 之前 emit `POST_TOOL_USE`，payload 含 `tool_name`、`tool_input`、`tool_result`、`is_error`
7. publish `Done` 之前调 `STOP`，payload(`Map.of("iter", iter)`)
8. publish `Approval` 之前调 `NOTIFICATION`，payload(`Map.of("kind", "approval", "detail", toolName)`)
9. publish `Failed` 之前调 `NOTIFICATION`，payload(`Map.of("kind", "stream_error", "detail", err.toString())`)
10. 拦截结果整合：私有 `ToolResult hookBlockedResult(String callId, String hookName, String reason)`：content=`[hook <name>] <reason>`、isError=true

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T17: AgentTest 拦截路径与 emit 覆盖

**文件：** `src/test/java/dev/mewcode/agent/AgentTest.java`、`SessionRuntimeTest.java`
**依赖：** T16
**步骤：**
1. 构造一个 fake `Provider` + 注入真实 `HookEngine`（合成 rules 注入）
2. 测试：PreToolUse 拦截时工具结果是 `hookBlockedResult` 形式、`PhaseStart`/`PhaseEnd` 仍 publish
3. 测试：PreUserMessage 注入的 prompt 在下一次 `streamOnce` 的 reminder 串中可见
4. 测试：Stop 事件在 `Done` 前一刻被 emit
5. 由于 HookEngine 不是接口，直接 new 真实 HookEngine 注入合成 rules（更简单）；或写一个 `TestHookEngine extends HookEngine` 子类覆盖 dispatch

**验证：** `./gradlew test -Dtest=AgentTest -Dtest.method=*Hook*` 通过

## T18: tui MewCodeModel 持有 HookEngine

**文件：** `src/main/java/com/mewcode/tui/MewCodeModel.java`
**依赖：** T15
**步骤：**
1. `MewCodeModel.Params` 加 `HookEngine hookEngine`
2. `MewCodeModel` 加字段 `HookEngine hookEngine`
3. 构造器内：
   - 把 `params.hookEngine` 赋给 `this.hookEngine` 与 `runtime.hookEngine`
   - 构造 agent 时加 `.hookEngine(params.hookEngine)`
4. `init()` 末尾调 `dispatchSessionStart()`

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T19: tui UserPromptSubmit 拦截集成

**文件：** `src/main/java/com/mewcode/tui/AgentEvent 队列.java` 或 `MewCodeModel` 的 submit 方法所在文件
**依赖：** T18
**步骤：**
1. `submit()` 重写：
   - 现有的 trim 与 slash 分发保留
   - 非 slash 路径进入 hook 拦截判定
   - 构造 payload：`new Payload(Map.of("event", "UserPromptSubmit", "session_id", sessionId, "cwd", cwd, "mode", mode.name().toLowerCase(), "prompt", text))`
   - 调 `hookEngine.dispatch(Event.USER_PROMPT_SUBMIT, payload)`
   - `blocked=true`：在 scrollback 追加 `errorLabel(String.format("[hook %s] %s", result.blockingHookName(), result.reason()))`，不消费 textBox
   - 否则：把 `injectedPrompts` 经 `runtime.appendReminders`；`conversation.addUser(text)`；`beginTurn`
2. 提供辅助方法 `Payload basePayload(Event event, Map<String, Object> extras)` 构造通用字段
3. UI 更新统一通过 `program.send(new AgentEventMessage(...)` 切回 GUI 线程

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T20: tui SessionStart / End / Resume

**文件：** `src/main/java/com/mewcode/tui/MewCodeModel.java`、`Commands.java`、`AgentEvent 队列.java`
**依赖：** T18、T19
**步骤：**
1. 新增 `void dispatchSessionStart()`：构造 payload + 调 `HookEngine.dispatch` + `injectedPrompts` 写入 runtime
2. 新增 `void dispatchSessionEnd()`：仅同步调 dispatch
3. 新增 `void dispatchSessionResume()`：同 SessionStart 流程，event 改为 `SESSION_RESUME`
4. `init()` 末尾调 `dispatchSessionStart`
5. `/clear` handler 内：先 `dispatchSessionEnd`，再 `runtime.resetForNewSession`，最后 `dispatchSessionStart`
6. `/resume` handler 选中会话恢复完毕后：先 `dispatchSessionEnd`（旧），切到新会话后 `dispatchSessionResume`
7. `handleExit` 内：`dispatchSessionEnd` 后再退出
8. `MewCode` 中 `app.run()` 返回后由 main 调一次 `hookEngine.dispatch(Event.SESSION_END, ...)` 兜底（ctrl+c 一退出也 emit）；tui 内的 `/clear`、`/resume` 自己控制

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T21: /hooks 命令

**文件：** `src/main/java/com/mewcode/tui/HooksCommand.java`、`command/BuiltinCommands.java`、`command/CommandUi.java`
**依赖：** T6、T10、T18
**步骤：**
1. `CommandUi` 接口加方法 `List<String> hookSources()`、`List<HookRule> hookRules()`
2. `MewCodeModel` 实现这两个方法（读 `this.hookEngine` 字段）
3. 新增 `HooksCommand`，实现 `Command` 接口或注册成 lambda：
   - 取 rules 与 sources
   - 空时 `view.append("No hooks loaded.")`
   - 否则按 event 分组（保留 yaml 声明顺序）、每条一行 `  <name>  <event>  <action.type>  [once] [async]`
   - 末尾 `Loaded from: file1, file2`
4. `BuiltinCommands.register` 加 `/hooks` 命令，KindLocal，描述「列出已加载的 hook 列表」

**验证：** `./gradlew -q -DskipTests compile` 编译通过

## T22: Main wiring

**文件：** `src/main/java/com/mewcode/MewCode.java`
**依赖：** T8、T18
**步骤：**
1. 在 `PermissionEngine.create(root)` 之后调 `HookEngine hookEngine = HookLoader.load(root)`
2. `MewCodeModel.Params` 设置 `.hookEngine(hookEngine)`
3. `app.run()` 返回后调
   ```java
   if (hookEngine != null) {
       hookEngine.dispatch(Event.SESSION_END, basePayload(...));
   }
   ```
   兜底 SessionEnd
4. import 加 `com.mewcode.hook.*`

**验证：** `./gradlew shadowJar` 编译通过、`java -jar build/libs/mewcode.jar` 能启动

## T23: 整体编译与测试

**文件：** —
**依赖：** T1-T22 全部
**步骤：**
1. `./gradlew shadowJar` 通过
2. `./gradlew test` 通过——hooks 相关测试 + 既有测试都得过
3. `./gradlew compileJava` (代码风格由 IDE 保证)（若启用）通过

**验证：** 上述命令本地通过

## T24: 修复回归

**文件：** 根据测试输出决定
**依赖：** T23
**步骤：**
1. 修复 ch08 / ch11 等老测试因 Matcher 改造而失败的用例
2. 修复 ch10 / ch11 测试因 `/hooks` 命令加入而影响排序或数量的用例
3. 重新跑全套测试

**验证：** `./gradlew test` 通过

## T25: tmux 端到端实跑（验收 AC17 与 checklist 端到端场景）

**文件：** `.mewcode/hooks.yaml` 临时测试配置
**依赖：** T23、T24
**步骤：**
1. 写测试 hooks.yaml：包含 AC4-AC15 各典型场景的 hook
2. tmux 新建 session 启动 mewcode（`java -jar build/libs/mewcode.jar` 或 `./gradlew -q exec:java`）
3. 依次触发：write_file 工具调用、含 delete 关键字的用户输入、git 命令、Stop 事件
4. 观察 stderr 日志、tool_result 内容、reminder 注入是否符合预期
5. 全程无异常堆栈、不卡顿

**验证：** 见 checklist.md

## 执行顺序

```
T1 → T2 → T3 → T4 → T5            # permission Matcher 扩展
T6 → T7 → T8 → T9                 # hook 基础结构 + Loader
T10 → T13                         # HookEngine
T11 → T12                         # HookExecutor（与 HookEngine 并行）
T14 → T15 → T16 → T17             # agent 接入
T18 → T19 → T20                   # tui 接入
T21                               # /hooks 命令
T22                               # Main wiring
T23 → T24                         # 整体编译测试
T25                               # tmux 实跑验收
```

并行机会：
- T11/T12 与 T10/T13 互不依赖,可并行
- T11 与 T8 在 T6 完成后可并行
- T17 必须在 T16 之后
- T19 之前 T18 必须先完成