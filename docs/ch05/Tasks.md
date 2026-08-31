# 系统提示工程化 Tasks

> 顶层包:`com.mewcode`(Java 21 / Gradle)。构建产物在 `build/libs/`。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/mewcode/prompt/Module.java` | `Module` record |
| 新建 | `src/main/java/com/mewcode/prompt/Modules.java` | `fixedModules()` 七固定模块、`optionalModules()` 三空槽的内容常量 |
| 改   | `src/main/java/com/mewcode/prompt/Prompt.java` | `assembleSystem`/`buildSystemPrompt`;删旧 `SYSTEM_PROMPT`/`PLAN_MODE_REMINDER`/`EXECUTE_DIRECTIVE` 常量(迁移);保留 banner |
| 新建 | `src/main/java/com/mewcode/prompt/Environment.java` | `Environment` record、`gather`、`render` |
| 新建 | `src/main/java/com/mewcode/prompt/Reminder.java` | `systemReminder` 标签包裹、`plan(boolean full)`、规划提醒完整/精简常量、`EXECUTE_DIRECTIVE` |
| 新建 | `src/test/java/dev/mewcode/prompt/PromptTest.java` | 装配顺序、跳空槽、N1 确定性、双重强化文本断言 |
| 新建 | `src/test/java/dev/mewcode/prompt/EnvironmentTest.java` | 非 git 降级、`render` 含 cwd/platform/date |
| 新建 | `src/test/java/dev/mewcode/prompt/ReminderTest.java` | `<system-reminder>` 标签 + 完整/精简 |
| 改   | `src/main/java/com/mewcode/tool/EditFileTool.java` | `description()` 补「编辑前先 read_file」 |
| 改   | `src/main/java/com/mewcode/tool/BashTool.java` | `description()` 补「优先用专用工具而非 bash 拼凑」 |
| 新建 | `src/main/java/com/mewcode/llm/Request.java` | `Request` record |
| 新建 | `src/main/java/com/mewcode/llm/System.java` | `System` record(stable + environment) |
| 改   | `src/main/java/com/mewcode/llm/Usage.java` | 加 `cacheWrite`/`cacheRead` |
| 改   | `src/main/java/com/mewcode/llm/Provider.java` | `stream(Request)`;删 `effectiveSystem` 及 prompt import |
| 改   | `src/main/java/com/mewcode/llm/AnthropicProvider.java` | 两块 system(稳定块打断点 + env 块)、缓存用量解析、reminder 并入末条 user |
| 改   | `src/main/java/com/mewcode/llm/OpenAIProvider.java` | 单条 system(stable+env 拼接)、`cachedTokens` 解析、reminder 追加尾部 user |
| 新建 | `src/test/java/dev/mewcode/llm/AnthropicSystemTest.java` | 序列化断言 `cacheControl` 只挂在 stable 块、env 块无 |
| 改   | `src/main/java/com/mewcode/agent/Agent.java` | 构造器加 `version`;`run` 采集环境/装配系统;按轮次 reminder;缓存用量透传 |
| 改   | `src/test/java/dev/mewcode/agent/AgentTest.java` | 断言 Request 装配(system 两段、规划按轮次 reminder)、缓存用量透传;修既有用例适配新签名 |
| 改   | `src/main/java/com/mewcode/tui/AgentEvent 队列.java` | `new Agent(...)` 传 `tuiApp.version()` |
| 改   | `src/main/java/com/mewcode/smoke/SmokeMewCode.java` | 打印缓存用量;`new Agent(p, registry, "dev")` |

---

## T1: prompt 模块化装配

**文件:** `src/main/java/com/mewcode/prompt/Module.java`、`src/main/java/com/mewcode/prompt/Modules.java`、`src/main/java/com/mewcode/prompt/Prompt.java`
**依赖:** 无
**步骤:**
1. 在 `Module.java` 定义 `public record Module(String name, int priority, String content) {}`。
2. `Modules.fixedModules()` 返回 `List<Module>`,七个固定模块,内容内置(中英按现有 `SYSTEM_PROMPT` 风格,英文为主):
   - 身份(10):MewCode 是终端编码 Agent。
   - 系统约束(20):操作边界——在工作目录约定内行事、不外泄密钥、对破坏性操作谨慎。
   - 任务模式(30):ReAct——多步推进、读后再改、完成才给终答。
   - 动作执行(40):何时调工具、连续只读可并发、有副作用谨慎。
   - 工具使用(50):**优先用 read_file/glob/grep 而非 bash 拼凑;编辑文件前必先 read_file**(F5)。
   - 语气风格(60):简洁、直接、不奉承。
   - 文本输出(70):必要时用 Markdown(代码块/列表),终答精炼。
3. `Modules.optionalModules()` 返回三个空槽:自定义指令(80)、已激活 Skill(90)、长期记忆(100),`content` 均为 `""`。
4. 在 `Prompt.java`:
   - `assembleSystem(List<Module> mods)`:按 `priority` 升序 `Collections.sort`(或 `stream().sorted()`)、**跳过 `content.isEmpty()`**、用 `String.join("\n\n", ...)` 连接。
   - `buildSystemPrompt()`:`assembleSystem(Stream.concat(fixedModules().stream(), optionalModules().stream()).toList())`。
   - 删除旧 `SYSTEM_PROMPT`、`PLAN_MODE_REMINDER` 常量(内容迁至模块/reminder);`EXECUTE_DIRECTIVE` 迁至 `Reminder.java`。保留 `CAT_BANNER`/`READY_HINT`/`renderBanner`。

**验证:** `./gradlew compileJava`;临时 `System.out.println(Prompt.buildSystemPrompt())` 观察七模块按序、空槽不留空行。

## T2: 环境采集与渲染

**文件:** `src/main/java/com/mewcode/prompt/Environment.java`
**依赖:** 无
**步骤:**
1. 定义 `public record Environment(String workingDir, String platform, String date, String gitStatus, String version, String model)`。
2. `public static Environment gather(String version, String model)`:
   - `workingDir = java.lang.System.getProperty("user.dir")`(为空降级 `""`)、`platform = java.lang.System.getProperty("os.name")`、`date = java.time.LocalDate.now().toString()`。
   - `gitStatus`:用 `new ProcessBuilder("git", "status", "--porcelain").redirectErrorStream(true).start()`,`process.waitFor(2, TimeUnit.SECONDS)`,非零退出/非 git 目录/超时/`IOException` → `""`;有输出则取摘要(如「N 个文件改动」或前几行)。
   - `version=version`、`model=model`。**不读任何环境变量**(N5)。
3. `public String render()`:渲染为「环境信息」段——逐行 `Key: Value`,空值项省略。

**验证:** 单测 `EnvironmentTest` 在临时非 git 目录调用 `gather` 得 `gitStatus.isEmpty()` 且不抛;`render()` 含 cwd/platform/date。

## T3: 补充消息与规划提醒构造

**文件:** `src/main/java/com/mewcode/prompt/Reminder.java`
**依赖:** 无
**步骤:**
1. `public static String systemReminder(String body)`:返回 `"<system-reminder>\n" + body + "\n</system-reminder>"`。
2. 规划提醒常量:`PLAN_REMINDER_FULL`(完整版,含「仅可用只读工具调研、产出分步计划、等 /do 批准」)、`PLAN_REMINDER_CONCISE`(精简版,一两句)。
3. `public static String plan(boolean full)`:`systemReminder(full ? PLAN_REMINDER_FULL : PLAN_REMINDER_CONCISE)`。
4. `public static final String EXECUTE_DIRECTIVE`(从 `Prompt.java` 迁来):`/do` 注入的用户消息文案。

**验证:** 单测 `ReminderTest` 断言 `Reminder.plan(true)` 含 `<system-reminder>` 与完整文案;`Reminder.plan(false)` 用精简文案。

## T4: prompt 单测

**文件:** `src/test/java/dev/mewcode/prompt/PromptTest.java`
**依赖:** T1, T2, T3
**步骤:**
1. 装配顺序:断言 `Prompt.buildSystemPrompt()` 中身份段出现在工具使用段之前;模块以空行(`\n\n`)分隔。
2. 跳空槽:在 `Prompt.assembleSystem` 传入含空 `content` 的模块,断言其不出现、不产生连续多空行。
3. **N1 确定性**:连续两次 `Prompt.buildSystemPrompt()` 结果 `equals`。
4. **F5 双重强化**:`Prompt.buildSystemPrompt()` 文本含「编辑」与「先读」之意、含「优先」与专用工具名。
5. 环境与 reminder:见 T2/T3 验证。

**验证:** `./gradlew test -Dtest='PromptTest,EnvironmentTest,ReminderTest'` 通过。

## T5: 工具描述双重强化

**文件:** `src/main/java/com/mewcode/tool/EditFileTool.java`、`src/main/java/com/mewcode/tool/BashTool.java`
**依赖:** 无
**步骤:**
1. `EditFileTool.description()` 末补:「编辑前请先用 read_file 读取目标文件,确认 old_string 唯一。」
2. `BashTool.description()` 末补:「读文件、找文件、搜内容请优先用 read_file/glob/grep,不要用 bash 拼凑。」
3. 不改 schema、不改 `execute()` 行为。

**验证:** `./gradlew compileJava`;`./gradlew test -Dtest='*ToolTest'` 仍通过。

## T6: llm 接口改造

**文件:** `src/main/java/com/mewcode/llm/System.java`、`src/main/java/com/mewcode/llm/Request.java`、`src/main/java/com/mewcode/llm/Usage.java`、`src/main/java/com/mewcode/llm/Provider.java`
**依赖:** 无(但 T7/T8/T9 依赖本任务)
**步骤:**
1. 新增 `public record System(String stable, String environment)`、`public record Request(List<Message> messages, List<Map<String, Object>/*tool schema*/> tools, System system, String reminder)`。
2. `Usage` 加 `long cacheWrite`、`long cacheRead`(若 `Usage` 已是 record,定义新 record 含全部字段;若为类,加字段与 accessor)。
3. `Provider.stream` 改为 `BlockingQueue<StreamEvent> stream(Request req)`;更新接口 javadoc。
4. 删除 `effectiveSystem` 方法与对 `com.mewcode.prompt` 包的 import。
5. 工厂、构造器签名保持。

**验证:** `./gradlew compileJava` 在 `AnthropicProvider`/`OpenAIProvider` 报未适配的编译错(预期),T7/T8 修复。

## T7: Anthropic 适配缓存通道 + reminder

**文件:** `src/main/java/com/mewcode/llm/AnthropicProvider.java`
**依赖:** T6
**步骤:**
1. `stream(Request req)`:
   - 构造 `List<TextBlockParam>`:`req.system().stable()` 非空 → `TextBlockParam.builder().text(stable).cacheControl(CacheControlEphemeral.builder().build()).build()`(断点);`req.system().environment()` 非空 → `TextBlockParam.builder().text(env).build()`(无 `cacheControl`)。
   - `params.system(systemBlocks)`。
   - `messages = toAnthropicMessages(req.messages())`;`req.reminder() != null && !req.reminder().isEmpty()` → 调 `appendReminder(msgs, req.reminder())`:把 `ContentBlockParam.ofText(TextBlockParam.builder().text(reminder).build())` 追加到**最后一条消息**的 content 列表;末条非 user 时新起一条 user 消息。
   - `params.tools(toAnthropicTools(req.tools()))`(不另打断点)。
   - thinking 逻辑沿用(`assistantUsedTools(req.messages())`)。
2. Usage 解析:订阅 `onComplete` 时从 `accumulator.usage()` 取 `inputTokens`/`outputTokens`/`cacheCreationInputTokens`/`cacheReadInputTokens`,推 `pub.submit(new StreamEvent.UsageEvent(new Usage(...)))`。
   - 注意缺字段 `Optional.orElse(0L)` 处理(N6)。

**验证:** `./gradlew compileJava` 通过(配合 T8);烟囱 anthropic 跑两轮见 `cacheRead > 0`(次轮)。

## T8: OpenAI 适配缓存通道 + reminder

**文件:** `src/main/java/com/mewcode/llm/OpenAIProvider.java`
**依赖:** T6
**步骤:**
1. `toOpenAIMessages(req)`:首条 system 消息 = `req.system().stable()`(若 `environment` 非空拼为 `stable + "\n\n" + environment`);随后映射历史;`req.reminder()` 非空 → 追加一条尾部 `ChatCompletionUserMessageParam.builder().content(req.reminder()).build()`。
2. `stream(Request req)` 改用 `req`;`params.tools(toOpenAITools(req.tools()))`。
3. Usage 解析:`cacheRead = accumulator.usage().promptTokensDetails().cachedTokens().orElse(0L)`;`cacheWrite = 0L`。

**验证:** `./gradlew compileJava` 通过;烟囱 openai 兼容端点跑两轮,`cachedTokens` 字段被打印(端点支持则 >0)。

## T9: agent 改造

**文件:** `src/main/java/com/mewcode/agent/Agent.java`
**依赖:** T1, T2, T3, T6
**步骤:**
1. `Agent` 加 `private final String version` 字段;构造器 `Agent(LlmClient client, ToolToolRegistry registry, String version)`。
2. 加常量 `private static final int PLAN_REMINDER_INTERVAL = 4;`。
3. `run` 起始:`var env = Environment.gather(this.version, provider.model());`;`var sys = Prompt.buildSystemPrompt();`;`defs` 按 mode 选择(规划=`registry.readOnlyDefinitions()`,普通=`registry.getAllSchemas(protocol)`)——**移除 suffix 变量**。
4. 每轮迭代算 reminder:`String reminder = "";`;`if (mode == Mode.PLAN) { boolean full = (iter == 1) || ((iter - 1) % PLAN_REMINDER_INTERVAL == 0); reminder = Reminder.plan(full); }`。
5. `streamOnce` 签名改为接收 `sys`、`envText`、`defs`、`reminder`,内部组装 `var req = new llm.Request(conv.messages(), defs, new llm.System(sys, envText), reminder);` 调 `provider.stream(req)`。
6. `agent.Usage` 加 `cacheWrite/cacheRead`;`run` 透传 `new Event.Usage(input, output, cacheWrite, cacheRead)`。

**验证:** `./gradlew compileJava` 通过(配合 T10/T11)。

## T10: TUI 与 smoke 接线

**文件:** `src/main/java/com/mewcode/tui/AgentEvent 队列.java`、`src/main/java/com/mewcode/smoke/SmokeMewCode.java`
**依赖:** T9
**步骤:**
1. `AgentEvent 队列`(或 `MewCodeModel` 内构造 Agent 的位置):改 `new Agent(provider, registry, tuiApp.version())`;`/do` 注入仍用 `Reminder.EXECUTE_DIRECTIVE`(已迁至 `Reminder.java`,import 路径更新)。
2. `SmokeMain`:`new Agent(p, ToolToolRegistry.createDefault(), "dev")`;消费 `Event.usage()` 时打印 `input/output/cache_write/cache_read`;可改为连发两条消息观察次轮 `cacheRead`。

**验证:** `./gradlew shadowJar` 全绿;`java -jar build/libs/mewcode.jar` 可启动。

## T11: agent 单测适配

**文件:** `src/test/java/dev/mewcode/agent/AgentTest.java`
**依赖:** T9
**步骤:**
1. 修 fake provider:`stream` 实现新签名 `BlockingQueue<StreamEvent> stream(Request req)`;记录收到的 `req`(`system.stable`/`system.environment`、`tools`、`reminder`)。
2. 既有 ch04 场景(A 自然完成、B 上限、C 未知工具、D 并发、E 取消、F 规划只读工具)适配新签名;`new Agent(...)` 传 version。
3. 新增断言:
   - 规划模式下 `req.system().stable()` 非空且**普通/规划一致**;`req.system().environment()` 非空。
   - 规划模式 iter1 的 `req.reminder()` 含完整提醒、含 `<system-reminder>`;iter2 为精简版(构造一个让循环多轮的脚本)。
   - 规划模式 `req.tools()` 仅只读;普通模式全量。
   - reminder **不写入 conv 持久历史**(`conv.messages()` 不含 reminder 文本)。
   - 缓存用量透传:fake 发 `Usage(..., cacheWrite=X, cacheRead=Y)` → 收到的 `Event.Usage` 携带 X/Y。

**验证:** `./gradlew test -Dtest=AgentTest` 通过;`./gradlew -Dsurefire.argLine='-Xshare:off' test -Dtest=AgentTest` 多轮稳定。

## T12: 全量编译测试与规范

**文件:** —
**依赖:** T1–T11
**步骤:**
1. `./gradlew compileJava` (代码风格由 IDE 保证)(无差异)。
2. `./gradlew shadowJar`、`./gradlew test`。
3. 多轮跑 `./gradlew test -Dtest='*Test'` 观察并发稳定。

**验证:** 全部通过;检索输出无 api_key 明文。

## 执行顺序

```
T1 ─┐
T2 ─┼─→ T4(prompt 单测)
T3 ─┘
T5(工具描述,独立)

T6(接口) ─┬─→ T7(anthropic) ─┐
          └─→ T8(openai)    ─┤
T1,T2,T3,T6 ─→ T9(agent) ────┼─→ T10(tui/smoke)
                              └─→ T11(agent 单测)

全部 ─→ T12(编译/测试/spotless)
```