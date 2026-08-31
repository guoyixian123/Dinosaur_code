# 工具系统 Tasks

> 基于已批准的 spec.md + plan.md。任务有序，每步留绿编译。验证一律「先跑命令看输出，再下结论」。
> 顶层包 `dinocode`（Java 21 / Maven）。命令用 `mvn`（不是 gradle）。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 修改 | `core/Role.java` | 增 `TOOL` |
| 新建 | `core/ToolCall.java` / `core/ToolResult.java` / `core/ToolDefinition.java` | 协议无关 record |
| 修改 | `core/ChatEvent.java` | sealed 增 `ToolCallComplete` |
| 修改 | `core/Message.java` | 4 字段 + 便捷工厂 + Jackson 兼容 |
| 新建 | `tool/Tool.java` / `tool/Result.java` / `tool/Truncate.java` / `tool/ToolRegistry.java` | 工具抽象与注册中心 |
| 新建 | `tool/{ReadFile,WriteFile,EditFile,Bash,Glob,Grep}Tool.java` | 6 个核心工具 |
| 新建 | `config/ThinkingConfig.java` | extended thinking 配置 |
| 修改 | `config/AppConfig.java` / `config/ConfigLoader.java` | 增 `protocol` + `DINO_PROTOCOL` + thinking 解析 |
| 修改 | `provider/ChatRequest.java` | 增 `tools` |
| 新建 | `provider/ProviderFactory.java` | 协议 → Provider 映射 |
| 修改 | `provider/ApiErrors.java` | 补 `fromStreamError`（Anthropic error 事件） |
| 修改 | `provider/OpenAiProvider.java` | 注入 tools、解析 tool_calls、回灌 |
| 新建 | `provider/AnthropicProvider.java` | 双协议：注入/解析/回灌 |
| 新建 | `agent/TurnEvent.java` / `agent/TurnStream.java` / `agent/Agent.java` | 单轮闭环 |
| 修改 | `tui/Tui.java` / `tui/Renderer.java` | turn() 走 Agent、工具行渲染 |
| 修改 | `Main.java` | ProviderFactory + ToolRegistry 注入 |
| 新建 | `test/tool/ToolRegistryTest.java` | 6 工具单测 |
| 新建 | `test/agent/AgentTest.java` | fake Provider 单轮闭环 |
| 新建 | `test/provider/AnthropicProviderTest.java` | fixture 回放 |
| 修改 | `test/OfflineStreamTest.java` | mock 端点带工具流 |
| 新建 | `test/resources/fixtures/{anthropic-stream,anthropic-tool,openai-tool}.txt` | 回放数据 |

---

## T1: core 类型扩展

**文件：** `core/{Role,Message,ChatEvent}.java` + 新建 `core/{ToolCall,ToolResult,ToolDefinition}.java` + `session/SessionStore.java`
**依赖：** 无
**步骤：**
1. `Role` 枚举增 `TOOL("tool")`（wire 值 `"tool"`，会话文件与两协议都用小写）。
2. 新建三个 record：`ToolCall(String id, String name, String arguments)`、`ToolResult(String toolCallId, String content, boolean isError)`、`ToolDefinition(String name, String description, Map<String,Object> inputSchema)`（各带中文注释）。
3. `ChatEvent` sealed 增 `record ToolCallComplete(ToolCall call) implements ChatEvent {}`，permits 列表追加。
4. `Message` 重写为 4 字段 record：`Message(Role role, String content, List<ToolCall> toolCalls, List<ToolResult> toolResults)`；保留 null 校验；加便捷工厂 `user(text)`/`assistant(text)`/`assistantWithTools(text, calls)`/`tool(results)`；加 `@JsonCreator`（`@JsonProperty` 标四个参数，`toolCalls`/`toolResults` 为 null 时默认 `List.of()`，兼容旧会话 JSON）；`toolCalls`/`toolResults` 加 `@JsonIgnore`（工具回合不落盘）。
5. 迁移全部 `new Message(Role.XXX, text)` 调用点改用工厂：`Tui.turn`、`OfflineStreamTest`、`SessionStoreTest` 等。
6. `SessionStore.save` 序列化前过滤 `Role.TOOL` 消息（工具结果不落盘）。

**验证：** `mvn test` 全绿（现有 53 个测试迁移后仍通过）。

## T2: tool 骨架

**文件：** `tool/{Tool,Result,Truncate,ToolRegistry}.java`
**依赖：** T1
**步骤：**
1. `Result`：record `Result(String content, boolean isError)` + 静态 `ok`/`error`。
2. `Tool`：interface `{ String name(); String description(); Map<String,Object> schema(); Result execute(Map<String,Object> args); }`。
3. `Truncate`：`static String byLinesAndBytes(String s, int maxLines, int maxBytes)`，超出尾部加 `\n[truncated]`。
4. `ToolRegistry`：字段 `List<String> order` + `Map<String,Tool> tools`；方法 `register`/`get`/`definitions()`（按 order 导出 `List<ToolDefinition>`）/`execute(name, args)`（未命中返回 `Result.error("未知工具: "+name)`；`args` 为 null/空串归一为 `"{}"` 后反序列化）；常量 `DEFAULT_TIMEOUT=Duration.ofSeconds(30)`。**暂不写** `createDefault()`。

**验证：** `mvn -q test` 编译通过。

## T3: ReadFileTool

**文件：** `tool/ReadFileTool.java`
**依赖：** T2
**步骤：**
1. 私有 record `ReadFileArgs(String path)`；类实现 `Tool`。
2. `schema()`：`type:object`、`properties.path{type:string, description:"要读取的文件路径"}`、`required:["path"]`（`LinkedHashMap`）。
3. `execute`：Jackson 解析参数（空当 `{}`）；`Files.isDirectory`/不存在/`AccessDeniedException` → `Result.error`；成功 `Files.readString` 按 `\n` 切分加行号 `String.format("%6d\t%s", i, line)`，经 `Truncate.byLinesAndBytes(s, 2000, 256*1024)`。

**验证：** `mvn -q test` 编译；T9 后单测覆盖存在/不存在/目录三情形。

## T4: WriteFileTool

**文件：** `tool/WriteFileTool.java`
**依赖：** T2
**步骤：**
1. 私有 record `WriteFileArgs(String path, String content)`；实现 `Tool`。
2. `schema()`：`path`、`content` 均必填。
3. `execute`：`Files.createDirectories(p.getParent())` 后 `Files.writeString(p, content)`（覆盖）；成功返回「已写入 <path>（<N> 字节）」；`IOException` → `Result.error(e.getMessage())`。

**验证：** `mvn -q test` 编译；T9 后单测写嵌套路径检查磁盘。

## T5: EditFileTool

**文件：** `tool/EditFileTool.java`
**依赖：** T2
**步骤：**
1. 私有 record `EditFileArgs(String path, String oldString, String newString)`；实现 `Tool`。
2. `schema()`：三字段必填，描述注明唯一匹配语义。
3. `execute`：读文件失败 isError；`int n = content.split(Pattern.quote(old), -1).length - 1`；`n==0`→`error("未找到匹配的内容")`；`n>1`→`error("匹配到 N 处，old_string 不唯一，请提供更长上下文")`；`n==1`→`content.replace(old, new)` 后 `Files.writeString`，返回成功。

**验证：** `mvn -q test` 编译；T9 后单测覆盖 0/1/多三情形。

## T6: BashTool

**文件：** `tool/BashTool.java`
**依赖：** T2
**步骤：**
1. 私有 record `BashArgs(String command)`；实现 `Tool`。
2. `schema()`：`command` 必填。
3. `execute`：按 `System.getProperty("os.name")` 选 shell（Windows `cmd /C`，其余 `sh -c`）；`redirectErrorStream(true)`；启动后用 `Thread.ofVirtual()` 异步读 `getInputStream()` 到 `ByteArrayOutputStream`；`waitFor(DEFAULT_TIMEOUT.toMillis(), MILLISECONDS)` 为 false → `destroyForcibly()` + `error("命令超时")`；否则 join reader thread，返回含 stdout/exit_code 文本（经 `Truncate` ~30000 字符），非零退出不设 isError（按结果回灌）。

**验证：** `mvn -q test` 编译；T9 后单测 `echo hi` 与超时命令。

## T7: GlobTool

**文件：** `tool/GlobTool.java`
**依赖：** T2
**步骤：**
1. 私有 record `GlobArgs(String pattern, String path)`；实现 `Tool`。
2. `schema()`：`pattern` 必填，`path` 可选（默认 `.`）。
3. `execute`：`Files.walk(Path.of(path==null?".":path))`，对相对路径做支持 `**` 的段匹配（自实现 `matchGlob`：pattern 与 path 按 `/` 切段，`**` 匹配零或多个段，其余段用 `PathMatcher.glob:`）；收集 ≤100 字典序排序；无匹配返回 `ok("无匹配")`（非 isError）。

**验证：** `mvn -q test` 编译；T9 后单测 `**/*.java` 命中 `src/main/...`。

## T8: GrepTool

**文件：** `tool/GrepTool.java`
**依赖：** T2
**步骤：**
1. 私有 record `GrepArgs(String pattern, String path, String glob)`；实现 `Tool`。
2. `schema()`：`pattern` 必填（描述注明 Java `Pattern` 语法），`path`/`glob` 可选。
3. `execute`：`Pattern.compile` 抛 `PatternSyntaxException` → isError；`Files.walk` 遍历（`glob` 非空时按文件名过滤），`BufferedReader` 限行长 1MB 逐行读（溢出跳过并标注），`matcher.find()` 收集 `file:line:content` ≤100；无命中返回 `ok("无命中")`（非 isError）。

**验证：** `mvn -q test` 编译；T9 后单测搜已知关键字命中。

## T9: createDefault + 工具单测

**文件：** `tool/ToolRegistry.java` + 新建 `test/tool/ToolRegistryTest.java`
**依赖：** T3–T8
**步骤：**
1. `ToolRegistry.createDefault()`：依次 register 6 个工具，返回注册中心。
2. `ToolRegistryTest`（JUnit 5，`@TempDir`）覆盖：`definitionsReturnsSixOrdered`（AC1）、`readFile_exists/missing/directory`、`writeFile_nestedDir`（AC3）、`editFile_zero/unique/multiple`（AC4，断言三条文案不同且 >1 含数字）、`bash_echo/timeout`（AC5）、`glob_starStarJava`、`grep_keyword`（AC6）。

**验证：** `mvn -Dtest=ToolRegistryTest test` 全通过。

## T10: config 扩展

**文件：** `config/{AppConfig,ConfigLoader}.java` + 新建 `config/ThinkingConfig.java`
**依赖：** 无（可与 T2–T9 并行）
**步骤：**
1. `ThinkingConfig`：record `(boolean enabled, int budgetTokens)`，常量 `DEFAULT_BUDGET=2048`、`DISABLED`。
2. `AppConfig`：增 `protocol` 字段（record 第 5 个组件，放最后）；常量 `PROTOCOL_OPENAI="openai"`、`PROTOCOL_ANTHROPIC="anthropic"`、`ENV_PROTOCOL="DINO_PROTOCOL"`、`DEFAULT_BASE_URL`（openai 默认）、`ANTHROPIC_DEFAULT_BASE_URL`。加便捷工厂 `AppConfig(..., protocol)` 兼容旧构造。
3. `ConfigLoader`：解析 `DINO_PROTOCOL`（默认 openai，非法值抛 `ConfigException("未知 protocol: ...")`）；`base_url` 缺省按协议给默认；解析 thinking（可选 `thinking.enabled`/`thinking.budget_tokens`）。
4. 迁移 `AppConfig` 构造点（`ConfigLoader.load`、`OfflineStreamTest` 等）补 `protocol` 参数。

**验证：** `mvn test` 全绿；`mvn -Dtest=ConfigLoaderTest test` 通过。

## T11: ChatRequest 增 tools

**文件：** `provider/ChatRequest.java` + 迁移调用点
**依赖：** T1
**步骤：**
1. `ChatRequest` 改为 `record ChatRequest(List<Message> history, int maxTokens, List<ToolDefinition> tools)`。
2. 迁移 `Tui.turn`、`OfflineStreamTest` 的构造点补 `List.of()`（T16/T18 再替换为真实工具）。

**验证：** `mvn test` 全绿（Provider 暂忽略 tools，向后兼容）。

## T12: ProviderFactory + ApiErrors.fromStreamError

**文件：** 新建 `provider/ProviderFactory.java` + 修改 `provider/ApiErrors.java`
**依赖：** T10
**步骤：**
1. `ProviderFactory.create(AppConfig)`：`protocol` 为 `anthropic` → `AnthropicProvider`（T14 才建类，先留 TODO 或先建空壳），`openai` → `OpenAiProvider`，否则抛 `IllegalArgumentException`。
2. `ApiErrors.fromStreamError(String type, String message)`：映射 `authentication_error`→AUTH、`rate_limit_error`/`overloaded_error`→RATE_LIMIT、`invalid_request_error`→OTHER，返回 `ChatEvent.Failure`。

**验证：** `mvn -q test` 编译通过。

## T13: OpenAIProvider 工具注入 + 解析 + 回灌

**文件：** `provider/OpenAiProvider.java`
**依赖：** T11
**步骤：**
1. `buildRequest`：`request.tools()` 非空时 body 增 `tools`（每项 `{type:"function", function:{name,description,parameters:inputSchema}}`）。
2. `OpenAiEventStream`：新增按 `index` 累积 `delta.tool_calls` 的字段；每片 `delta.tool_calls[i]` 首片记 `id`+`function.name`，后续片拼 `function.arguments`；`[DONE]` 时若累积非空 → 逐个发 `ToolCallComplete(id, name, arguments==空?"{}":arguments)` 再发 `Done`。
3. 回灌：`buildRequest` 组装 messages 时，assistant 有 `toolCalls` → 手工构造含 `tool_calls` 数组的 assistant 消息；`Role.TOOL` → 每个结果一条 `role:"tool"`、`tool_call_id`+`content`。

**验证：** `mvn -Dtest=OpenAiProviderTest test` 通过；`mvn test` 全绿。

## T14: AnthropicProvider

**文件：** 新建 `provider/AnthropicProvider.java`
**依赖：** T10、T11、T12
**步骤：**
1. 类 `AnthropicProvider extends AbstractHttpProvider`；`buildRequest`：`POST {base}/v1/messages`，头 `x-api-key`+`anthropic-version: 2023-06-01`；body 含 `model`/`max_tokens`/`messages`/`stream:true`；`tools` 映射 `[{name,description,input_schema}]`；thinking 开启时附 `{"type":"enabled","budget_tokens":N}`。
2. `AnthropicEventStream extends HttpEventStream`：`ping` 忽略；`content_block_start`（`type=="tool_use"`）记 `id`+`name`；`content_block_delta` 的 `input_json_delta.partial_json` 拼到缓冲，`text_delta`/`thinking_delta` 分别上抛 `TextDelta`/`ThinkingDelta`；`message_delta` 累积 output_tokens；`message_stop` 时若有 tool_use → 逐个发 `ToolCallComplete` 再发 `Done(usage)`；`error` → `ApiErrors.fromStreamError`。
3. 回灌：assistant 有 toolCalls → content 为 `[text(若有), {type:tool_use,id,name,input}, ...]`；`Role.TOOL` → 一条 `role:"user"` 消息，content `[{type:tool_result, tool_use_id, content, is_error}, ...]`。

**验证：** `mvn -q test` 编译通过（T15 补 fixture 回放单测）。

## T15: Anthropic/OpenAI 工具流回放测试

**文件：** 新建 `test/provider/AnthropicProviderTest.java` + 修改 `test/OfflineStreamTest.java` + 新建 fixtures
**依赖：** T13、T14
**步骤：**
1. fixtures：`anthropic-stream.txt`（普通流，重加）、`anthropic-tool.txt`（含 `content_block_start tool_use` + `input_json_delta`）、`openai-tool.txt`（含 `delta.tool_calls`）。
2. `AnthropicProviderTest`：fixture 回放断言 `ThinkingDelta`/`TextDelta`/`ToolCallComplete`/`Done` 序列正确（AC7/F4）。
3. `OfflineStreamTest` 扩展：mock 端点回放 `openai-tool.txt`，断言 `ToolCallComplete` 的 `name`/`arguments` 拼齐。

**验证：** `mvn -Dtest=AnthropicProviderTest,OfflineStreamTest test` 通过。

## T16: agent 单轮闭环

**文件：** 新建 `agent/{TurnEvent,TurnStream,Agent}.java`
**依赖：** T1、T9、T13、T14
**步骤：**
1. `TurnEvent`：sealed `Text`/`Thinking`/`ToolStart`/`ToolEnd`/`Done`/`Error`。
2. `TurnStream`：`next()` 阻塞 / `close()` 中断。
3. `Agent`：构造 `(ChatProvider, ToolRegistry)`；`run(history, maxTokens)` 返回 `TurnStream`，内部同步跑单轮闭环（见 plan「run 算法」）；`argsPreview` 取 `path`/`command`/`pattern` 等关键字段截 80 字符；`close` 关底层流 + `Future.cancel(true)`。

**验证：** `mvn -q test` 编译通过（T17 补单测）。

## T17: AgentTest

**文件：** 新建 `test/agent/AgentTest.java`
**依赖：** T16
**步骤：**
1. fake `ChatProvider` 脚本：(a) 请求#1 返回 1 个工具调用、请求#2 返回文本 → 断言 Event 序列含 ToolStart/ToolEnd 与最终 Text，`history` 末尾为 assistant 文本（AC8）；(b) 请求#1 返回工具、请求#2 仍返回工具 → 断言只执行一轮、不发起第二轮（AC9）。

**验证：** `mvn -Dtest=AgentTest test` 通过。

## T18: TUI + Main 接线

**文件：** `tui/{Tui,Renderer}.java` + `Main.java` + `session/SessionStore.java`（若 T1 未做过滤）
**依赖：** T16、T10、T12
**步骤：**
1. `Renderer` 增 `toolLine(name, argsPreview)`（绿色 `● name(args)`）、`toolSummary(summary, isError)`（缩进 `  ⎿ `、灰/红、截 ~8 行）。
2. `Tui`：构造增 `ToolRegistry` 字段；`turn()` 重写为 `history.add(user)` → `new Agent(provider, registry).run(history, maxTokens)` → 循环 `TurnStream.next()` 按 `TurnEvent` 渲染；SIGINT 时 `turnStream.close()`。
3. `Main`：`ToolRegistry.createDefault()` + `ProviderFactory.create(config)` + 注入 `Tui`。

**验证：** `mvn package` 成功；`java -jar target/dinocode.jar` 启动进入对话界面。

## T19: 全量验证 + 端到端冒烟

**文件：** 无（验证）
**依赖：** T1–T18
**步骤：**
1. `mvn test`、`mvn package` 全绿。
2. mock 端点（`MockServerMain` 或扩展）跑通「读 X 并总结」→ 工具行 + 结果摘要 + 最终答复。
3. 触发各错误（读不存在、edit 匹配不到、bash 非零退出）→ 结构化回灌、不退出（AC12）。
4. 两种协议（openai + anthropic mock）跑同一组任务，行为一致（AC10）。
5. 逐条对照 `Checklist.md` 勾选。

**验证：** 全部命令通过、端到端链路与错误恢复符合预期。

## 执行顺序

```
T1 ──┬── T2 ──┬── T3 ─┐
     │         ├── T4 ─┤
     │         ├── T5 ─┼── T9 ─┐
     │         ├── T6 ─┤       │
     │         ├── T7 ─┤       │
     │         └── T8 ─┘       │
     ├── T10 ──┬── T12 ────────┤
     │          └── T14 ───┐   │
     ├── T11 ──┬── T13 ────┤   │
     │          └── T15 ───┤   │
     │                     │   │
     T9, T13, T14 ──────────→ T16 ──→ T17 ──→ T18 ──→ T19
                             T10, T12 ──┘
```
