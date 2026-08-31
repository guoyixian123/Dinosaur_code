# 系统提示工程化 Plan

> 技术栈:Java 21(LTS、virtual thread / record / sealed / pattern matching);Gradle;`com.anthropic:anthropic-java` 2.x、`com.openai:openai-java` 4.x。所有 SDK 缓存 API 已对 Gradle Central 上的官方 SDK 类型核实(见技术决策表)。

## 架构概览

ch05 在三层叠加,**不改 ch04 的 Agent Loop 控制流**:

- **prompt 包(重写)**:从「单个常量字符串」升级为「模块化装配 + 环境采集 + 补充消息构造」。对外产出三类文本——**稳定系统提示**(可缓存)、**环境信息段**(不缓存)、**system-reminder 包裹的补充指令**。prompt 包不依赖 llm 包(避免环依赖)。
- **llm 包(改造)**:`Provider.stream` 入参从位置参数改为 `Request` record,承载 `messages / tools / system{stable,environment} / reminder`。Anthropic 把 stable 块打缓存断点、env 块不打;OpenAI 把 stable 置于系统消息前缀。`Usage` 增加缓存写/读字段。两 provider 把 `reminder` 按各自协议安全地织入消息通道(N3)。
- **agent 包(改造)**:每次 `run` 开始采集环境、装配稳定系统提示;每轮迭代按 `mode + iter` 计算本轮 reminder(规划模式按轮次详略),组装 `Request` 发起请求;把缓存用量透传到 `Event.usage`。
- **smoke(改造)**:打印每轮用量的缓存写/读字段,作为缓存策略生效的验证手段(TUI 状态栏不变)。

数据流:`agent.run` → `Prompt.buildSystemPrompt()`(稳定) + `Environment.gather(...).render()`(环境) + `Reminder.plan(full)`(本轮补充) → 组装 `llm.Request` → `provider.stream` → Anthropic/OpenAI 各自装配缓存通道与消息通道 → 流式事件回到 agent → `Event{usage:{...cacheWrite,cacheRead}}` → smoke 打印。

## 核心数据结构

### prompt.Module(新增)
```java
package com.mewcode.prompt;

public record Module(
        String name,    // 模块标识(身份、系统约束 …),仅用于可读性与测试断言
        int priority,   // 数值越小优先级越高、排越前;固定模块 10..70,可选模块 80..100
        String content  // 模块正文;为空则装配时跳过(可选空槽)
) {}
```

### prompt.Environment(新增)
```java
package com.mewcode.prompt;

public record Environment(
        String workingDir, // System.getProperty("user.dir")
        String platform,   // System.getProperty("os.name")
        String date,       // LocalDate.now().toString()
        String gitStatus,  // `git status --porcelain` 摘要;非 git 目录/取不到则留空
        String version,    // 应用版本(从 agent 透传)
        String model       // provider.model()
) {
    public static Environment gather(String version, String model) { /* … */ }
    public String render() { /* … */ }
}
```

### llm.System(新增)
```java
package com.mewcode.llm;

public record System(
        String stable,      // 可缓存:装配好的稳定系统提示(工具定义随 tools 一并进缓存前缀)
        String environment  // 不缓存:环境信息段
) {}
```

### llm.Request(新增,替换 stream 位置参数)
```java
package com.mewcode.llm;

public record Request(
        java.util.List<Message> messages,   // 持久对话历史(不含本轮 reminder)
        java.util.List<Map<String, Object>/*tool schema*/> tools, // 本轮工具集(普通=全量 / 规划=只读)
        System system,                       // 稳定系统提示 + 环境段
        String reminder                      // 本轮 system-reminder 内容(已含标签;空=不注入)
) {}
```

### llm.Usage(扩展)
```java
public record Usage(
        long inputTokens,
        long outputTokens,
        long cacheWrite, // Anthropic: cacheCreationInputTokens;OpenAI: 恒 0(自动缓存无写计数)
        long cacheRead   // Anthropic: cacheReadInputTokens;OpenAI: promptTokensDetails.cachedTokens
) {}
```

### agent.Usage(扩展,对外事件)
```java
public record Usage(
        long input,
        long output,
        long cacheWrite,
        long cacheRead    // 透传自 llm.Usage,供 smoke 打印
) {}
```

## 核心接口

### prompt 包
```java
public final class Prompt {
    public static java.util.List<Module> fixedModules();      // 七个固定模块(身份…文本输出),内容内置
    public static java.util.List<Module> optionalModules();   // 三个可选空槽(自定义指令/已激活Skill/长期记忆),content=""
    public static String assembleSystem(java.util.List<Module> mods); // 按 priority 升序、跳过空 content、以 "\n\n" 连接
    public static String buildSystemPrompt();                 // = assembleSystem(append(fixedModules(), optionalModules()...))
}

public final class Reminder {
    public static String systemReminder(String body);   // 用 <system-reminder>…</system-reminder> 包裹 body
    public static String plan(boolean full);            // 返回包好标签的规划模式提醒(full=完整 / 否=精简)
    public static final String EXECUTE_DIRECTIVE = "…"; // /do 注入的用户消息文案
}
```

### llm.Provider(签名变更)
```java
public interface Provider {
    String name();
    String model();
    java.util.concurrent.BlockingQueue<StreamEvent> stream(Request req); // 由 Request 承载全部入参
}
```

## 模块设计

### prompt 包
**职责:** 模块化装配稳定系统提示;采集并渲染环境信息;构造 system-reminder 与规划模式提醒。
**对外接口:** 见上。
**关键点:**
- 七个固定模块按优先级排:**身份(10) → 系统约束(20) → 任务模式(30) → 动作执行(40) → 工具使用(50) → 语气风格(60) → 文本输出(70)**;可选空槽:**自定义指令(80) → 已激活 Skill(90) → 长期记忆(100)**(content="" 跳过)。
- **F5 双重强化**写在「工具使用(50)」模块:明确「优先用专用工具(read_file/glob/grep)而非用 bash 拼凑」「编辑文件前必须先 read_file 读取」;同时同义强化到 `EditFileTool`、`BashTool` 的 description(见 tool 包改动)。
- `assembleSystem` 只用常量内容 → 跨轮逐字节一致(**N1**);环境与时间相关内容只进 `Environment`,绝不进稳定模块。
- `Environment.gather`:git 状态用一条 `git status --porcelain`(`ProcessBuilder` + 短超时执行),失败/非 git 目录则 `gitStatus=""`;不读取任何环境变量(**N5**)。
**依赖:** JDK 标准库(`java.lang.System` 读 `user.dir`/`os.name`、`java.time.LocalDate`、`java.lang.ProcessBuilder`);不依赖 llm。

### llm 包(Provider.java / AnthropicProvider.java / OpenAIProvider.java)
**职责:** 把 `Request` 装配为各协议请求,分离缓存通道与消息通道,解析缓存用量,安全织入 reminder。
**对外接口:** `stream(Request)`。
**关键点:**
- 删除 `effectiveSystem` 与对 `com.mewcode.prompt` 包的 import(系统提示改由 agent 传入)。
- **Anthropic**:用 `MessageCreateParams.builder().system(List<TextBlockParam>)` 传**两块** TextBlockParam:`req.system().stable()` 非空 → `TextBlockParam.builder().text(stable).cacheControl(CacheControlEphemeral.builder().build()).build()`(断点,默认 5m TTL);`req.system().environment()` 非空 → `TextBlockParam.builder().text(env).build()`(无 cacheControl)。请求顺序 tools→system→messages,断点打在稳定块 → **缓存前缀 = 全部工具 + 稳定块**;env 与历史在断点后不缓存,env 每轮变化不影响前缀命中。`Usage.cacheWrite = acc.usage().cacheCreationInputTokens()`、`cacheRead = acc.usage().cacheReadInputTokens()`。
  - reminder 织入:`req.reminder() != null && !req.reminder().isEmpty()` 时,把 `ContentBlockParam.ofText(TextBlockParam.builder().text(reminder).build())` **追加到最后一条消息的 content 块列表**(循环中最后一条恒为 user 或 tool_result→user,追加文本块仍是合法 user 消息,保 N3 角色交替);极端情形(末尾为 assistant)则新起一条 user 消息。
- **OpenAI**:首条 system 消息 = `req.system().stable()`(若 `environment` 非空则拼为 `stable + "\n\n" + environment` 单条 system 消息——兼容端点对多条 system 消息支持不一,统一单条);stable 居前缀 → 端点前缀缓存命中稳定部分。`Usage.cacheRead = acc.usage().promptTokensDetails().cachedTokens()`、`cacheWrite = 0`。
  - reminder 织入:`req.reminder()` 非空 → **追加一条尾部 user 消息**(OpenAI 容忍连续 user / tool 后接 user)。
- **N6**:缓存字段缺失即零值(`Optional.orElse(0L)`),不额外校验、不报错。

### agent 包(Agent.java)
**职责:** 采集环境、装配系统提示、按轮次构造 reminder、组装 Request、透传缓存用量。
**关键点:**
- 构造器 `Agent(LlmClient client, ToolToolRegistry registry, String version)` 增加 `version` 字段(供环境段);`model()` 取 `p.model()`。
- `run` 起始:`var env = Environment.gather(this.version, provider.model())`、`var sys = Prompt.buildSystemPrompt()`(稳定,普通/规划模式一致——规划提醒已移出系统通道)。
- 每轮迭代计算 reminder:`mode == Mode.PLAN` → `Reminder.plan(full)`,`full = (iter == 1 || (iter - 1) % PLAN_REMINDER_INTERVAL == 0)`;否则 `""`。`PLAN_REMINDER_INTERVAL = 4`(内置常量)。
- `streamOnce` 组装 `new llm.Request(conv.messages(), defs, new llm.System(sys, env.render()), reminder)` 调 `provider.stream`。
- 删除 `suffix`/`readOnlyDefinitions` 的「系统后缀」用法;**只读工具集仍按 mode 选择**(规划=`ToolRegistry.readOnlyDefinitions()`),`PLAN_MODE_REMINDER` 常量从系统后缀迁移为 `Reminder.plan(...)` 的内容。
- 缓存用量透传:`new Event.Usage(input, output, cacheWrite, cacheRead)`。

### smoke(`src/main/java/com/mewcode/smoke/SmokeMewCode.java`)
**职责:** 端到端验证缓存生效。
**关键点:** 消费 `Event.usage` 时打印 `input/output/cache_write/cache_read`;跑两轮(或多轮)观察次轮 `cacheRead > 0`。`new Agent(p, ToolToolRegistry.createDefault(), "dev")`。

### tool 包(描述微调,F5)
- `EditFileTool.description()`:补「编辑前请先用 read_file 读取目标文件,确认 old_string 唯一」。
- `BashTool.description()`:补「读文件/找文件/搜内容请优先用 read_file/glob/grep,不要用 bash 拼凑」。
- 仅改描述文本,不改行为、不改 schema(N2)。

## 模块交互

```
TUI/smoke ──run(conv,mode)──> agent
  agent.run:
    env  = Environment.gather(version, provider.model())
    sys  = Prompt.buildSystemPrompt()
    for iter:
      reminder = (mode == PLAN) ? Reminder.plan(full(iter)) : ""
      req = new llm.Request(conv.messages(), defs(mode), new llm.System(sys, env.render()), reminder)
      provider.stream(req) ──BlockingQueue──> StreamEvent{TextDelta / ToolCallComplete / Usage(+cache) / Done / Failed}
    Event{usage:{...cacheWrite, cacheRead}} ──> smoke 打印 / TUI 状态栏(忽略 cache 字段)
```

依赖方向(无环):`agent → {prompt, llm, conversation, tool}`;`llm → config`(不再 import prompt);`prompt → JDK 标准库`。

## 文件组织

```
mewcode/
├── src/main/java/com/mewcode/prompt/
│   ├── Prompt.java         — 改:Module 引用/装配/buildSystemPrompt;保留 banner(CAT_BANNER/renderBanner/READY_HINT)
│   ├── Module.java         — 新:Module record
│   ├── Modules.java        — 新:fixedModules()/optionalModules() 七固定+三空槽的内容常量
│   ├── Environment.java    — 新:Environment record / gather / render
│   └── Reminder.java       — 新:systemReminder/plan(完整版/精简版常量)/EXECUTE_DIRECTIVE
├── src/main/java/com/mewcode/llm/
│   ├── Provider.java       — 改:Provider 接口签名 stream(Request)
│   ├── Request.java        — 新:Request record
│   ├── System.java         — 新:System record(stable + environment)
│   ├── Usage.java          — 改:加 cacheWrite/cacheRead
│   ├── AnthropicProvider.java — 改:两块 system(断点 + env)、缓存用量解析、reminder 织入末条 user
│   └── OpenAIProvider.java    — 改:单条 system(stable+env)、cachedTokens 解析、reminder 尾部注入
├── src/main/java/com/mewcode/agent/
│   └── Agent.java          — 改:构造器加 version、run 采集环境/装配系统、按轮次 reminder、缓存透传
├── src/main/java/com/mewcode/tool/
│   ├── EditFileTool.java   — 改:description() 补强化
│   └── BashTool.java       — 改:description() 补强化
├── src/main/java/com/mewcode/tui/
│   └── AgentEvent 队列.java     — 改:new Agent(...) 传 version(MewCodeModel.version 已有)
├── src/main/java/com/mewcode/smoke/
│   └── SmokeMewCode.java      — 改:打印缓存用量;new Agent(p, registry, "dev")
└── src/test/java/dev/mewcode/
    ├── prompt/PromptTest.java   — 新:装配顺序/跳空槽/N1 确定性/双重强化文本 断言
    ├── prompt/EnvironmentTest.java — 新:非 git 目录降级、Render 含 cwd/platform/date
    ├── prompt/ReminderTest.java — 新:<system-reminder> 标签 + 完整/精简
    ├── llm/AnthropicSystemTest.java — 新:序列化 toAnthropicSystem 断言 cacheControl 仅出现在 stable 块
    └── agent/AgentTest.java     — 改/新:断言 Request 装配(system 两段、规划按轮次 reminder)、缓存用量透传
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 系统提示组织 | 模块化(`Module(name, priority, content)` record + `assembleSystem`) | 满足 F1「挂载即扩展」;优先级排序使顺序确定(N1) |
| 环境信息归属 | system 通道独立第二块(用户拍板) | 结构上接系统提示之后;物理上与稳定块分离,不进缓存 |
| Anthropic 缓存断点 | 仅在稳定 system 块打 `cacheControl(CacheControlEphemeral)`(默认 5m) | 请求序 tools→system→messages,断点在稳定块即缓存「工具+稳定块」整段前缀;env 在其后不缓存,env 变化不冲前缀命中(SDK 核实:`TextBlockParam.cacheControl` 字段存在) |
| 工具是否单独打断点 | 否 | 稳定块断点的前缀已含全部工具,无需 `ToolUnion.toolWithCacheControl` 再标 |
| OpenAI 环境信息 | 拼入单条 system 消息(stable 在前) | 兼容端点对多条 system 支持不一;stable 居前缀,端点前缀缓存自动命中稳定部分。代价:env 居 system 尾,OpenAI 工具可能不进缓存前缀——本章 OpenAI 缓存为尽力而为、不强制(F8) |
| 缓存用量字段 | `Usage` 加 `cacheWrite/cacheRead` | Anthropic 取 `cacheCreationInputTokens/cacheReadInputTokens`;OpenAI 取 `promptTokensDetails().cachedTokens()`(SDK 核实) |
| stream 入参 | 改 `Request` record | 入参从 4 个增至含 system/reminder,record 更清晰、后续扩展不再改签名(N8) |
| reminder 注入位置 | Anthropic 并入末条 user 消息 content 块;OpenAI 追加尾部 user 消息 | Anthropic 严格角色交替——并入避免连续 user 触发 400(N3);OpenAI 容忍连续 user |
| reminder 持久化 | 不写入 `ConversationManager`(用户拍板) | 每轮动态构造;不污染缓存、不破坏历史可恢复性 |
| 规划提醒节奏 | `iter == 1` 或 `(iter - 1) % 4 == 0` → 完整,否则精简(per run 内 iter) | 实现 F7「首轮完整、间隔重复、其余精简」;复用已有 iter 计数 |
| 缓存验证呈现 | smoke/调试打印(用户拍板) | 不动 TUI 状态栏;`Usage` 携带字段供打印 |
| prompt↔llm 依赖 | 系统提示由 agent 传入,llm 不再 import prompt | 打破潜在环依赖;职责更清晰 |
| 异步并发 | 沿用 ch04:virtual thread + `BlockingQueue`/`Flow` | 与 ch04 一致,不引入新机制(N2) |