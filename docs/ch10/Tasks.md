# slash命令体系 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/dinocode/command/Kind.java` | Kind 枚举 |
| 新建 | `src/main/java/com/dinocode/command/Command.java` | Command record + Handler 函数接口 |
| 新建 | `src/main/java/com/dinocode/command/CommandRegistry.java` | register/lookup/visible/prefixMatch + 冲突检测 |
| 新建 | `src/test/java/dev/dinocode/command/CommandRegistryTest.java` | 注册、冲突抛异常、前缀匹配、visible 排序的单测 |
| 新建 | `src/main/java/com/dinocode/command/Dispatch.java` | parse(input) — 解析 `/<name>` 形态 |
| 新建 | `src/test/java/dev/dinocode/command/DispatchTest.java` | parse 各种输入的单测 |
| 新建 | `src/main/java/com/dinocode/command/Ui.java` | Ui 接口 + NopUi 测试桩 |
| 新建 | `src/main/java/com/dinocode/command/BuiltinLocal.java` | 5 条纯本地命令(/help /status /memory /permission /session) |
| 新建 | `src/main/java/com/dinocode/command/BuiltinUi.java` | 5 条影响界面命令(/exit /plan /compact /resume /clear) |
| 新建 | `src/main/java/com/dinocode/command/BuiltinPrompt.java` | 2 条提示词命令(/do /review) + REVIEW_DIRECTIVE 常量 |
| 新建 | `src/main/java/com/dinocode/command/Builtins.java` | registerAll(reg) 把 12 条命令一次性注入 |
| 新建 | `src/test/java/dev/dinocode/command/BuiltinsTest.java` | 12 条命令注册成功、NopUi 调用全部 handler 不抛异常 |
| 改造 | `src/main/java/com/dinocode/tui/Commands.java` | 删旧 builtinCommands + handle* 方法; 新增 dinocodeModel 实现 Ui 接口的全部方法 + dispatchSlash 入口 |
| 新建 | `src/main/java/com/dinocode/tui/CompletionMenu.java` | CompletionMenu 类 + handleCompletionKey + syncCompletionFromInput + render |
| 改造 | `src/main/java/com/dinocode/tui/Resume.java` | handleResume 改名/拆分为 dinocodeModel.openResumeMenu(Ui 接口实现) |
| 改造 | `src/main/java/com/dinocode/tui/dinocodeModel.java` | 加 cmdRegistry+completion+pendingPrintln+pendingAction 字段; 构造 registry; IDLE 输入分支接入补全键位拦截 |
| 改造 | `src/main/java/com/dinocode/tui/AgentEvent 队列.java` | submit() 把 dispatchCommand 调用替换为 dispatchSlash |
| 改造 | `src/main/java/com/dinocode/tui/Styles.java + MarkdownRenderer.java` | render() 在 TextBox 渲染后、status bar 前插入 completion.render |
| 改造 | `src/test/java/dev/dinocode/tui/dinocodeModelTest.java` | 老的 TestTuiSlashCompactRoutesToCommand / TestTuiUnknownSlashCommandFriendly 迁到新分发器 |
| 改造 | `src/main/java/com/dinocode/prompt/Prompt.java` | READY_HINT 改为"建议输入 /help 查看命令"(不再列具体命令名) |
| 改造 | `src/test/java/dev/dinocode/prompt/PromptTest.java` | 跟随 READY_HINT 变化 |
| 改造 | `src/main/java/com/dinocode/memory/MemoryManager.java` + 单测 | 新增 listFiles() |
| 改造 | `src/main/java/com/dinocode/session/Writer.java` + 单测 | 新增 path() |
| 改造 | `src/main/java/com/dinocode/tool/ToolRegistry.java` | 新增 count() |
| 改造 | `src/main/java/com/dinocode/agent/SessionRuntime.java` + 单测 | 新增 resetForNewSession |

## 任务

### T0a: MemoryManager.listFiles

**文件**：`src/main/java/com/dinocode/memory/MemoryManager.java`、`src/test/java/dev/dinocode/memory/MemoryManagerTest.java`
**依赖**：无
**步骤**：
1. 在 `MemoryManager` 新增 `public Files listFiles()`,其中 `Files` 是嵌套 record `record Files(java.util.List<String> project, java.util.List<String> user) {}`——列出项目层与用户层 memory 目录下的 `.md` 文件（含 `MEMORY.md` 自身）；目录不存在视为空 `List` 不抛异常；其它 IO 错误用 `Logger.warning` 记录后视为空 `List`；返回值已按文件名字典序排序
2. 单测覆盖 4 个 case：目录不存在 / 仅含 `MEMORY.md` / 含多 `.md` / 含 `.md` 与非 `.md` 混合

**验证**：`./gradlew test -Dtest=MemoryManagerTest#testListFiles` 全绿

### T0b: session.Writer.path

**文件**：`src/main/java/com/dinocode/session/Writer.java`、`src/test/java/dev/dinocode/session/WriterTest.java`
**依赖**：无
**步骤**：
1. `Writer` 类新增 `private final java.nio.file.Path path` 字段；`openWriter` / 构造器在打开成功后写 `this.path = <绝对路径>`
2. 新增 `public java.nio.file.Path path() { return this.path; }`
3. 单测：创建 writer 后断言 `path()` 非 null 且文件存在

**验证**：`./gradlew test -Dtest=WriterTest#testPath` 全绿
**注**：`sessionId` 不由 writer 提供,数据源是 `runtime.session().sessionId()`

### T0c: SessionRuntime.resetForNewSession + ToolRegistry.count

**文件**：`src/main/java/com/dinocode/agent/SessionRuntime.java`、`src/test/java/dev/dinocode/agent/SessionRuntimeTest.java`、`src/main/java/com/dinocode/tool/ToolRegistry.java`
**依赖**：无
**步骤**：
1. `SessionRuntime` 新增 `public void resetForNewSession(compact.SessionContext sesCtx)` — 原子重置 `replacement` / `recovery` / `autoTracking` / `session` / `usageAnchor` / `anchorMsgLen` / `turnCount`,把 `session` 字段指向 `sesCtx`
2. `SessionRuntimeTest` 单测：调用后所有字段回到构造时的零值,`session` 字段被替换
3. `ToolRegistry` 新增 `public int count()` — 返回当前已注册工具数量（O(1) 实现,基于现有内部 `List` 或 `Map` 长度）

**验证**：`./gradlew test -Dtest=SessionRuntimeTest#testResetForNewSession` 与 `./gradlew -q -pl . compile` 全过

### T1: 定义 Command record 与 Kind 枚举

**文件**：`src/main/java/com/dinocode/command/Kind.java`、`src/main/java/com/dinocode/command/Command.java`
**依赖**：无
**步骤**：
1. 新建包 `com.dinocode.command`
2. 定义 `public enum Kind { LOCAL, UI, PROMPT }`（按这个顺序）
3. 在 `Command.java` 中定义 `public interface Handler { void handle(AtomicBoolean cancelled, Ui ui) throws Exception; }`（Ui 接口在 T4 声明,Java 允许跨文件前向引用,只要 T4 在同一 ./gradlew compile 单元中即可）
4. 定义 `public record Command(String name, List<String> aliases, String description, Kind kind, boolean hidden, Handler handler) {}`,在紧凑构造函数里用 `List.copyOf(aliases)` 防御外部修改

**验证**：`./gradlew compileJava`（如 Ui 未声明,先在 `Ui.java` 占位）

### T2: 实现 CommandRegistry + 冲突检测 + 前缀匹配

**文件**：`src/main/java/com/dinocode/command/CommandRegistry.java`、`src/test/java/dev/dinocode/command/CommandRegistryTest.java`
**依赖**：T1
**步骤**：
1. `CommandRegistry` 类含 `byName: Map<String, Command>`、`visible: List<Command>`
2. 默认构造器：初始化空 `HashMap` + 空 `ArrayList`
3. `public void register(Command c)`：校验 `name` 非空且全小写、`aliases` 全部非空且全小写；遍历 `(name + aliases)` 每个键,如已存在于 `byName` 则 `throw new IllegalStateException("命令名/别名冲突: " + key)`；通过后把每个键塞进 `byName` 都指向同一 `Command`；若 `hidden=false` 则 `visible.add(c)`,然后对 `visible` 按 `name` 字典序排序
4. `public Optional<Command> lookup(String name)`：`Optional.ofNullable(byName.get(name.toLowerCase()))`
5. `public List<Command> visible()`：返回 `List.copyOf(visible)`（防外部改动）
6. `public List<Command> prefixMatch(String prefix)`：去掉前导 `/`、小写化；流式过滤 `visible`,`name.startsWith(prefix)` 的入选,保持字典序返回；`prefix` 为空时返回全部 visible
7. 在 `CommandRegistryTest` 写 5 个测试：`registerOk`(注册一条,`lookup` 命中)、`registerDuplicateNameThrows`、`registerDuplicateAliasThrows`、`visibleSorted`、`prefixMatch`

**验证**：`./gradlew test -Dtest=CommandRegistryTest` 全绿

### T3: parse 输入解析

**文件**：`src/main/java/com/dinocode/command/Dispatch.java`、`src/test/java/dev/dinocode/command/DispatchTest.java`
**依赖**：无
**步骤**：
1. 在 `Dispatch.java` 定义 `public record Parsed(String name, boolean isSlash) {}` 和 `public static Parsed parse(String input)`：对 `input` 调 `input.strip()`；若不以 `/` 开头返回 `new Parsed("", false)`；若仅为 `/` 返回 `new Parsed("", true)`；否则取掉前导 `/`、把首个空白前的部分小写化；若 name 之后还有非空白尾随字符,则返回 `new Parsed("", true)`（`lookup` 必然 miss）；否则返回 `new Parsed(name, true)`
2. 在 `DispatchTest` 用 `@ParameterizedTest` + `@CsvSource` 覆盖样本：`""` / `"   "` / `"hello"` / `"/"` / `"/help"` / `"  /HELP  "` / `"/help xx"`（→ `Parsed("", true)`）/ `"/help  "`（→ `Parsed("help", true)`）/ `"//double"` / `"/ /help"`（→ `Parsed("", true)`）,确认每个返回值

**验证**：`./gradlew test -Dtest=DispatchTest` 全绿

### T4: Ui 接口 + NopUi 测试桩

**文件**：`src/main/java/com/dinocode/command/Ui.java`
**依赖**：无（声明 Ui 接口让 T1 的 Handler 签名合法）
**步骤**：
1. import `com.dinocode.permission.Mode`
2. 声明 `Ui` 接口,方法集完整列出（见 plan.md "core.Ui" 一节）：`println/error/mode/setMode/injectAndSend/usageIn/usageOut/modelName/cwd/toolCount/memoryFiles/sessionPath/sessionId/quit/forceCompact/openResumeMenu/clearAndNewSession/idle`
3. 在同文件提供 `public final class NopUi implements Ui { public static final NopUi INSTANCE = new NopUi(); ... }`：所有写入方法 no-op、所有查询返回零值（`mode` 返回 `Mode.DEFAULT`、`memoryFiles` 返回 `List.of()` 等）
4. 在 `dispatchSlash` 出现需要的 helper 之前,先保证 `NopUi` 可用

**验证**：`./gradlew compileJava` 编译通过

### T5: 实现 5 条纯本地命令

**文件**：`src/main/java/com/dinocode/command/BuiltinLocal.java`
**依赖**：T1、T2、T4
**步骤**：
1. `static Handler help(CommandRegistry reg)`：返回 lambda,handler 内调 `reg.visible()`,计算最长 `name` 长度做对齐填充,逐条拼 `"/<name>  <desc>"`,用 `\n` 连接后 `ui.println(...)` 一次输出
2. `static Handler status()`：6 行 key:value,key 列宽固定（`Mode:` `Tokens:` `Tools:` `Memories:` `Model:` `Directory:` 中最长那个）；值依次取 `ui.mode().toString()`、`String.format("%d in / %d out", ui.usageIn(), ui.usageOut())`、`String.format("%d enabled", ui.toolCount())`、`String.format("%d files", ui.memoryFiles().size())`、`ui.modelName()`、`ui.cwd()`；首行加标题 `"dinocode Status"`（空行隔开）
3. `static Handler memory()`：`var files = ui.memoryFiles();` `files.isEmpty()` 时 `ui.println("无已加载的记忆文件")`；否则按行打印 `files`
4. `static Handler permission()`：`ui.println(ui.mode().toString())`
5. `static Handler session()`：`ui.println("Session: " + ui.sessionId() + "\nPath: " + ui.sessionPath())`

**验证**：`./gradlew compileJava` 通过 + 后续 T8 的 `BuiltinsTest` 会覆盖

### T6: 实现 5 条影响界面命令

**文件**：`src/main/java/com/dinocode/command/BuiltinUi.java`
**依赖**：T1、T4
**步骤**：
1. `static Handler exit()`：`(cancelled, ui) -> ui.quit();`
2. `static Handler plan()`：`(cancelled, ui) -> { ui.setMode(Mode.PLAN); ui.println("已切换到 PLAN 模式"); }`
3. `static Handler compact()`：`(cancelled, ui) -> { if (!ui.idle()) { ui.error("请等待当前任务完成"); return; } ui.forceCompact(); }`
4. `static Handler resume()`：`(cancelled, ui) -> { if (!ui.idle()) { ui.error("请等待当前任务完成"); return; } ui.openResumeMenu(); }`
5. `static Handler clear()`：`(cancelled, ui) -> { ui.clearAndNewSession(); ui.println("已清空当前会话,开启新 session"); }`

**验证**：`./gradlew compileJava` 通过

### T7: 实现 2 条提示词命令

**文件**：`src/main/java/com/dinocode/command/BuiltinPrompt.java`
**依赖**：T1、T4
**步骤**：
1. 包内 `static final String REVIEW_DIRECTIVE = "请审查当前上下文中的代码变更/已读取的文件,指出潜在 bug、可读性问题和可简化处。";`
2. `static Handler doRun()`：`(cancelled, ui) -> { ui.setMode(Mode.DEFAULT); ui.injectAndSend("/do", Prompt.EXECUTE_DIRECTIVE); }`（import `com.dinocode.prompt.Prompt`）
3. `static Handler review()`：`(cancelled, ui) -> ui.injectAndSend("/review", REVIEW_DIRECTIVE);`

**验证**：`./gradlew compileJava` 通过

### T8: Builtins.registerAll + 12 条命令一次性注册

**文件**：`src/main/java/com/dinocode/command/Builtins.java`、`src/test/java/dev/dinocode/command/BuiltinsTest.java`
**依赖**：T5、T6、T7
**步骤**：
1. `public static void registerAll(CommandRegistry reg)`：按字典序注册 12 条 `Command` 字面量（`name` 全部小写,`description` 一句中文,`kind` 按设计,`aliases = List.of()`,`hidden=false`）；`/help` 的 handler 通过 `BuiltinLocal.help(reg)` 闭包注入
2. 在 `BuiltinsTest` 写：`registerAll_allRegistered`(注册后 `reg.visible().size() == 12`、含所有 12 个名字)、`registerAll_noCollision`(直接调 `registerAll` 不抛异常)、`registerAll_handlersRunOnNopUi`(把 `NopUi.INSTANCE` 传给每个命令的 handler,断言全部不抛异常)
3. 升级为可观测桩：在 `BuiltinsTest` 新增 `RecordingUi` 类实现 `Ui` 接口（委托给 `NopUi.INSTANCE`,但记录 `println/error/setMode/injectAndSend` 调用）；至少 3 个行为断言：
   - `handleStatus_printsAllKeys` — `status` handler 调 `println` 一次且文本含 6 个 key（Mode/Tokens/Tools/Memories/Model/Directory）
   - `handleCompact_blocksWhenBusy` — `compact` handler 在 `idle()==false` 时调 `error` 不调 `forceCompact`
   - `handleDo_setsModeAndInjects` — `doRun` handler 调 `setMode(Mode.DEFAULT)` + `injectAndSend("/do", ...)`

**验证**：`./gradlew test -Dtest=BuiltinsTest` 全绿；`./gradlew spotbugs:check`（如启用）无 warning

### T8.5: dinocodeModel 字段铺垫

**文件**：`src/main/java/com/dinocode/tui/dinocodeModel.java`
**依赖**：T8
**步骤**：
1. `dinocodeModel` 类在原 `toolRegistry: ToolRegistry` 字段之后增 `cmdRegistry: CommandRegistry`、`completion: CompletionMenu`、`pendingPrintln: List<String>`（`new ArrayList<>()`）、`pendingAction: Runnable` 四个字段
2. 在构造函数中初始化（registry 通过 T9c 注册）

**验证**：`./gradlew compileJava` 零退出码

### T9a: dinocodeModel 实现 Ui 只读查询方法

**文件**：`src/main/java/com/dinocode/tui/Commands.java`（在该文件已被清空旧 handler 后重写）
**依赖**：T8.5
**步骤**：
1. 删除旧文件 `Commands.java` 全部内容（`builtinCommands` Map、`CommandHandler` 类型、`dispatchCommand`、`handleExit`/`handlePlan`/`handleDo`/`handleCompact`/`handleUnknown`、`formatCompactNotice` 全部移除）
2. 给 `dinocodeModel` 实现 `Ui` 接口的所有只读方法（写在 `dinocodeModel.java` 内 `implements Ui` 即可,Commands.java 可只保留 helper 函数）：
   - `mode() -> this.mode`
   - `usageIn() / usageOut() -> this.usageIn / this.usageOut`
   - `modelName() -> provider != null ? provider.model() : ""`
   - `cwd() -> this.cwd`（若 `dinocodeModel` 上没有此字段,从构造参数拷一份）
   - `toolCount() -> this.toolRegistry.count()`
   - `memoryFiles() -> { var f = memoryManager.listFiles(); return Stream.concat(f.project().stream(), f.user().stream()).toList(); }`
   - `sessionPath() -> writer != null ? writer.path().toString() : ""`
   - `sessionId() -> runtime != null && runtime.session() != null ? runtime.session().sessionId() : ""`
   - `idle() -> this.state == SessionState.IDLE`

**验证**：`./gradlew compileJava` 零退出码

### T9b: dinocodeModel 实现 Ui 写入方法 + 缓冲机制

**文件**：`src/main/java/com/dinocode/tui/Commands.java`、`src/main/java/com/dinocode/tui/dinocodeModel.java`
**依赖**：T9a
**步骤**：
1. `println(msg) -> this.pendingPrintln.add(msg);`（原始字符串,渲染时再用 `noticeBlock` 包）
2. `error(msg) -> this.pendingPrintln.add("ERROR\u0000" + msg);`（用前缀编码区分 notice/error,渲染时按前缀分流）
3. `setMode(Mode m) -> this.mode = m;`
4. `quit() -> this.pendingAction = () -> close();`（`close` 内部 cancel 根 token + `screen.stopScreen()`）
5. `forceCompact()` → 复用原 `handleCompact` 内构造的 `Runnable`（启动 compact 任务的代码块）,push 到 `pendingAction`
6. `openResumeMenu()` → 直接调 T10 提供的方法体（本步骤仅声明,实现在 T10 在 `Resume.java` 提供）
7. `clearAndNewSession()` — 步骤：
   a. 关闭 `writer`（`writer.close()`）
   b. `var newSesCtx = ContextCompactor.newSessionContext(this.cwd);` `IOException` 时 `error(ex.getMessage())` 直接返回（不动现状）。注意签名：`ContextCompactor.newSessionContext(Path workspace) throws IOException`,内部在 `<workspace>/.dinocode/sessions/<id>/tool-results` 下建好目录
   c. `var newWriter = Session.openWriter(newSesCtx.sessionDir());` `IOException` 时 `error` 后返回（沿用 ch09 既有的 `openWriter` 入口,不要新写一个 `newWriter`）
   d. `this.writer = newWriter;`
   e. 重新构造 `this.conv`：`this.conv = new Conversation(onAppend, onReplace);`,`onAppend`/`onReplace` 闭包捕获 `newWriter` 与新的 `isFirst:=true`
   f. `runtime.resetForNewSession(newSesCtx);`
   g. `this.iter = 0; this.usageIn = 0; this.usageOut = 0;`
   h. 把"重绘"`Runnable` push 到 `pendingAction`（可以是清空 scrollback Panel 或仅 `() -> {}`）
8. `injectAndSend(label, preset)` — `conv.addUser(preset);` 把 `() -> beginTurn(userBlock(label))` push 到 `pendingAction`

建议：把 conv 闭包构造抽成 `private void bindConversation(Writer writer)` 让构造器和 `clearAndNewSession` 共用,避免漂移

**验证**：`./gradlew compileJava` 零退出码

### T9c: dispatchSlash 入口 + 注册中心构造

**文件**：`src/main/java/com/dinocode/tui/Commands.java`、`src/main/java/com/dinocode/tui/dinocodeModel.java`
**依赖**：T9b
**步骤**：
1. `Commands.java` 新增静态方法或 `dinocodeModel.dispatchSlash(String text): boolean`：
   a. `var parsed = Dispatch.parse(text);` 若 `!parsed.isSlash()` 返回 `false`
   b. 清 `pendingPrintln` 与 `pendingAction`
   c. `var cmdOpt = cmdRegistry.lookup(parsed.name());`
   d. `cmdOpt.isEmpty()` → `pendingPrintln.add(noticeBlock("未知命令: 输入 /help 查看可用命令"));`（注：`parse` 返回 `("", true)` 即退化输入（如纯 `"/"` 或 `"/<空白>"`）时,提示文案不要拼 `"/"+name` 避免出现 `"未知命令: /, ..."` 这种悬空斜杠）
   e. `cmdOpt.isPresent() && (kind == Kind.UI || kind == Kind.PROMPT) && state != SessionState.IDLE` → push `errorBlock("请等待当前任务完成")` 到 `pendingPrintln`
   f. 否则 `try { cmd.handler().handle(this.cancelled, this); } catch (Exception ex) { pendingPrintln.add(errorBlock(ex.getMessage())); }`
   g. 在 GUI 线程上 flush：对 `pendingPrintln` 中每条调 `program.send(new AgentEventMessage(() -> scrollback.addComponent(...))`；若 `pendingAction != null` 则 `program.send(new AgentEventMessage(pendingAction);` 返回 `true`
2. `dinocodeModel` 构造函数中加：`var reg = new CommandRegistry(); Builtins.registerAll(reg); this.cmdRegistry = reg;`

**验证**：`./gradlew shadowJar` 零退出码；`./gradlew compileJava` (代码风格由 IDE 保证) 无 warning

### T10: openResumeMenu — handleResume 重构进 Ui 接口

**文件**：`src/main/java/com/dinocode/tui/Resume.java`
**依赖**：T9
**步骤**：
1. 把现有 `handleResume(dinocodeModel app)` 方法体迁移到 `dinocodeModel.openResumeMenu()`：把"state guard"那一段挪到 `BuiltinUi.resume()`（已经做了）；剩下"构造 sessionItem 列表、设置 resumeList、切换 `state = SessionState.RESUMING`"放进 `openResumeMenu`；没有 `Runnable` 返回则用 nop `pendingAction`。同时移除 `openResumeMenu` 内部对 `state != IDLE` 的判断和提示（guard 已在 `dispatchSlash` 按 `Kind` 统一处理,handler 内不重复 `ui.idle()` 检查）
2. 如果 `handleResume` 老方法还被引用,删除引用；否则直接整段移除
3. `updateResuming`、`doResumeSession`、`resumeSessionEvent` 保持不变（它们由 GUI 线程调度,不属于命令系统）

**验证**：`./gradlew compileJava` 待 T12 完成

### T11: CompletionMenu 状态机 + 渲染

**文件**：`src/main/java/com/dinocode/tui/CompletionMenu.java`（新）
**依赖**：T2
**步骤**：
1. 定义 `CompletionMenu` 类：`items: List<Command>`、`cursor: int`、`offset: int`、`active: boolean`
2. 定义 `static final int MAX_ROWS = 8;`
3. `void update(String input, CommandRegistry reg)`：input 去前后空白；若不以 `/` 开头则 `active = false` 并 return；否则 `items = reg.prefixMatch(input);` 若 `items.isEmpty()` 仍 `active = true`（显示"无匹配"）；`cursor`/`offset` 在 `items` 长度变化时夹紧
4. `void moveUp() / moveDown()`：夹在 `[0, items.size()-1]`；`offset` 跟随 `cursor`,使 `cursor` 始终在可见窗口内
5. `Command selected()`：`items.isEmpty() ? null : items.get(cursor);`
6. `void hide()`：`active = false; items = List.of(); cursor = 0; offset = 0;`
7. `String render(int width)`：`!active` 返回 `""`；否则用一组 ANSI 着色或 `TextColor` 标记拼一个左对齐的多行块：每行 `"/<name>  <description>"`,`name` 列做对齐填充；高亮 `cursor` 行（背景色或反色）；上下溢出时显示 `"↑ N more" / "↓ N more"` 提示行；整块宽度不超 `width`
   - JLine/tui.tea 中实际渲染时,`View.render()` 用一个垂直 `Panel`,每行一个 `Label` 设 `setForegroundColor` / `setBackgroundColor`；这里只算"该行文字"
8. `dinocodeModel.handleCompletionKey(KeyStroke ks): boolean`：`if (!completion.active()) return false;` switch on `ks.getKeyType()` { `ArrowUp`: `moveUp` / `ArrowDown`: `moveDown` / `Escape`: `hide` / `Enter` / `Tab`: `var sel = completion.selected(); if (sel != null) executeSelected(sel); else completion.hide();` / default: `return false;`（透传 TextBox）};被消费的分支返回 `true`。`executeSelected(sel)`：`textBox.setText("/" + sel.name()); var handled = submit(); completion.hide();` 由 `handleCompletionKey` 把 `handled` 透传返回 `true`,不丢弃 quit / spinner / beginTurn 等异步动作
9. `dinocodeModel.syncCompletionFromInput()`：取 `textBox.getText()`,调 `completion.update(value, cmdRegistry)`（注意是 `cmdRegistry` 不是 `toolRegistry`）

**验证**：`./gradlew compileJava` 待 T12；先用 `./gradlew spotbugs:check`（如启用）看类型错误

### T12: TUI Update 集成补全键位

**文件**：`src/main/java/com/dinocode/tui/dinocodeModel.java`
**依赖**：T9c、T10、T11
**步骤**：
1. `dinocodeModel` 字段已在 T8.5 加好（`cmdRegistry`、`completion`、`pendingPrintln`、`pendingAction`）；本任务不重新声明
2. 构造函数中 `cmdRegistry` 的构造与注入已在 T9c step 2 完成；本任务不重复
3. `IDLE` 状态下处理 `KeyStroke`（TextBox 的 `InputFilter` 或 Window 的 `KeyStroke` 钩子）：
   - 先调 `if (handleCompletionKey(ks)) return;`
   - 否则继续原 TextBox 路径
   - TextBox 内容变化后（注册 `textBox.setTextChangeListener(...)`）调 `syncCompletionFromInput()`
4. `IDLE` 中对 `Enter` 键的处理保持现状（由 `submit()` 处理）；`submit()` 内的命令分发改动放 T13

**验证**：`./gradlew compileJava` 通过

### T13: AgentEvent 队列.submit 接入 dispatchSlash + View.render 渲染补全菜单

**文件**：`src/main/java/com/dinocode/tui/AgentEvent 队列.java`、`src/main/java/com/dinocode/tui/Styles.java + MarkdownRenderer.java`
**依赖**：T12
**步骤**：
1. `AgentEvent 队列.java`：`submit()` 中把现有的 `if (Commands.dispatchCommand(text)) { ... }` 整段替换为 `if (app.dispatchSlash(text)) { app.textBox().setText(""); app.completion().hide(); return; }`
2. `Styles.java + MarkdownRenderer.java`：在 `render()` 方法中,定位到 TextBox 渲染块之后、status bar 渲染块之前；插入 `if (app.completion().active()) { mainPanel.addComponent(buildCompletionPanel(app.completion(), width)); }` （`buildCompletionPanel` 把 `completion.render(width)` 输出拆行,每行包一个 `Label` 加 `TextColor`）
3. `Styles.java + MarkdownRenderer.java`：不要动 `statusBar` / `modeLabel` / `modeStatusStyle` 方法

**验证**：`./gradlew shadowJar` 通过；`./gradlew test -Dtest=dinocodeModelTest#testTuiSlash` 期待红（下一任务迁移测试）

### T14: 迁移测试 + READY_HINT 调整

**文件**：`src/test/java/dev/dinocode/tui/dinocodeModelTest.java`、`src/main/java/com/dinocode/prompt/Prompt.java`、`src/test/java/dev/dinocode/prompt/PromptTest.java`
**依赖**：T13
**步骤**：
1. `dinocodeModelTest`：把 `testTuiSlashCompactRoutesToCommand` 改为构造 `dinocodeModel` + 注册 builtins 后,调 `app.dispatchSlash("/compact")`、断言返回 `true`、断言未调 `conv.addUser`
2. `dinocodeModelTest`：`testTuiUnknownSlashCommandFriendly` 改为调 `app.dispatchSlash("/foobar")`、断言返回 `true`、断言 `app.pendingPrintln()` 含"未知命令"
3. `dinocodeModelTest`：新增 `testTuiDispatchCaseInsensitive` 测 `/Help` 与 `/help` 同效；`testTuiDispatchHelpListsAllBuiltins` 测 /help 输出含 12 个命令名
4. `Prompt.java`：`READY_HINT` 字符串由现有"/plan, /do, /exit"列表改为类似 `"已就绪,输入 /help 查看可用命令。"`（具体文本含 `/help` 引导即可）
5. `PromptTest`：跟随调整断言

**验证**：`./gradlew test` 全绿；`./gradlew spotbugs:check`（如启用）无 warning；`./gradlew compileJava` (代码风格由 IDE 保证) 无 diff

### T15: 端到端验证（tmux 实跑）

**文件**：无（运行可执行文件）
**依赖**：T14
**步骤**：
1. `cd /Users/codemelo/dinocode && ./gradlew shadowJar`
2. `tmux new-session -d -s mewspec 'java -jar build/libs/dinocode.jar'` 启动会话
3. 按 checklist.md 的"端到端场景（tmux 实跑）"逐项发送按键并截屏：
   - 启动后键入 `/` 看补全菜单是否弹出且含 12 条
   - 键入 `/s` 看是否过滤为 /session、/status
   - 选中 /status 回车验证 6 字段输出
   - 依次跑 /help、/memory、/permission、/session、/review、/clear,逐一观测输出
   - 验证 /plan 切到 plan 模式后状态栏徽章变化
   - 验证 /do 切回 default + 触发 AI 回复
   - 验证 /resume 列表能看到 /clear 之前的旧会话
   - 验证未知命令 /foobar 提示
   - 验证启动期冲突检测：临时给某条命令多注册一遍同名,启动应抛 `IllegalStateException` 退出,看错误信息
4. 全部通过后 `tmux kill-session -t mewspec`

**验证**：按 checklist.md 全部勾选；期间出错则修复后从失败点重跑

## 执行顺序

```
T0a, T0b, T0c (并行) → T1 → (T2, T3, T4 并行) → (T5, T6, T7 并行) → T8 → T8.5 → T9a → T9b → T9c → T10 → T11 → T12 → T13 → T14 → T15
```

- T0a/T0b/T0c 是底层 helper 铺垫（`MemoryManager.listFiles`、`Writer.path`、`SessionRuntime.resetForNewSession`、`ToolRegistry.count`）,互不依赖可并行
- T1/T2/T3/T4 是 command 包基础,T1→T2 是结构依赖,T2/T3/T4 互不依赖可并行
- T5/T6/T7 三组命令实现互不依赖,可并行
- T8 必须在 T5+T6+T7 后
- T8.5 给 `dinocodeModel` 加字段,作为 T9a 前置
- T9a→T9b→T9c 拆分原 T9：只读方法 → 写入方法+缓冲 → dispatchSlash 与注册中心,严格串行
- T10 替换 `openResumeMenu`；T11（`CompletionMenu`）仅依赖 T2,放在 T10 后接入 UI 也可
- T12 把 IDLE 输入分支接入补全键位；T13 把 stream/view 接入；T14 是测试与 READY_HINT 调整,T15 是端到端验证