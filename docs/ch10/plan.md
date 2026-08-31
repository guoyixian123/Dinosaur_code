# slash命令体系 Plan

## 架构概览

```
┌──────────────────────────────────────────────────────────┐
│                  com.dinocode.tui                         │
│                                                          │
│  dinocodeModel.submit() ─┬─► Dispatch.parse → CommandRegistry   │
│                   │      (lookup by name/alias)          │
│                   │             │                        │
│                   │             ▼                        │
│                   │      Handler.handle(ctx, ui)         │
│                   │             │                        │
│                   │             ▼ via Ui interface       │
│                   │      dinocodeModel 字段桥接 (impl Ui)        │
│                   │                                      │
│                   └─► (非 / 开头) conv.addUser + beginTurn│
│                                                          │
│  updateIdle ─► CompletionMenu 状态机 ─► View.render      │
└──────────────────────────────────────────────────────────┘
                  ▲
                  │ 依赖
                  │
┌──────────────────────────────────────────────────────────┐
│              com.dinocode.command (新包)                  │
│                                                          │
│  Command/Kind/Handler 类型定义                           │
│  CommandRegistry: 注册 + 冲突检测 + 前缀匹配 + 字典序排序│
│  Ui 接口: handler 操作 TUI 的唯一通道                    │
│  Dispatch: parse + lookup                                │
│  12 条内置命令的 handler 实现 (按 Kind 分类)              │
└──────────────────────────────────────────────────────────┘
```

- 新建包 `com.dinocode.command`：纯领域逻辑,不依赖 JLine/tui.tea
- 既有包 `com.dinocode.tui`：删掉 `Commands.java` 里的旧注册表与 5 个旧 handler；改为构造 `CommandRegistry`、实现 `Ui` 接口、把分发结果桥接回 `dinocodeModel`
- 自动补全菜单是 TUI 层独有的 UX 元素,完全在 `com.dinocode.tui.CompletionMenu` 中实现,只读 `CommandRegistry` 拿候选列表

## 核心数据结构

### `command.Kind`

```java
package com.dinocode.command;

public enum Kind {
    LOCAL,   // 纯本地: 只打印, 不改 TUI 状态, 不进 history
    UI,      // 影响界面: 改 TUI 状态, 不进 history
    PROMPT   // 提示词: 注入 user 消息 + 触发回合, 进 history
}
```

### `command.Command`

```java
package com.dinocode.command;

public record Command(
        String name,                // 不带 "/" 前缀, 全小写, 唯一
        java.util.List<String> aliases, // 不带 "/" 前缀, 全小写, 全局唯一(含 name)
        String description,         // 一句话, 用于 /help 与补全菜单
        Kind kind,
        boolean hidden,             // /help 与补全菜单都不显示, 但 dispatcher 仍可命中
        Handler handler
) {}

@FunctionalInterface
public interface Handler {
    void handle(java.util.concurrent.atomic.AtomicBoolean cancelled, Ui ui) throws Exception;
}
```

> Handler 的第一个参数沿用 ch10 已有的"根取消令牌"——`AtomicBoolean cancelled`（外加 `Thread.interrupt()`）。Java 这边不像 Go 把 `context.Context` 当一等参数,handler 几乎不需要它；保留只是为了与 Agent/Memory 体系的取消传播口径一致。

### `command.CommandRegistry`

```java
public final class CommandRegistry {
    private final java.util.Map<String, Command> byName = new java.util.HashMap<>(); // 主名 + 别名都映射到同一 Command, key 已转小写
    private final java.util.List<Command> visible = new java.util.ArrayList<>();     // 按 name 字典序排序, 排除 hidden, 给 /help 与补全菜单使用

    public void register(Command c);                  // 名/别名冲突即抛 IllegalStateException
    public java.util.Optional<Command> lookup(String name); // name 内部转小写
    public java.util.List<Command> visible();         // 返回已排序的可见命令副本
    public java.util.List<Command> prefixMatch(String prefix); // prefix 含 "/", 内部 trim 并小写; 前缀匹配 name; 不匹配别名/描述
}
```

### `command.Ui` 接口

handler 通过该接口操作 TUI；`dinocodeModel` 实现此接口。

```java
package com.dinocode.command;

import java.util.List;

public interface Ui {
    // 输出 (通过 dinocodeModel 的 pendingPrintln 缓冲 -> View 下一帧把 noticeBlock 推进 scrollback)
    void println(String msg);
    void error(String msg);

    // 模式
    com.dinocode.permission.Mode mode();
    void setMode(com.dinocode.permission.Mode m);

    // 对话注入 (KindPrompt 命令使用)
    // displayLabel 在 scrollback 中显示, presetPrompt 是实际写入 conversation/JSONL 的文本
    void injectAndSend(String displayLabel, String presetPrompt);

    // /status 与 /memory 等只读查询
    long usageIn();
    long usageOut();
    String modelName();
    String cwd();
    int toolCount();
    List<String> memoryFiles();
    String sessionPath();
    String sessionId();

    // 影响界面动作
    void quit();
    void forceCompact();
    void openResumeMenu();
    void clearAndNewSession();

    // 状态机查询
    boolean idle();
}
```

### Ui 接口降级合约

- `dispatchSlash` 仅在 `SessionState.IDLE` 下被调用
- 即便如此,Ui 实现需对 null 做防御:
  - `provider == null` → `modelName()` 返回 `""`
  - `agent == null` → `forceCompact()` 用 `error("agent 未就绪")` 兜底
  - `writer == null` → `sessionPath()` / `sessionId()` 返回空串
  - `memoryManager == null` → `memoryFiles()` 返回空 `List`

### `tui.CompletionMenu` (新)

```java
package com.dinocode.tui;

public final class CompletionMenu {
    static final int MAX_ROWS = 8;

    private java.util.List<com.dinocode.command.Command> items = java.util.List.of(); // 当前候选, 已按 name 字典序
    private int cursor;   // 当前高亮索引
    private int offset;   // 滚动偏移 (候选数 > MAX_ROWS 时)
    private boolean active; // 是否激活

    public void update(String input, com.dinocode.command.CommandRegistry reg); // 根据当前输入刷新候选; 无 "/" 前缀则 deactivate
    public void moveUp();
    public void moveDown();
    public com.dinocode.command.Command selected();
    public void hide();
    public String render(int width); // 多行字符串, 已按 MAX_ROWS 截断 + 滚动
    public boolean active();
}
```

**边界规则**：
- `dinocodeModel.handleCompletionKey` 在 TextBox 内容含 `\n` 时强制 `active=false`（避免多行粘贴误激活）
- `selected() == null` 时（零匹配）,回车走未命中提示分支、Tab/ESC 仅关闭菜单

## 模块设计

### `command/Command.java`
**职责**：定义 `Command` record、`Handler` 函数接口。
**对外接口**：上面列出的类型。
**依赖**：仅 `com.dinocode.permission`（Kind 的语义说明引用）。

### `command/CommandRegistry.java`
**职责**：注册中心。维护 `byName` 索引、`visible` 排序列表；`register` 时做冲突检测；`prefixMatch` 提供补全数据源。
**对外接口**：上面 `CommandRegistry` 的方法集。
**依赖**：仅 JDK 标准库。

### `command/Dispatch.java`
**职责**：`static Parsed parse(String input)`——空白/空串/非 `/` 开头返回 `new Parsed("", false)`；只含 `/` 返回 `new Parsed("", true)`；取掉前导 `/`、第一个空白前的部分小写化作为 `name`；若 `name` 之后还有非空白尾随字符（用户传了参数）,返回 `new Parsed("", true)` 让 `lookup` 必然 miss 走未命中分支。纯粹字符串操作,无副作用。`CommandRegistry.lookup` 已能完成查找,Dispatch 不必另外封装。
**对外接口**：`Parsed`（record）、`parse`。

### `command/Ui.java`
**职责**：定义 `Ui` 接口；同时提供一个 `NopUi` 测试桩,供 registry/handler 单元测试用。
**对外接口**：`Ui` 接口、`NopUi.INSTANCE`（返回吞掉所有调用、查询返回零值的桩）。

### `command/BuiltinLocal.java`
**职责**：5 条纯本地命令的 handler——`/help`、`/status`、`/memory`、`/permission`、`/session`。
- `/help`：调用 `reg.visible()`,按"<name>  <description>"两列对齐输出,通过 `ui.println` 打印。其中 `reg` 由命令构造时闭包捕获（`Builtins.registerAll` 把 `reg` 自身传入 help handler）。
- `/status`：按固定顺序输出 6 行——`Mode/Tokens/Tools/Memories/Model/Directory`,值来自 `ui.mode().toString()` / `ui.usageIn|Out()` / `ui.toolCount()` / `ui.memoryFiles().size()` / `ui.modelName()` / `ui.cwd()`。
- `/memory`：调用 `ui.memoryFiles()`,逐行打印文件名；为空时打印"无已加载的记忆文件"。
- `/permission`：打印 `ui.mode().toString()` 一行。
- `/session`：打印 `"Session: <id>"` + `"Path: <path>"` 两行（值来自 `ui.sessionId()`、`ui.sessionPath()`）。

### `command/BuiltinUi.java`
**职责**：5 条影响界面命令——`/exit`、`/plan`、`/compact`、`/resume`、`/clear`。
- `/exit`：调用 `ui.quit()`。
- `/plan`：调用 `ui.setMode(Mode.PLAN)` + `ui.println("已切换到 PLAN 模式")`。
- `/compact`：调用 `ui.forceCompact()`（idle 守护由 `dispatchSlash` 在 handler 调用前完成,handler 自身不再检查）。
- `/resume`：调用 `ui.openResumeMenu()`（idle 守护由 handler 通过 `dispatchSlash` 做唯一一次,`openResumeMenu` 自身不再检查）。
- `/clear`：调用 `ui.clearAndNewSession()`。

### `command/BuiltinPrompt.java`
**职责**：2 条提示词命令——`/do`、`/review`。
- `/do`：`ui.setMode(Mode.DEFAULT)` + `ui.injectAndSend("/do", Prompt.EXECUTE_DIRECTIVE)`。
- `/review`：`ui.injectAndSend("/review", REVIEW_DIRECTIVE)`（`REVIEW_DIRECTIVE` 是包内 `static final String` 常量,文案如"请审查上下文中的代码变更,指出潜在 bug、可读性问题、可简化处"）。

### `command/Builtins.java`
**职责**：`public static void registerAll(CommandRegistry reg)`——按一致顺序在 reg 上注册 12 条命令,把对应 handler 写进 `Command` 字面量；`/help` 的 handler 需要闭包捕获 `reg`。
**对外接口**：`Builtins.registerAll(CommandRegistry)`。

### `tui/Commands.java`（改造）
**职责**：变成 thin glue：
1. 给 `dinocodeModel` 实现 `Ui` 接口的所有方法（每个方法 1~5 行,字段桥接 + push 到 `pendingPrintln`）
2. 提供 `dinocodeModel.dispatchSlash(String text)`：调 `Dispatch.parse` → `cmdRegistry.lookup` → 找到则 `cmd.handler().handle(this.cancelled, this)`、未找到则 push `noticeBlock("未知命令...")` 到 `pendingPrintln`
3. 删掉 `builtinCommands` Map、`handleExit`/`handlePlan`/`handleDo`/`handleCompact`/`handleUnknown` 等 5 个老 handler；保留 `handleResume` 中和 `openResumeMenu` Ui 方法整合的部分（ch09 写的 list/state 启动逻辑搬到 `dinocodeModel.openResumeMenu`）

**依赖**：`com.dinocode.command`、`com.dinocode.permission`、`com.dinocode.prompt`。

### `agent/SessionRuntime.java`（改动）
- `SessionRuntime` 新增 `resetForNewSession(compact.SessionContext sesCtx)` 方法：原子重置 `replacement`/`recovery`/`autoTracking` 三个 compact 子状态,`usageAnchor`/`anchorMsgLen`/`turnCount` 清零,`session` 字段指向新的 `sesCtx`；`contextWindow` 保留；writer 与 conv 重建由 `clearAndNewSession` 自身负责,不进 runtime 接口

### `tui/AgentEvent 队列.java`（改动）
- `submit()` 路径：把 `dispatchCommand(text)` 这一行替换为 `dispatchSlash(text)`,其余流程不变（空输入早返回、非命令走 `conv.addUser + beginTurn`）

### `tui/CompletionMenu.java`（新）
**职责**：自动补全菜单状态机 + 渲染。
- `CompletionMenu` 类与方法见数据结构小节
- 提供 `dinocodeModel.handleCompletionKey(KeyStroke ks)`：当菜单激活时,返回 `true` 表示该键已被菜单消费；否则返回 `false` 让上层透传给 TextBox
- 提供 `dinocodeModel.syncCompletionFromInput()`：每次 TextBox 内容变化后调用,根据当前内容刷新菜单 active/items

### `tui/dinocodeModel.java`（改动）
- `dinocodeModel` 增字段：`CommandRegistry cmdRegistry`、`CompletionMenu completion`、`List<String> pendingPrintln`、`Runnable pendingAction`
- 构造时：`var reg = new CommandRegistry(); Builtins.registerAll(reg); this.cmdRegistry = reg;`
- `IDLE` 状态下处理 `KeyStroke`：
  - 先调 `handleCompletionKey(ks)`,被消费则直接返回
  - 否则继续走原 TextBox.handleInput + Enter 触发 submit 的流程
  - TextBox 内容变化后立刻调 `syncCompletionFromInput()` 让菜单跟随输入实时刷新

### `tui/Styles.java + MarkdownRenderer.java`（改动）
- `render()` 函数：在 TextBox 渲染块之后、status bar 渲染块之前,如果 `app.completion().active()`,插入 `app.completion().render(width)`
- 不动 statusBar、modeLabel、modeStatusStyle

## 模块交互

### 命令分发流（用户回车）

```
keystroke (Enter) ─► dinocodeModel.handleInput (IDLE)
                       │
                       ▼
                 dinocodeModel.submit()
                  trim 输入
                  空输入 → 早返回
                       │
                       ▼
                dinocodeModel.dispatchSlash(text)
                  │
                  ├─ Dispatch.parse(text)
                  │   isSlash=false → 返回 false (上层走 addUser + beginTurn)
                  │   isSlash=true 拿到 name
                  │
                  ├─ cmdRegistry.lookup(name)
                  │   isEmpty → pendingPrintln.add(noticeBlock(unknown msg)), 清输入
                  │
                  └─ cmd.handler().handle(cancelled, this)
                       │
                       ├─ throws ex → pendingPrintln.add(errorBlock(ex.getMessage()))
                       │
                       ▼
                     通过 Ui 接口操作 dinocodeModel
                       ├─ println    → pendingPrintln.add(noticeBlock(...))
                       ├─ setMode    → this.mode = newMode
                       ├─ injectAndSend → conv.addUser(preset) + beginTurn(userBlock(label))
                       ├─ quit       → pendingAction = () -> close()
                       ├─ forceCompact / openResumeMenu / clearAndNewSession → 触发对应 sub-flow
```

注意：`Ui.quit()` / `Ui.forceCompact()` 等需要异步动作的实现,通过 `dinocodeModel` 内部缓存的 `pendingAction` 字段：Ui 方法 push 一个 `Runnable` 到 `pendingAction`,`dispatchSlash` 在 handler 返回后从 `pendingAction` 取出,在 GUI 线程上 `program.send()` 执行。这样 Ui 接口不需要返回值,handler 写起来线性。

### 自动补全流

```
keystroke (任意字符) ─► dinocodeModel.handleInput (IDLE)
                          │
                          ▼
                  dinocodeModel.handleCompletionKey(ks)
                    ┌─ 菜单 active=true:
                    │    ↑/↓     → menu.moveUp/Down, 消费
                    │    Tab/⏎   → 执行 menu.selected() 的 handler, 关闭菜单, 消费
                    │    ESC     → menu.hide(), 消费
                    │    其他键  → 不消费, 透传 TextBox
                    │
                    └─ 菜单 active=false:
                         不消费, 透传

(透传 TextBox 处理后)
dinocodeModel.syncCompletionFromInput()
  读 textBox.getText()
  首字符是 "/" → menu.update(value, cmdRegistry) → active=true 或刷新候选
  首字符非 "/" → menu.hide()

(渲染)
View.render():
  scrollback 渲染区
  textBox 渲染区
  ↓ 如果 completion.active():
  completion.render(width)  ← inline, 紧贴 TextBox
  ↓
  status bar 渲染区
```

## 文件组织

```
src/main/java/com/dinocode/command/        新包
├── Kind.java              Kind 枚举
├── Command.java           Command record + Handler 函数接口
├── CommandRegistry.java   register/lookup/visible/prefixMatch
├── Dispatch.java          Parsed record + parse(String)
├── Ui.java                Ui 接口 + NopUi 测试桩
├── Builtins.java          registerAll(reg) + REVIEW_DIRECTIVE 常量
├── BuiltinLocal.java      /help /status /memory /permission /session
├── BuiltinUi.java         /exit /plan /compact /resume /clear
└── BuiltinPrompt.java     /do /review

src/test/java/dev/dinocode/command/
├── CommandRegistryTest.java   注册中心冲突 / 前缀匹配测试
├── DispatchTest.java          parse 测试
└── BuiltinsTest.java          12 条命令的注册与 NopUi 调用测试

src/main/java/com/dinocode/tui/
├── Commands.java         改造: dinocodeModel 实现 Ui 接口 + dispatchSlash + 删旧 handler
├── CompletionMenu.java   新: CompletionMenu + handleCompletionKey + syncCompletionFromInput
├── AgentEvent 队列.java       改: submit() 调 dispatchSlash
├── dinocodeModel.java           改: 加 cmdRegistry + completion 字段, 构造 cmdRegistry
├── Styles.java + MarkdownRenderer.java             改: render() 中插入补全菜单渲染
├── Resume.java           改: 将 handleResume 函数体迁到 dinocodeModel.openResumeMenu() 方法, 文件仍在 tui/Resume.java; 删除老 handleResume
└── dinocodeModelTest.java       改: 旧的 TestTuiSlashCompactRoutesToCommand 等用例迁到新分发器

src/main/java/com/dinocode/agent/SessionRuntime.java  改: 新增 resetForNewSession(sesCtx); clearAndNewSession 调用此 helper 重置 compact 子状态

src/main/java/com/dinocode/dinocode.java   不变 (dinocodeModel 构造时内部 wire cmdRegistry)
src/main/java/com/dinocode/prompt/Prompt.java    READY_HINT 由"硬编码列表" 改为"建议输入 /help 查看可用命令" (去掉与命令清单同步的负担)
src/test/java/dev/dinocode/prompt/PromptTest.java  改: 跟随 READY_HINT 文案调整断言
```

## 技术决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 命令系统包归属 | 新建 `com.dinocode.command`,不留在 `com.dinocode.tui` | tui 内 handler 持有 `dinocodeModel` 引用紧耦合,要满足 G3 必须把命令逻辑搬出 tui 包 |
| 注册方式 | 显式 `Builtins.registerAll(reg)`,不用 static 初始化副作用 | 测试时能用空 registry,启动顺序明确,易做单测 |
| 冲突检测 | `register` 内部抛 `IllegalStateException`,信息含具体名字/别名 | 失败快,启动期就报,不会进入运行时 |
| Handler 函数签名 | `Handler { void handle(AtomicBoolean cancelled, Ui ui) throws Exception; }` 而非 `Function<dinocodeModel, Runnable>` | 满足 G3 解耦；handler 抛 non-null 异常时,`dispatchSlash` 自动把 `errorBlock(ex.getMessage())` 追加到 `pendingPrintln`,无需 handler 显式调 `ui.error` 用户也能看到失败 |
| Idle 守护规则 | `dispatchSlash` 在调用 handler 前根据 Kind 判定：`Kind.UI` 与 `Kind.PROMPT` 命令在非 idle 状态拒绝（直接 errorBlock 提示）；`Kind.LOCAL` 命令任何状态都可执行 | handler 不再单独检查 `ui.idle()` |
| Ui 与 GUI 线程衔接 | 通过 `dinocodeModel` 内部 `pendingAction: Runnable` 字段缓冲,handler 返回后用 `program.send(new AgentEventMessage(pendingAction)` | Ui 接口方法保持无返回值,handler 写线性代码；`dispatchSlash` 在 handler 返回后调度到 GUI 线程 |
| Kind 与"是否进 history" | `Kind.PROMPT` = 调 `injectAndSend`；Kind 仅是元数据,实际行为靠 handler 主动调用 | 避免把"是否注入"做成隐式行为；由 handler 显式表达意图,可读 |
| 别名匹配范围 | dispatcher 命中（主名 + 别名都进 byName）；补全菜单仅按主名前缀 | 别名是输入快捷,补全是发现机制,语义不同；本期 12 条命令暂不填别名 |
| 补全菜单实现 | 自实现 inline 渲染（JLine/tui.tea `Label` 多行）,不用 JLine/tui.tea 内置 `ActionListBox` 弹出 | `ActionListBox` 弹出 Window 占满主屏与产品图不符；inline 多行字符串渲染足够 |
| 补全菜单激活条件 | TextBox 首字符为 `/` | 简单可靠；空输入或非 `/` 开头都不弹 |
| 补全菜单键位归属 | active 时 ↑/↓/Tab/⏎/ESC 都被消费；关闭时透传 TextBox | 用户在菜单激活时不会期望普通编辑；关闭后所有键回到 TextBox |
| 老命令收编 | 一次性把 5 条旧 handler 重写为基于 `Ui` 接口的新 handler；不保留过渡 | 双轨维护成本高于一次性重写 |
| /resume 状态机 | `Ui.openResumeMenu` 内部仍由 tui 包持有 `SessionState` 与 `ActionListBox` | 避免 command 包知道 JLine/tui.tea 类型；ch09 行为完全保留 |
| /clear 实现 | close 旧 writer → 用 `ContextCompactor.newSessionContext` 构造新 `SessionContext` → 用 `Session.openWriter` 打开新 writer → 重新构造 `new Conversation(onAppend, onReplace)`（onAppend 闭包重新捕获新 writer）→ `runtime.resetForNewSession(...)` → `iter=0; usageIn=0; usageOut=0` | 旧 writer 关闭后其 hook 已失效,必须重建 conversation 才能挂上新 writer；旧 JSONL 文件保留,/resume 仍能看到 |
| /memory 数据源 | `Ui.memoryFiles()` 由 `dinocodeModel` 委托给已有的 `memoryManager` | 不重做记忆加载,只新增"列文件名"查询路径 |
| /status 字段渲染 | 6 行 key:value 两列对齐（key 用 `String.format("%-10s", ...)` 右补空格）；Mode 用 `Mode.toString()`；`modelName` 来源为 `provider.model()`（provider 为 null 时返回空串）,与 status bar 取 model name 的来源一致,不读 engine | `Mode.toString()` 已是 camelCase（default/plan/acceptEdits/bypassPermissions）与设计图一致 |
| toolCount 数据源 | `Ui.toolCount()` 由 `dinocodeModel` 委托给 `toolRegistry.count()`,即 `ToolRegistry` 已有（若不存在则本期新增）的 O(1) 方法 | 与 `cmdRegistry` 字段无关,二者并存 |
| 未命中提示文本 | "未知命令: /<name>。输入 /help 查看可用命令" | 唯一硬编码字符串；集中在 `Commands.java` 中 |
| READY_HINT 处理 | 改为通用引导文案（"准备好了,输入 /help 查看命令"）,不再列具体命令名 | 消除 N7 要求的"硬编码命令清单" |
| 状态栏改动 | 不动 | N11 要求 |