# Agent Loop Plan（dinocode 栈）

> 基于已批准的 Spec.md。本文档针对**实际 dinocode 代码库**：Java 21 + Maven、JDK HttpClient（不引 SDK）、JLine `LineReader`、阻塞式 `EventStream`、顶层包 `dinocode`。
> 取代上一版写给 `com.dinocode`/Gradle/SDK/tui.tea 的 plan——本版可逐字落地。

## 架构概览

ch04 不新增包，在 ch03 的 `tool / agent / provider / prompt / tui` 之上**扩展**，把单轮闭环升级为 ReAct 循环：

- **`dinocode.agent`（重写 `Agent.run`）**：把「请求#1 → 执行 → 请求#2 → 停」改为 `for` 迭代——每轮带工具发请求 → 流式收集 → 有工具则执行并回灌进入下一轮，无工具则纯文本即最终答复。新增保序分批并发执行、迭代/用量/通知事件、多种停止条件、终止时历史一致性收尾、`Mode.NORMAL/PLAN`。
- **`dinocode.tool`（扩展）**：`Tool` 接口加 `boolean readOnly()`；`ToolRegistry` 加 `readOnlyDefinitions()` / `isReadOnly(name)`。
- **`dinocode.provider`（扩展）**：`ChatRequest` 加 `String systemSuffix`（Plan Mode 系统提示后缀）；两适配器把 `suffix` 拼到内置系统提示后。用量无需新类型——`ChatEvent.Done` 已携带 `Usage`。
- **`dinocode.prompt`（扩展）**：加 `PLAN_MODE_REMINDER` / `EXECUTE_DIRECTIVE`；`SYSTEM_PROMPT` 增补「持续用工具推进直到完成」的循环约定。
- **`dinocode.tui`（扩展）**：识别 `/plan` `/do`；引入 per-turn 取消；渲染迭代轮次、会话累计用量、并发多工具行；按键拆分 Esc / Ctrl+C；状态栏显示模式徽标与累计用量。

依赖方向不变、无环：`tool → core`；`agent → {core, tool, provider, prompt}`；`provider → {core, config, prompt}`；`tui → {agent, core, tool, provider, session, config}`。

## 核心数据结构

全部基于 ch03，只做增量。包 `dinocode`（顶层）。

### tool 包（只读分类）

```java
public interface Tool {
    String name();
    String description();
    Map<String, Object> schema();
    boolean readOnly();                       // 新增：true=只读（可并发 & Plan Mode 放行）
    Result execute(Map<String, Object> args);
}
```

只读分类（语义）：`ReadFileTool`/`GlobTool`/`GrepTool` → `true`；`WriteFileTool`/`EditFileTool`/`BashTool` → `false`（`BashTool` 可执行任意副作用命令，保守归有副作用）。

`ToolRegistry` 新增：

```java
public List<ToolDefinition> readOnlyDefinitions();  // 仅收 readOnly()==true 的项，按注册顺序
public boolean isReadOnly(String name);             // 未知工具 false
```

### provider 包（系统提示后缀）

```java
// ChatRequest 加第 4 字段：systemSuffix（Plan Mode 计划态约束），空串 = 普通模式。
public record ChatRequest(List<Message> history, int maxTokens, List<ToolDefinition> tools, String systemSuffix) {}
```

两适配器 `buildRequest` 内把系统提示由硬编码 `Prompt.SYSTEM_PROMPT` 改为 `effectiveSystem(suffix)`：`suffix` 空时原样；非空时 `Prompt.SYSTEM_PROMPT + "\n\n" + suffix`。

### agent 包（事件扩展 + `run` 重写）

```java
// TurnEvent sealed 新增三个变体（原有 Text/Thinking/ToolStart/ToolEnd/Done/Error 保留）：
record UsageReport(Usage usage) implements TurnEvent {}   // 每轮 stream 结束后一次（F8）
record Iter(int iter) implements TurnEvent {}             // 进入第 iter 轮迭代（F9）
record Notice(String message) implements TurnEvent {}     // 停止原因等提示；仅 UI，不入历史

// Mode：普通 / 计划模式（F10）。
public enum Mode { NORMAL, PLAN }

// CancelToken：per-turn 轻量取消句柄（volatile + 可选 onCancel 回调 + 派生 timeout）。
public final class CancelToken {
    private volatile boolean cancelled;
    private final List<Runnable> onCancel = new ArrayList<>();
    public void cancel();
    public boolean isCancelled();
    public void onCancel(Runnable r);         // 取消时触发（用于关闭底层流）
    public CancelToken withTimeout(Duration d); // 派生：到点自动 cancel，仍受父 token 牵连
}

// Agent.run 签名变更：
public TurnStream run(List<Message> history, int maxTokens, Mode mode, CancelToken cancel);
```

停止/提示常量（内置，不可配）：

```java
final class AgentConstants {
    static final int MAX_ITERATIONS  = 25; // 迭代上限兜底（F2）
    static final int MAX_UNKNOWN_RUN = 3;  // 连续「整轮只产生未知工具调用」上限（F2）

    static final String NOTICE_MAX_ITER      = "(已达最大迭代轮数 25，自动停止；可继续发消息推进。)";
    static final String NOTICE_UNKNOWN_TOOLS = "(连续多轮只请求到未注册的工具，自动停止。)";
    static final String NOTICE_STREAM_ERR    = "(请求出错，本轮已中断。)";
    static final String NOTICE_CANCELLED     = "(已取消。)";
}
```

## 模块设计

### dinocode.tool（扩展）

`Tool` 加 `readOnly()`；6 工具各加一行实现；`ToolRegistry.readOnlyDefinitions()` 仿 `definitions()` 仅收只读项；`isReadOnly(name)` 用 `get(name).filter(Tool::readOnly)` 判断。执行逻辑、超时不变。

### dinocode.prompt（扩展）

- `SYSTEM_PROMPT` 增补循环约定：「持续调用工具推进任务，直到任务完成后给出最终简洁答复，不要每步都停下等用户」。
- `PLAN_MODE_REMINDER`：计划态系统后缀——当前为计划模式，只能用只读工具（读文件 / 按模式找文件 / 搜内容）调研并产出分步计划，不得写/改文件或执行命令，计划写完即停等 `/do`。
- `EXECUTE_DIRECTIVE = "请按上面的计划开始执行。"`（`/do` 注入的用户消息）。

### dinocode.provider（扩展）

`ChatRequest` 加 `systemSuffix`；两适配器：
- `OpenAiProvider`：首条 system 消息 `content` 由 `Prompt.SYSTEM_PROMPT` 改为 `effectiveSystem(suffix)`。`stream_options.include_usage` 已在 ch03 打开，`Done.usage` 已有用量。
- `AnthropicProvider`：`body.put("system", effectiveSystem(suffix))`。`message_start`/`message_delta` 已累积用量到 `Done.usage`。

用量上抛：无需新类型——`Agent.streamOnce` 从 `ChatEvent.Done` 的 `usage` 拿到本轮用量，emit `TurnEvent.UsageReport`。

### dinocode.agent（重写 `run`）

**职责**：ReAct 循环（F1/F2）、保序分批并发（F5）、事件流（F3/F8/F9）、终止历史一致（F6）、Plan/Normal（F10）。

**`run(history, maxTokens, mode, cancel)` 算法（virtual thread 内，`LinkedBlockingQueue<TurnEvent>` 输出）：**

1. 按 `mode` 取工具集与后缀：
   - `PLAN` → `defs = registry.readOnlyDefinitions()`、`suffix = Prompt.PLAN_MODE_REMINDER`。
   - `NORMAL` → `defs = registry.definitions()`、`suffix = ""`。
2. `int unknownRun = 0;`
3. `for (int iter = 1; iter <= MAX_ITERATIONS; iter++)`：
   1. `emit(new TurnEvent.Iter(iter))`；若 `cancel.isCancelled()` → 收尾、停。
   2. `StreamOutcome out = streamOnce(history, maxTokens, defs, suffix, cancel, queue)`——本轮流式：转发 `Text`/`Thinking`，收集 `ToolCallComplete`，记录 `Done.usage`；`Failure` 时发 `TurnEvent.Error` 并返回 `failed=true`。
   3. 若 `out.failed`：`cancel.isCancelled()` → 收尾 `NOTICE_CANCELLED`；否则收尾 `NOTICE_STREAM_ERR`；停。
   4. `emit(new TurnEvent.UsageReport(out.usage()))`（usage 可能 UNKNOWN）。
   5. **无工具**（`out.calls().isEmpty()`）：`history.add(Message.assistant(ensureFinal(out.text())))`；`emit(TurnEvent.Done(usage))`；停（自然完成，F2-1）。
   6. **有工具**：`history.add(Message.assistantWithTools(out.text(), out.calls()))`。
   7. 统计未知工具：`allUnknown(out.calls())` 则 `unknownRun++`，否则归零。
   8. `BatchOutcome batch = executeBatched(out.calls(), cancel, queue)`（保序分批并发，F5）。
   9. `history.add(Message.tool(batch.results()))`（无论取消都回灌，含已取消占位，F6）。
   10. 若 `!batch.completed()`（执行中被取消）→ `emit(Notice(NOTICE_CANCELLED))`、`ensureAssistantTail`、停（最高优先级）。
   11. 若 `unknownRun >= MAX_UNKNOWN_RUN` → `emit(Notice(NOTICE_UNKNOWN_TOOLS))`、`ensureAssistantTail`、`emit(Done)`、停（F2-4）。
4. 循环走完（触达上限）：`emit(Notice(NOTICE_MAX_ITER))`、`ensureAssistantTail`、`emit(Done)`（F2-2）。

**`streamOnce(...) → StreamOutcome(text, calls, usage, failed)`**：`ChatRequest(history, maxTokens, defs, suffix)` → `provider.chat(request)` 阻塞 `EventStream`；循环 `next()` 按 `ChatEvent` 分派：`TextDelta`→累积 + emit `TurnEvent.Text`、`ThinkingDelta`→emit `TurnEvent.Thinking`、`ToolCallComplete`→收集、`Done`→记录 usage、`Failure`→emit `TurnEvent.Error` 并标记 failed。`cancel` 在每事件后检查。

**`executeBatched(calls, cancel, queue) → BatchOutcome(results, completed)`**：保序分批（F5）。`ToolResult[] results = new ToolResult[calls.size()]`，从 `i=0` 扫描：
- 当前只读 → 吃最长连续只读区间 `[i, j)`，**并发**执行：每个调用一个 virtual thread，用 `cancel.withTimeout(ToolRegistry.DEFAULT_TIMEOUT)` 派生 token，执行结果写**自己下标** `results[k]`（互不重叠，无锁），`CountDownLatch(j-i)` 汇合；`i = j`。
- 当前非只读 → **串行**执行单个 `results[i]`；`i++`。
- 事件顺序：**Start 按序、End 按序**——并发批先按序 emit 每个 `ToolStart`，执行完再按序 emit 每个 `ToolEnd`；并发只发生在执行环节，事件始终是调用序（N3）。
- 每段执行前判 `cancel.isCancelled()`：给未执行的 call 填「已取消」结果，`return completed=false`。

**辅助函数：**
- `emit(queue, event)`：`if (cancel.isCancelled()) return false; queue.put(event); return true;`。
- `allUnknown(calls)`：每个 call 用 `registry.get(name)` 判，**全部**未注册才 true；混入任一已注册即 false（计数重置）。
- `ensureFinal(text)`：非空原样；空则返回占位提示（避免空 assistant 回合）。
- `ensureAssistantTail(history, fallback)`：若 `history` 末尾不是 `Role.ASSISTANT`，`history.add(Message.assistant(fallback))`（F6 角色交替）。
- `lastRole(history)`：空返回 `Optional.empty()`，否则末条 `role()`（历史是 `List<Message>`，无需 `Conversation` 类）。

**取消（F7）**：`TurnStream.close()`（TUI 中断触发）→ `cancel.cancel()` + 关闭当前底层 `EventStream`。`run` 主流程在每轮边界与每个流事件后检查 `cancel.isCancelled()`；`executeBatched` 内用派生 token 让正在执行的工具尽快返回。正在执行的工具靠 cancel flag / `Future.cancel(true)` 尽力而为（不做硬性杀进程，沿用 ch03「工具取消尽力而为」）。

### dinocode.tui（扩展）

- `Tui` 加字段：`Mode mode = Mode.NORMAL`、`int iter`、`long usageIn`/`usageOut`、`List<ToolDisplay> curTools`（替换单个）、`CancelToken turnCancel`。
- `turn()` / 命令分发：识别 `/plan`（`mode=PLAN`、提示、回空闲）、`/do`（`mode=NORMAL`、`addUser(EXECUTE_DIRECTIVE)`、启动 Loop）；普通文本 `addUser(text)`。启动处 `new Agent(provider, registry).run(history, maxTokens, mode, turnCancel)`。
- `TurnEvent` 分派：`Iter`→`iter`；`UsageReport`→累加 `usageIn/usageOut`；`Notice`→灰色提示；`ToolStart`→`curTools.add`；`ToolEnd`→按序落工具行+摘要；`Done`→最终答复+`finishTurn`。
- 按键：流式态 Esc / Ctrl+C → `turnCancel.cancel()`（不退出）；空闲态 Ctrl+C → 退出。
- `Renderer`：状态栏模式徽标（PLAN）+ 累计用量 `↑in ↓out tok`；动态区多工具行 `● name(args)` Running…，否则「thinking… 第 N 轮」。

## 模块交互

```
用户提交 /do 或文本
  └─ Tui.turn: /plan→mode=PLAN；/do→mode=NORMAL + addUser(EXECUTE_DIRECTIVE)；文本→addUser(text)
       └─ turnCancel = new CancelToken()
          new Agent(provider, registry).run(history, maxTokens, mode, turnCancel)  [virtual thread]
            for iter = 1..MAX_ITERATIONS:
              ├─ emit Iter(iter)
              ├─ provider.chat(ChatRequest(history, maxTokens, defs(mode), suffix(mode)))
              │     └─ 适配器: 注入 tools + (SYSTEM_PROMPT+suffix) → 流式 → Text/Thinking/ToolCallComplete/Done(usage)
              ├─ emit UsageReport(usage)
              ├─ 无 calls → addAssistant(final); emit Done; 停
              └─ 有 calls:
                   ├─ addAssistantWithTools(preamble, calls)
                   ├─ executeBatched: 连续只读并发 / 有副作用串行（Start按序→执行→End按序）
                   ├─ addToolResults(results)
                   └─ 下一轮 iter
  └─ Tui 按 TurnEvent 渲染（正文/工具行/轮次/用量/通知）
  └─ Esc/Ctrl+C(streaming) → turnCancel.cancel() → run 收尾历史 → TurnStream 结束 → 回空闲
```

并发：`history` 任一时刻只被 `run` 主 virtual thread 触碰；执行批 worker 只写各自下标 `results[k]`；`addToolResults` 在 `CountDownLatch.await()` 后串行；TUI 只读事件渲染（N2/N6）。

## 文件组织

```
dinocode/（顶层包，src/main/java/dinocode）
├── tool/
│   ├── Tool.java                                  — 修改：接口加 readOnly()
│   ├── ToolRegistry.java                          — 修改：readOnlyDefinitions()、isReadOnly()
│   └── {ReadFile,WriteFile,EditFile,Bash,Glob,Grep}Tool.java — 修改：各加 readOnly()
├── prompt/Prompt.java                             — 修改：PLAN_MODE_REMINDER、EXECUTE_DIRECTIVE、SYSTEM_PROMPT 循环约定
├── provider/ChatRequest.java                      — 修改：加 systemSuffix 字段
├── provider/{OpenAiProvider,AnthropicProvider}.java — 修改：effectiveSystem(suffix)
├── agent/
│   ├── Mode.java                                  — 新建：enum {NORMAL, PLAN}
│   ├── CancelToken.java                           — 新建：per-turn 取消句柄
│   ├── TurnEvent.java                             — 修改：加 UsageReport/Iter/Notice
│   ├── Agent.java                                 — 重写：ReAct 循环 + executeBatched + 停止条件 + 历史收尾
│   └── AgentConstants.java                        — 新建：上限/文案常量
└── tui/
    ├── Tui.java                                   — 修改：mode/iter/usage/curTools/turnCancel、/plan /do、按键拆分
    └── Renderer.java                              — 修改：状态栏徽标+累计用量、多工具行、轮次
```

测试：
```
src/test/java/dinocode/
├── agent/AgentTest.java                           — 重写：多轮、分批并发、停止条件、Plan 工具集
└── tool/ToolRegistryTest.java                     — 扩展：readOnlyDefinitions/isReadOnly 断言
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| Loop 放哪 | 重写 `Agent.run` 为 `for` 循环，签名加 `mode`/`cancel` | 循环编排属 agent 包；TUI 维持纯渲染器。ch03 的两段式 `streamOnce` 推广为循环即可 |
| 用量 | 复用 `ChatEvent.Done.usage`，`streamOnce` 取出后 emit `TurnEvent.UsageReport` | ch03 已在 `Done` 携带 usage（`stream_options.include_usage` / Anthropic `message_delta`），无需新 llm 类型 |
| 系统后缀 | `ChatRequest` 加 `String systemSuffix`，适配器 `effectiveSystem(suffix)` 拼到内置提示后 | 系统提示在适配器内注入，计划态约束必须穿过 `chat`；加一个字符串形参最小且显式 |
| 只读分类 | `Tool.readOnly()`；`BashTool` 归有副作用 | read/glob/grep 无副作用；bash 可含任意写，保守串行 |
| 并发分批粒度 | 「连续只读」合批并发，有副作用单个串行，保持调用序 | spec「保序分批」：read 之后的 write 不被提前；相邻只读才并发加速 |
| 并发原语 | virtual thread + `CountDownLatch` + 每 worker 独占下标 | 只写自己 `results[k]` 无需锁；`CountDownLatch` 主流程汇合（N6） |
| 事件顺序 | Start 按序、End 按序，并发只在执行环节 | N3：scrollback 工具行顺序恒等于模型调用序 |
| 取消 | per-turn `CancelToken`；Esc/Ctrl+C(streaming) 取消、Ctrl+C(idle) 退出 | 表达「取消本轮但不退程序」；工具与流阻塞处轮询 / `Future.cancel` |
| 取消后历史一致 | 已发起工具补「已取消」结果 + `ensureAssistantTail` | F6：补齐 tool_result + assistant 尾巴，下一轮不 400 |
| 停止条件之未知工具 | 连续 `MAX_UNKNOWN_RUN=3` 轮「整轮全未知」即停 | 单次未知靠结构化错误回灌纠偏；连续多轮全错才对幻觉工具空转兜底 |
| 迭代上限 | `MAX_ITERATIONS=25` 内置常量 | 兜底安全网，spec 不配置化 |
| Plan 工具集 | 计划态只注入 `readOnlyDefinitions()` | 物理上不给模型写/执行工具，提示被忽略也无法改动 |
| `/do` 语义 | 切回 Normal + 注入 `EXECUTE_DIRECTIVE` + 立即启动 | `/do` 不入历史，执行指令作为用户消息驱动模型开干 |
| 模式状态 | 存于 `Tui.mode`，不进历史 | 历史是 `List<Message>`，放不住可变模式；模式是会话级 UI 状态 |
| lastRole | 内联 `history` 末条判断，不复用死代码 `Conversation` | ch03 已确认 `Conversation` 无引用，历史即 `List<Message>` |
