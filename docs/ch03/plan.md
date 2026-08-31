# 工具系统 Plan（dinocode 栈）

> 基于已批准的 spec.md（docs/ch03/Spec.md，F0–F9 / N1–N6 / AC1–AC13）。
> 本文档针对**实际 dinocode 代码库**：Java 21 + Maven、JDK HttpClient（不引官方 SDK）、JLine `LineReader`、阻塞式 `EventStream`、包名 `dinocode`（顶层，非 `com.dinocode`）。
> 取代上一版写给 `com.mewcode`/Gradle/SDK/tui.tea 的 plan——本版可逐字落地。

## 架构概览

在 ch02 的 `config / core / provider / session / tui` 五模块之上，新增两个包、扩展四处：

- **`dinocode.tool`（新建）**：工具抽象 `Tool`、执行结果 `Result`、注册中心 `ToolRegistry`、6 个核心工具。零协议依赖（仅 JDK + Jackson），不感知 LLM 协议。
- **`dinocode.agent`（新建）**：单轮闭环编排 `Agent`——请求#1（带工具）→ 收集工具调用 → 执行 → 结果回灌 → 请求#2（续答）→ 最终文本 → 停。对外吐阻塞式 `TurnEvent` 流供 TUI 渲染；只依赖 `core`/`tool`/`provider`，可无 UI 单测（AC8/AC9）。
- **`dinocode.core`（扩展）**：`Role` 增 `TOOL`；`Message` 增 `toolCalls`/`toolResults`；新增 `ToolCall`/`ToolResult`/`ToolDefinition` 记录；`ChatEvent` 增 `ToolCallComplete` 变体。
- **`dinocode.provider`（扩展）**：新增 `AnthropicProvider`（双协议）、重加 `ProviderFactory`；`ChatRequest` 增 `tools`；两适配器注入工具定义、解析流式工具调用、序列化 tool_use/tool_result 回灌。
- **`dinocode.config`（扩展）**：`AppConfig` 重加 `protocol` 字段 + `DINO_PROTOCOL` 环境变量；重加 `ThinkingConfig`（Anthropic extended thinking，含禁用态）。
- **`dinocode.tui`（扩展）**：`turn()` 改走 `Agent`；`Renderer` 增工具行与结果摘要渲染。

依赖方向（无环）：`tool → core`；`agent → {core, tool, provider}`；`provider → {core, config}`；`tui → {agent, core, tool, provider, session, config}`；`Main` 负责组装全部。

## 核心数据结构

全部协议无关，放 `dinocode.core`（除 `tool.Result`）。

```java
// Role 增 TOOL：工具结果回合。
public enum Role { USER, ASSISTANT, TOOL }

// ToolCall：模型发起的一次工具调用（流式拼接完成后）。arguments 为完整 JSON 字符串。
public record ToolCall(String id, String name, String arguments) {}

// ToolResult：message 级工具结果（关联 toolCallId，供两协议回灌）。
public record ToolResult(String toolCallId, String content, boolean isError) {}

// ToolDefinition：注册中心导出的协议无关工具定义（F1/F3/AC1）。
public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {}

// Message：assistant 回合可带 toolCalls；TOOL 回合带 toolResults（一条可含多个）。
public record Message(
        Role role,
        String content,
        List<ToolCall> toolCalls,
        List<ToolResult> toolResults) {
    public static Message user(String text)                    { ... }
    public static Message assistant(String text)               { ... }
    public static Message assistantWithTools(String text, List<ToolCall> calls) { ... }
    public static Message tool(List<ToolResult> results)       { ... }
}

// ChatEvent：sealed 增 ToolCallComplete 变体（在 Done 之前发出）。
public sealed interface ChatEvent {
    record ThinkingDelta(String text) implements ChatEvent {}
    record TextDelta(String text) implements ChatEvent {}
    record ToolCallComplete(ToolCall call) implements ChatEvent {}   // 新增
    record Done(Usage usage) implements ChatEvent {}
    record Failure(ErrorKind kind, String message) implements ChatEvent {}
}
```

`ChatRequest` 增 `tools`：

```java
public record ChatRequest(List<Message> history, int maxTokens, List<ToolDefinition> tools) {}
// tools 为空 = 本次不带工具。续答（请求#2）仍带 tools，但 Agent 忽略再次返回的工具调用（AC9）。
```

### tool 包

```java
// Result：工具执行结果——永远以值返回，从不抛异常（F9/N4）。
public record Result(String content, boolean isError) {
    public static Result ok(String content)    { return new Result(content, false); }
    public static Result error(String content) { return new Result(content, true); }
}

// Tool：统一工具抽象（F1）。
public interface Tool {
    String name();
    String description();
    Map<String, Object> schema();               // 手写 JSON Schema（LinkedHashMap 保序）
    Result execute(Map<String, Object> args);   // 解析/IO 失败一律包成 Result.error
}

// ToolRegistry：集中登记、按名查找、导出定义、按名执行（F1/F3/F5/F9）。
public final class ToolRegistry {
    private final List<String> order;                    // 保持注册顺序，导出稳定
    private final Map<String, Tool> tools;
    public void register(Tool t);
    public Optional<Tool> get(String name);
    public List<ToolDefinition> definitions();           // 按 order 导出（AC1）
    public Result execute(String name, Map<String, Object> args); // 未知工具兜底为 error
    public static ToolRegistry createDefault();          // 注册 6 个工具
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30); // N1，不可配
}
```

六个工具（`execute` 内用 Jackson 把 `args` 反序列化到私有入参 record，解析失败转 `Result.error`）：

| 工具名 | 参数（JSON Schema） | 成功结果 | 错误结果 |
|--------|--------------------|---------|---------|
| `ReadFile` | `path`(必) | 带行号文本（`%6d\t`，≤2000 行 / ≤256KB，超出标 `[truncated]`） | 不存在/不可读/是目录 |
| `WriteFile` | `path`、`content`(必) | 建父目录后覆盖写，返回路径与字节数 | 写入失败 |
| `EditFile` | `path`、`old_string`、`new_string`(必) | 出现次数==1 时唯一替换写回 | 0 处→「未找到匹配」；>1 处→「匹配到 N 处，old_string 不唯一…」 |
| `Bash` | `command`(必) | 按平台 `sh -c`/`cmd /C` + `Process.waitFor(timeout)`；返回 stdout/stderr/exit_code（截断 ~30000 字符） | 超时（isError）；非零退出按结果回灌 |
| `Glob` | `pattern`(必)、`path`(可选，默认 cwd) | 匹配路径列表（≤100，排序） | 无匹配返回说明（非 isError） |
| `Grep` | `pattern`(必，Java `Pattern`)、`path`/`glob`(可选) | `file:line:content` 列表（≤100，超出标注） | 正则非法（isError）；无命中返回说明（非 isError） |

### agent 包

```java
// TurnEvent：单轮闭环对外事件流（sealed，TUI 按变体渲染）。比 ChatEvent 高一层，含工具行语义。
public sealed interface TurnEvent {
    record Text(String delta) implements TurnEvent {}          // 正文增量（preamble 或最终答复）
    record Thinking(String delta) implements TurnEvent {}      // 思考增量
    record ToolStart(String name, String argsPreview) implements TurnEvent {}   // 工具行：开始
    record ToolEnd(String name, String summary, boolean isError) implements TurnEvent {} // 工具行：结果摘要
    record Done(Usage usage) implements TurnEvent {}
    record Error(String message) implements TurnEvent {}
}

// TurnStream：阻塞式逐个拉取（对齐 provider.EventStream 风格），close() 中断本轮。
public interface TurnStream extends AutoCloseable {
    TurnEvent next();    // 阻塞；结束返回 null
    @Override void close();  // 关底层流 + 取消工具执行
}

// Agent：单轮闭环编排（F5/F6），保证 AC9 单轮上限。
public final class Agent {
    public Agent(ChatProvider provider, ToolRegistry registry);
    public TurnStream run(List<Message> history, int maxTokens);
}
```

## 模块设计

### dinocode.tool（新建）

**职责**：6 个工具的统一抽象与执行；集中登记/导出/按名执行；所有失败包成 `Result.error`（F1/F2/F9/N4）。
**对外接口**：`Tool`、`Result`、`ToolRegistry`、`ToolRegistry.createDefault()`。
**依赖**：JDK（`java.nio.file`/`java.util.regex`/`ProcessBuilder`/`java.util.concurrent`）、Jackson（参数 JSON 解析，沿用现有依赖）、`dinocode.core.ToolDefinition`。
**关键实现点**：
- Schema 手写为 `LinkedHashMap<String,Object>` 保序（`type`/`properties`/`required`）。
- `ReadFile`：`Files.readString` 后按 `\n` 切分加行号，经 `Truncate.byLinesAndBytes(s, 2000, 256*1024)`；`Files.isDirectory`/不存在/不可读 → error。
- `EditFile`：`content.split(Pattern.quote(old), -1).length - 1` 统计次数，0/1/>1 三分支文案不同（AC4）。
- `Bash`：按 `os.name` 选 `sh -c`/`cmd /C`，`redirectErrorStream(true)`，stdout 用单独 virtual thread 异步读避免管道阻塞，`waitFor(DEFAULT_TIMEOUT)` 超时 → `destroyForcibly()` + error。
- `Glob`：`Files.walk` + 自实现 `**` 跨层级段匹配（`PathMatcher` 的 `glob:**` 仅单层，需自写），收集 ≤100 排序。
- `Grep`：`Pattern.compile` 失败 → error；`BufferedReader` 限行长（1MB）逐行扫，`matcher.find()` 命中收集 `file:line:content` ≤100。
- 空 `args`（OpenAI 可能给空串）按 `"{}"` 归一，避免误报参数错误。

### dinocode.agent（新建）

**职责**：单轮闭环编排，保证 AC9 单轮上限；把 provider 的 `ChatEvent` 与工具执行翻译成统一 `TurnEvent` 流。
**对外接口**：`Agent`、`TurnEvent`、`TurnStream`。
**依赖**：`core`、`tool`、`provider`、JDK `Executors`/`Future`。
**run 算法（同步，在调用方线程顺序执行）**：
1. `var defs = registry.definitions();`
2. **请求#1**：`provider.chat(new ChatRequest(history, maxTokens, defs))` → 循环 `next()`：`TextDelta`→`TurnEvent.Text`、`ThinkingDelta`→`TurnEvent.Thinking`、累积 preamble 文本、收集 `ToolCallComplete`；`Done`/`Failure` 处理同上。
3. 无工具调用 → `history.add(Message.assistant(preamble))`，发 `TurnEvent.Done`，结束（纯文本回合，等价 ch02）。
4. 有工具调用 → `history.add(Message.assistantWithTools(preamble, calls))`。
5. 顺序执行每个 call：发 `TurnEvent.ToolStart(name, argsPreview)` → 在 virtual thread 上 `registry.execute(name, args)` 并 `Future.get(DEFAULT_TIMEOUT)`，超时 `Thread.interrupt()` + `Result.error("工具执行超时")` → 发 `TurnEvent.ToolEnd(name, summary, isError)` → 收集 `core.ToolResult(toolCallId, content, isError)`。
6. `history.add(Message.tool(results))`。
7. **请求#2**：再 `provider.chat(new ChatRequest(history, maxTokens, defs))` → 转发最终答复 `TurnEvent.Text`、累积 final 文本；**忽略**任何 `ToolCallComplete`（AC9）。
8. `history.add(Message.assistant(final))`，发 `TurnEvent.Done`。
- `close()`：置中断标志，关闭当前底层 `EventStream`，`Future.cancel(true)` 工具执行；`next()` 静默返回 null。

### dinocode.provider（扩展）

**职责**：协议无关请求/响应抽象 + 双协议工具调用全流程（F0/F3/F4/F6/F7）。

**ChatProvider / AbstractHttpProvider**：`ChatRequest` 增 `tools` 后，`AbstractHttpProvider.chat` 不变（仍发请求、映射非 2xx、交给子类 `streamFrom`）。

**AnthropicProvider（新建，复用 AbstractHttpProvider + SseReader）**：
- 请求：`POST {base}/v1/messages`，头 `x-api-key` + `anthropic-version: 2023-06-01`；body 含 `model`/`max_tokens`/`messages`/`stream:true`；`tools` 映射为 `[{name, description, input_schema}]`；`thinking` 开启时附 `{"type":"enabled","budget_tokens":N}`（见 thinking 决策）。
- 流式解析：`SseReader` 按 `event:`/`data:` 拆事件；`ping` 忽略；`content_block_start`（`type=="tool_use"`）记录 `id`+`name`；`content_block_delta` 的 `input_json_delta.partial_json` 累积到该 block 的 JSON 缓冲，`text_delta`/`thinking_delta` 分别上抛 `TextDelta`/`ThinkingDelta`；`message_delta` 累积 output_tokens；`message_stop` 时若累积了 tool_use → 逐个发 `ToolCallComplete(id, name, argsJson)` 再发 `Done(usage)`；`error` → `ApiErrors.fromStreamError`。
- 回灌序列化（`messages` 组装）：assistant 有 toolCalls → content 为 `[text(若有), {type:tool_use,id,name,input}, ...]`；`Role.TOOL` 消息 → 一条 `role:"user"` 消息，content 为 `[{type:tool_result, tool_use_id, content, is_error}, ...]`（Anthropic 要求 tool_result 由 user 角色提交）。

**OpenAIProvider（扩展）**：
- 请求：`POST {base}/chat/completions`；body 增 `tools: [{type:"function", function:{name,description,parameters}}]`（由 `ToolDefinition.inputSchema` 直接作为 parameters）。
- 流式解析：在现有 `OpenAiEventStream` 内，除 `delta.content`/`reasoning_content` 外，累积 `delta.tool_calls`（按 `index` 分组：首片带 `id`+`function.name`，后续片拼 `function.arguments`）；`[DONE]` 时若累积了工具调用 → 逐个发 `ToolCallComplete`（`arguments` 空串归一 `"{}"`）再发 `Done(usage)`。
- 回灌序列化：assistant 有 toolCalls → 手工构造含 `tool_calls` 数组的 assistant 消息；`Role.TOOL` 消息 → 每个结果一条 `role:"tool"`、`tool_call_id`+`content` 消息。

**ProviderFactory（重加）**：`AppConfig.protocol()` → `"anthropic"`/`"openai"` 映射到对应 Provider。

### dinocode.config（扩展）

- `AppConfig` 增 `protocol` 字段（默认 `"openai"`）+ 常量 `PROTOCOL_OPENAI`/`PROTOCOL_ANTHROPIC`/`ENV_PROTOCOL="DINO_PROTOCOL"`；重加 `ThinkingConfig`（`enabled`/`budgetTokens`，默认禁用、budget 2048）。
- `ConfigLoader`：解析 `DINO_PROTOCOL`（默认 openai，非法值报配置错误）；`base_url` 缺省按协议给默认（openai → `https://api.openai.com/v1`，anthropic → `https://api.anthropic.com`）；解析 thinking 配置。

### dinocode.tui（扩展）

- `Tui.turn()` 重构：`history.add(Message.user(text))` → `new Agent(provider, registry).run(history, maxTokens)` 返回 `TurnStream`，循环 `next()` 按 `TurnEvent` 变体渲染：`Text`→`renderer.text`、`Thinking`→`renderer.thinking`、`ToolStart`→记录当前工具（执行指示）、`ToolEnd`→`renderer.toolLine`+`renderer.toolSummary`、`Done`→`renderer.done`、`Error`→`renderer.failure`。
- `Renderer` 增：`toolLine(name, argsPreview)`（绿色 `● name(args)`）、`toolSummary(summary, isError)`（缩进 `  ⎿ `、灰/红，截断 ~8 行）。
- Ctrl+C 语义沿用 ch02：SIGINT → `turnStream.close()`。

## 模块交互

```
用户提交
  └─ Tui.turn(): history.add(Message.user(text))
       └─ Agent.run(history, maxTokens)  [同步阻塞]
            ├─ 请求#1: provider.chat(ChatRequest(history, maxTokens, registry.definitions()))
            │     └─ 适配器: 注入 tools → 流式解析 → TextDelta/ThinkingDelta/ToolCallComplete/Done
            │     → Agent 转发 TurnEvent.Text/Thinking，累积 preamble、收集 calls
            ├─ 无 calls → history.add(assistant(preamble)); TurnEvent.Done
            └─ 有 calls:
                 ├─ history.add(assistantWithTools(preamble, calls))
                 ├─ for call: TurnEvent.ToolStart → virtual-thread 执行 + Future.get(30s) → TurnEvent.ToolEnd
                 ├─ history.add(Message.tool(results))
                 ├─ 请求#2: provider.chat(...) → TurnEvent.Text(最终答复)，忽略二轮 ToolCallComplete
                 └─ history.add(assistant(final)); TurnEvent.Done
  └─ Tui 按 TurnEvent 渲染（正文区 / 工具行 / 结果摘要 / 用量）
```

并发：`Agent.run` 同步独占 `history`（`submit` 交 `addUser` 后不再触碰）；工具执行在 virtual thread、`Future.get(timeout)` 等待；`Tui` 只读 `TurnEvent` 渲染，互不干扰（N2）。

## 文件组织

```
dinocode/                                        （顶层包，src/main/java/dinocode）
├── Main.java                                     — 修改：ProviderFactory 建 Provider、ToolRegistry.createDefault()、注入 Tui
├── core/
│   ├── Message.java                              — 修改：增 toolCalls/toolResults + 便捷工厂
│   ├── Role.java                                 — 修改：增 TOOL
│   ├── ChatEvent.java                            — 修改：sealed 增 ToolCallComplete
│   ├── ToolCall.java / ToolResult.java / ToolDefinition.java — 新建：record
│   └── (Conversation.java 死代码，不动或删)
├── tool/                                         — 新建
│   ├── Tool.java / Result.java / ToolRegistry.java / Truncate.java
│   └── ReadFileTool / WriteFileTool / EditFileTool / BashTool / GlobTool / GrepTool .java
├── agent/                                        — 新建
│   └── Agent.java / TurnEvent.java / TurnStream.java
├── provider/
│   ├── ChatRequest.java                          — 修改：增 tools
│   ├── AnthropicProvider.java                    — 新建
│   ├── ProviderFactory.java                      — 新建（重加）
│   ├── OpenAiProvider.java                       — 修改：注入 tools、解析 tool_calls、回灌
│   └── ApiErrors.java                            — 修改：fromStreamError 补回（Anthropic error 事件）
├── config/
│   ├── AppConfig.java                            — 修改：增 protocol + ThinkingConfig 常量
│   ├── ConfigLoader.java                         — 修改：解析 DINO_PROTOCOL / thinking / 协议默认 base_url
│   └── ThinkingConfig.java                       — 新建（重加）
├── tui/
│   ├── Tui.java                                  — 修改：turn() 走 Agent
│   └── Renderer.java                             — 修改：增 toolLine/toolSummary
└── session/ (不变)
```

测试：
```
src/test/java/dinocode/
├── tool/ToolRegistryTest.java                    — 6 工具单测（AC1–AC6、AC13）
├── agent/AgentTest.java                          — fake Provider 驱动单轮闭环（AC8/AC9）
├── provider/AnthropicProviderTest.java           — fixture 回放（AC7、AC10）
└── OfflineStreamTest.java                        — 扩展：mock 端点带工具流（跨协议离线验证）
src/test/resources/fixtures/
├── anthropic-stream.txt                          — 重加
├── anthropic-tool.txt                            — 新建：含 tool_use 的流
└── openai-tool.txt                               — 新建：含 delta.tool_calls 的流
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 工具循环放哪 | 新建 `dinocode.agent.Agent`，TUI 退化为渲染器 | 循环（请求#1→执行→请求#2）无法塞进 ch02 的单次 `provider.chat`；独立包可无 UI 单测 AC8/AC9，只依赖 core/tool/provider |
| 协议接入 | 新增 `AnthropicProvider`（JDK HttpClient 手写 SSE），复用 `AbstractHttpProvider`/`SseReader` | 与 ch02 一致「不引官方 SDK」；Anthropic 曾实现过（git 历史可参考），差异只在事件类型与回灌格式 |
| 工具定义传递 | `ChatRequest` 增 `List<ToolDefinition> tools`（空=不带） | Provider 保持无状态；续答仍带 tools，与真实协议一致 |
| 流式工具参数拼接 | 两适配器各自累积：OpenAI 按 `index` 拼 `delta.tool_calls`；Anthropic 按 block 累积 `input_json_delta.partial_json`；流尾组装 `ToolCallComplete` | 手写 accumulator 处理分片，避免依赖 SDK；工具调用只在流尾（finish_reason/stop_reason）才完整 |
| 工具失败表达 | `Tool.execute` 返回 `Result(content, isError)`，从不抛异常 | F9/N4：失败包成结构化结果回灌，程序不崩 |
| 结果回灌形态 | `Message` 平铺 `toolCalls`/`toolResults`，适配器吸收协议差异 | 两协议工具语义本即 id 关联列表；Anthropic tool_result 进 user 消息、OpenAI 用 tool 角色 |
| 超时 | Agent 用 virtual thread + `Future.get(30s)`；`Bash` 内 `Process.waitFor(timeout)` | 双保险：外层兜住慢工具，内层杀子进程；N1「界面不冻结」 |
| 持久化 | 工具回合（TOOL 消息、assistant.toolCalls）不落盘，`save` 时过滤 | spec「工具调用与结果不落盘，退出即丢」；重载后从纯文本历史继续 |
| 单轮上限 | Agent 忽略续答里再次出现的 `ToolCallComplete` | AC9：不发起第二轮工具执行，连环调用留待下章 |
| thinking 与工具组合 | 历史含工具交互的续答请求不启用 thinking | Anthropic 要求回灌带 thinking 的 assistant 回合附 thinking 块签名，本章不保留签名，关闭以避 400 |
| 空参数归一 | OpenAI 空 `arguments` 归一为 `"{}"` | 无参工具的 arguments 可能为空串，回灌须合法 JSON，否则严格端点 400 |
| 工具命名 | `ReadFile`/`WriteFile`/`EditFile`/`Bash`/`Glob`/`Grep` | 符合 OpenAI 函数名规则，TUI 显示 `● name(args)` |
| 截断 | 工具级上限（read 2000 行/256KB、bash 30000 字符、结果 ≤100），尾部标 `[truncated]`；UI 摘要另截 ~8 行 | N5/AC13 控体量；模型需较完整内容，UI 只需摘要 |
