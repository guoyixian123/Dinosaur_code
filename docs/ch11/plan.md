# Skill 系统 Plan

## 架构概览

新增一个 `com.mewcode.skills` 包承载所有 Skill 相关的"数据 + 加载 + 执行 + 激活态"逻辑，与现有 `com.mewcode.command`、`com.mewcode.tool`、`com.mewcode.prompt`、`com.mewcode.agent` 通过细窄接口交互。

按职责拆解：

- **com.mewcode.skills**：核心包。包含数据结构（`Skill`、`SkillMeta`、`ActiveEntry`）、`SKILL.md` 解析、`Catalog` 两层路径扫描与覆盖、Skill 执行器（inline / fork 分支）、`ActiveSkills` 跨轮列表、`$ARGUMENTS` 渲染、`InstallSkill` zip 解压（zip-slip 防护）
- **com.mewcode.tool.LoadSkillTool**：新增 LoadSkill 工具实现。是系统工具，永远可见，不带权限拦截
- **com.mewcode.tool.InstallSkillTool**：新增 InstallSkill 工具实现。普通工具，受权限模式约束
- **com.mewcode.tool.ToolRegistry**：扩展——增加"系统工具"标记与 `filterByAllowed(List<String> allowed)` 切片导出能力
- **com.mewcode.command**：扩展——`registerSkillsAsCommands(registry, catalog, executor)` 把 Catalog 中每个 Skill 注册为 KindPrompt 命令；新增 `/skill` 命令（KindLocal，列出 Catalog）；`UI` 接口扩展 `listCatalogSkills / listActiveSkills / clearActiveSkills`
- **com.mewcode.prompt**：扩展——`OptionalModules` 中现有的"active-skills"槽位重命名为"skills-catalog"，承载第一阶段名字+描述列表；新增 `renderActiveSkillsBlock(entries)` 函数供 env context 拼装
- **com.mewcode.agent**：扩展——`SessionRuntime` 新增 `ActiveSkills activeSkills` 字段；`Agent` 新增 `withCatalog` / `withSkillExecutor` 构造选项；`run` 每轮重建 `sys` 时把 Catalog 列表传入 `buildSystemPrompt`、`envText` 拼接时调用 `renderActiveSkillsBlock`；新增 `clearActiveSkills() / activateSkill / listActive` 入口供 UI 与工具调用
- **com.mewcode.tui**：扩展——Model 持有 catalog 引用与执行器；`handleClear` 路径在 `clearAndNewSession` 后调 `activeSkills.clear`；UI 接口对应新增方法实现

## 核心数据结构

### SkillMeta

```java
package com.mewcode.skills;

import java.util.List;

public record SkillMeta(
        String name,
        String description,
        List<String> allowedTools,
        String mode,         // "inline" / "fork"
        String forkContext,  // "none" / "recent" / "full"
        String model
) {
    public boolean isFork() {
        return "fork".equals(mode);
    }
}
```

约定：`mode` 为 null 或 `"inline"` 视作 inline；`mode == "fork"` 视作 fork；其它值打 warning 后按 inline 处理。`forkContext` 仅 fork 时生效，缺省 `"none"`。

YAML → record 由 SnakeYAML Engine 解析成 `Map<String,Object>` 后手动绑定（`allowed_tools` 下划线键映射到 record 字段）。

### Skill

```java
public record Skill(
        SkillMeta meta,
        String promptBody,        // SKILL.md 去 frontmatter 后的正文（启动时缓存，执行时重读覆盖）
        java.nio.file.Path sourceDir,  // 绝对路径，重读 SKILL.md 时用
        SkillSource source,       // USER / PROJECT
) {}

public enum SkillSource {
    USER, PROJECT;

    @Override
    public String toString() {
        return name().toLowerCase();  // "user" / "project"
    }
}
```

由于 `Skill` 字段在执行期需要被 `promptBody` 重读覆盖，实际实现把它声明为带 setter 的普通类（或用 `AtomicReference<String>` 包装可变正文）。

### Catalog

```java
public final class Catalog {
    private final java.util.concurrent.locks.ReadWriteLock lock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();
    private final java.util.Map<String, Skill> byName = new java.util.HashMap<>();
    private final java.util.List<String> order = new java.util.ArrayList<>();  // 按 name 字典序

    public static Catalog load(java.nio.file.Path workDir);
    public void reload(java.nio.file.Path workDir);              // 内部锁保护，原子替换
    public java.util.Optional<Skill> get(String name);
    public java.util.List<Skill> list();                          // 按 order
    public java.util.List<String> names();
    public java.util.List<ValidationIssue> validateTools(ToolToolRegistry registry);
}
```

`Catalog.load` 按顺序扫描：
1. `~/.mewcode/skills/*` 子目录（`source=USER`）
2. `<workDir>/.mewcode/skills/*` 子目录（`source=PROJECT`）

后扫到的同名 `name` 覆盖前者。

### ActiveSkills

```java
public record ActiveEntry(String name, String body) {}

public final class ActiveSkills {
    private final Object lock = new Object();
    private final java.util.List<ActiveEntry> entries = new java.util.ArrayList<>();
    private final java.util.Map<String, Integer> index = new java.util.HashMap<>();

    public void activate(String name, String body);
    public void clear();
    public java.util.List<ActiveEntry> snapshot();  // 拷贝出当前列表（env 装配用）
    public java.util.List<String> names();
}
```

### Executor

```java
public final class Executor {
    private final Catalog catalog;
    private final SessionRuntime runtime;  // 持有 ActiveSkills 等跨轮状态
    private final ToolToolRegistry registry;
    private final Provider provider;        // 默认 provider；fork 时可用 Skill.model 切换
    private final PermissionEngine engine;
    private final String version;

    public Executor(...) { ... }

    // 入口：被 Slash 命令 handler 调用
    public void execute(java.util.concurrent.CancellationException ctx /* 或自定义 CancelToken */,
                        UI ui, String name, String args);

    // inline 路径直接通过 ui.injectAndSend
    // fork 路径起子 Agent 跑完后通过 ui.appendAssistantNotice 写回主对话
}
```

实际取消用 `volatile boolean cancelled` + `Thread.interrupt()`，签名里以一个轻量级 `CancelToken` 类传递。

## 模块设计

### com.mewcode.skills.SkillParser
**职责**：解析单个 Skill 目录 → `Skill`
**对外接口**：`static Skill parseSkillDir(Path dir, SkillSource source) throws SkillParseException`
**依赖**：`org.yaml:snakeyaml`（已在 build.gradle.kts 中）

解析流程：
1. 读 `<dir>/SKILL.md`，分离 frontmatter（两行 `---` 之间）与 body
2. SnakeYAML `Load.loadFromString(frontmatter)` → `Map<String,Object>` → 手动绑定 `SkillMeta`；校验 name 合法性、mode / fork_context 取值
4. 组装 `Skill` 返回

### com.mewcode.skills.Catalog
**职责**：两层路径扫描与覆盖管理
**对外接口**：`load / reload / get / list / names / validateTools`
**依赖**：`com.mewcode.skills.SkillParser`、JDK `java.nio.file`

`validateTools`：遍历 Catalog 中所有 Skill 的 `meta.allowedTools`，确认每个名字都能在传入的 `ToolRegistry` 里 `get` 到；记录所有不通过项返回。

### com.mewcode.skills.Render
**职责**：把 Skill body 渲染为最终注入文本（inline 和 fork 路径都先经过这一层）
**对外接口**：`static String renderBody(Skill skill, String args)`

逻辑：
- 替换所有 `$ARGUMENTS` 出现
- 若无占位符且 args 非空（trim 后非空），在末尾追加 `\n\n## User Request\n\n<args>`
- 若 `meta.allowedTools` 非空，在 body 顶部插一段 `This skill is designed to use only these tools: <list>. Prefer them over other tools when possible.\n\n---\n\n`

### com.mewcode.skills.Executor
**职责**：inline / fork 分发与执行
**对外接口**：`Executor` 构造 / `execute`

inline 分支：
1. 从 Catalog 取 Skill
2. 从磁盘重读 `SKILL.md`（失败回退缓存）
3. `Render.renderBody`
4. `ui.injectAndSend(displayLabel, body)` —— displayLabel 例如 `/<name>`

fork 分支：
1. 从 Catalog 取 Skill
2. 从磁盘重读 `SKILL.md`
3. `Render.renderBody`
4. 按 `forkContext` 构造初始 Conversation：
   - none：仅一条 user 消息（renderedBody）
   - recent：从主 conversation 拷最近 5 条原始消息，再追加 renderedBody
   - full：先用 `ContextCompactor.summarizeForFork(ctx, mainConv)`（基于 ch09 现成的摘要管道）产出摘要文本，作为一条 system 或 user 消息插入，再追加 renderedBody
5. 选 provider：默认主 provider；`skill.meta().model()` 非空时调 `ProviderFactory.create(...)` 重新构造
6. 构造子 Agent：复用 `Agent.builder().provider(...).registry(...).version(...).engine(...).runtime(forkRuntime).build()`，子 runtime 是独立 `new SessionRuntime()`
7. `forkAgent.run` → 消费 `BlockingQueue<RunEvent>` 直到 `Done`；累计 token 用量
8. 把累计 token 写回主 runtime 的 anchor（usage += sub）
9. 取子对话的最后一条 assistant 文本作为 finalText
10. `ui.appendAssistantMessage(finalText)`（新增 UI 方法）—— 主对话历史新增一条 assistant 消息

任一步骤出错：返回 `finalText = "[skill <name> failed: <reason>]"`，仍以 assistant 消息写入主对话。

### com.mewcode.skills.Install
**职责**：InstallSkill 的核心逻辑——下载 zip、校验路径、解压到 ~/.mewcode/skills/
**对外接口**：`static String installFromUrl(CancelToken ctx, String source, Catalog catalog, Path workDir) throws IOException`

流程：
1. 通过 `java.net.http.HttpClient`（`newHttpClient()`，`Duration.ofSeconds(60)`）下载 source 到临时文件（大小限制 50 MB，超出关闭 stream）
2. 用 `java.util.zip.ZipInputStream` / `ZipFile` 打开
3. 严格校验：所有路径必须以 `<topDir>/` 起头、`<topDir>` 满足 F3 命名、内部不含 `..`、不含绝对路径、不含符号链接（zip 条目通常不含符号链接位，但若 entry 的 unix-attr 标识为 symlink 则拒绝）
4. 解压到 `~/.mewcode/skills/<topDir>/`
5. 调用 `catalog.reload(workDir)` 触发热重载
6. 返回 `<topDir>` 作为 skillName

### com.mewcode.tool.LoadSkillTool
**职责**：LoadSkill 工具实现
**对外接口**：实现 `Tool` 接口

```java
public final class LoadSkillTool implements Tool {
    private final Catalog catalog;
    private final ActiveSkills active;
    private final ToolToolRegistry registry;

    // name / description / parameters / readOnly() / isSystem() / execute(...)
}
```

`isSystem() { return true; }`——新加在 `Tool` 接口（默认 default 方法返回 false，LoadSkill 覆盖为 true）。`execute` 流程：
1. 解析 `args.name`（Jackson `readTree` 取字段）
2. `catalog.get(name)` → 不存在返回 `unknown skill: <name>`
3. 重读 SKILL.md 获取最新 body
4. `active.activate(name, body)`
6. 返回 `Skill <name> activated. SOP pinned to env context.`

### com.mewcode.tool.InstallSkillTool
**职责**：InstallSkill 工具实现
**对外接口**：实现 `Tool`

```java
public final class InstallSkillTool implements Tool {
    private final Catalog catalog;
    private final Path workDir;
}
```

`readOnly() { return false; }`（写盘 + 网络），`isSystem() { return false; }`。`execute` 直接调 `Install.installFromUrl`，返回成功消息或错误。

### com.mewcode.tool.ToolRegistry
**修改**：
- `Tool` 接口新增 `default boolean isSystem() { return false; }` 方法；现有 6 个工具与 MCP 工具沿用默认实现
- `LoadSkillTool.isSystem()` 返回 true
- 新增 `ToolRegistry.systemDefinitions(): List<Map<String, Object>/*tool schema*/>`（仅返回系统工具）
- 新增 `ToolRegistry.definitionsFiltered(List<String> allowed): List<Map<String, Object>/*tool schema*/>`（按白名单 + 系统工具豁免过滤）

注：本期不在主 agent loop 里用 `definitionsFiltered` 改主对话工具集——按 spec F27 决议，inline 模式不真过滤。但 fork 模式子 Agent 用该方法构造工具集。

### com.mewcode.prompt.Modules
**修改**：
- `optionalModules(String instructions, String memory)` 改为 `optionalModules(String instructions, String memory, String skillsCatalog)`
- 原 priority 90 槽位由 `"active-skills"` 重命名为 `"skills-catalog"`，内容由调用方传入
- 增加常量 `PRIO_SKILLS_CATALOG = 90`，删除 `PRIO_ACTIVE_SKILLS`

### com.mewcode.prompt.Prompt
**修改**：
- `buildSystemPrompt(String instructions, String memory)` 改为 `buildSystemPrompt(String instructions, String memory, String skillsCatalog)`
- 增加 `static String renderActiveSkillsBlock(List<ActiveSkillEntry> entries)`，输出形如：
  ```
  ## Active Skills

  ### Skill: my-skill

  <body>

  ### Skill: another-skill

  <body>
  ```
  entries 空时返回空字符串
- 增加 `static String renderSkillsCatalog(List<SkillCatalogItem> items)`，输出 skills-catalog 模块内容；items 空时返回空字符串

为避免 prompt 包反向依赖 skills 包，新增类型：
```java
public record SkillCatalogItem(String name, String description) {}
public record ActiveSkillEntry(String name, String body) {}
```

`skills.Catalog` 和 `skills.ActiveSkills` 提供两个适配方法 `toPromptItems()` / `toPromptEntries()` 把内部类型转换到 prompt 包的类型上。

### com.mewcode.agent.SessionRuntime
**修改**：
- `SessionRuntime` 新增字段 `ActiveSkills activeSkills`
- 构造函数初始化空 `new ActiveSkills()`
- `resetForNewSession` 同时 `this.activeSkills.clear()`

### com.mewcode.agent.Agent
**修改**：
- 新增 `Builder.catalog(Catalog c)`：注入 catalog 引用（用于第一阶段列表与 clearActiveSkills 入口）
- 新增 `Agent.activateSkill(name, body)` / `clearActiveSkills()` 方法，转发到 `runtime.activeSkills`
- `run` 内每轮重建 sys 时：
  ```java
  String catalogText = catalog != null
      ? Prompt.renderSkillsCatalog(catalog.toPromptItems())
      : "";
  String sys = Prompt.buildSystemPrompt(instructionText, memoryText, catalogText);
  String envText = Environment.gather(...).render()
      + "\n\n" + Prompt.renderActiveSkillsBlock(runtime.activeSkills().toPromptEntries());
  ```
  （`catalog` 为 null 时跳过；进度提示放在 sub-tasks）

### com.mewcode.command.Registry + Skills (新建)
**职责**：把 Catalog 注册为 KindPrompt 命令；新增 /skill 命令；UI 接口扩展
**对外接口**：
- `registerSkillsAsCommands(Registry reg, Catalog catalog, Executor exec)`
- 提供给 reload 路径调用的 `removeSkillCommands(Registry reg)`
- 新增内置 `/skill` 命令（KindLocal）

`reg.register` 时给每个 Skill 添加 `hidden=false` 的 `Command`；命令的 `Handler` 是一个 lambda（`(ctx, ui) -> exec.execute(ctx, ui, skillName, "")`），其中 `skillName` 是局部 final 变量以避免闭包捕获后被覆盖。

注：当前 ch10 的 Slash dispatch 是零参数，Skill 显式调用本期也走零参数。`$ARGUMENTS` 替换仅在 LoadSkill + 后续 user message 的隐式场景下被替换为空——这是合理的简化（参数交互通过 Skill 后续轮次的对话进行）。

为了支持 reload 时清理旧命令，`ToolRegistry` 新增 `removeIf(Predicate<Command>)` 或 `removeSkillCommands()` 入口。

### com.mewcode.command.UI
**修改**：
- UI 接口新增方法：
  - `List<SkillSummary> listCatalogSkills()`（每条含 name/description/source/mode）
  - `List<String> listActiveSkills()`
  - `void clearActiveSkills()`
  - `void appendAssistantMessage(String text)`（fork 路径用，把子 Agent 的 finalText 写入主对话历史）
- `NopUI` 提供零值实现

### com.mewcode.command.Builtins
**修改**：
- 修改 `handleClear`：在调 `ui.clearAndNewSession()` 后追加 `ui.clearActiveSkills()`
- 新增 `name = "skill"`、kind = KindLocal、handler = `handleSkill` 的注册块

### com.mewcode.tui.*
**修改**：
- Model 持有 `Catalog`、`Executor` 字段
- 实现新增的 UI 方法：`listCatalogSkills` / `listActiveSkills` / `clearActiveSkills` / `appendAssistantMessage`
- `MewCodeModel` 的 builder 接受新参数并接入

### com.mewcode.MewCode
**修改**：
- 启动时构造 `Catalog`、`ActiveSkills` 并注入到 `SessionRuntime`
- 注册 `LoadSkillTool` / `InstallSkillTool` 内置工具
- 在工具注册完成后调 `catalog.validateTools(registry)`；对每条 issue 打 warning 并把该 Skill 从 Catalog 中移除（保留其它）
- 调 `Commands.registerSkillsAsCommands` 完成自动注册
- 把 catalog/executor 传给 TUI

## 模块交互

### 启动期

```
MewCode.main:
  ├─ ToolToolRegistry.createDefault()
  ├─ Mcp.attachServers(registry)              // 已有
  ├─ Catalog.load(workDir)                    // 两层路径扫描
  ├─ registry.register(new LoadSkillTool(...))// 系统工具
  ├─ registry.register(new InstallSkillTool(...))
  ├─ catalog.validateTools(registry)          // fail-fast 检查
  │     不通过项 → 打 warning + 从 catalog 移除
  ├─ new Executor(catalog, registry, ...)
  ├─ Commands.registerBuiltins(cmdReg)        // ch10 内置命令
  ├─ Commands.registerSkillsAsCommands(cmdReg, catalog, executor)
  ├─ Commands.registerSkillCmd(cmdReg)        // /skill (新)
  └─ new MewCodeModel(.catalog(catalog).executor(executor)...build().run()
```

### Skill 显式调用（如 /my-skill）

```
user → submit → Commands.dispatch(/my-skill)
       → handler 调 executor.execute(ctx, ui, "my-skill", "")
                 ├ inline: render → ui.injectAndSend → agent.run 注入主对话
                 └ fork: render → 子 Agent.run → finalText → ui.appendAssistantMessage
```

### Skill 意图触发（自然语言）

```
user 输入"帮我用 my-skill 处理一下" → agent.run loop
   └ streamOnce 拿到 LLM 调 LoadSkill({name:"my-skill"})
        → tool.execute → LoadSkillTool.execute
              ├ catalog.get → 重读 SKILL.md
              ├ active.activate("my-skill", body)
              └ 返回 toolResult
   下一轮迭代:
        sys = buildSystemPrompt(...catalog清单不变)
        envText = ... + renderActiveSkillsBlock(["my-skill" -> body])
        ↑ Agent 现在看得到完整 SOP
```

### /clear

```
/clear handler → ui.clearAndNewSession() (ch10) → ui.clearActiveSkills()
                                                       └ runtime.activeSkills.clear()
下轮 envText 中 active-skills 块为空字符串
```

### reload (InstallSkill 后或者未来 /skill reload)

```
InstallSkillTool.execute → Install.installFromUrl
   └ 解压完毕 → catalog.reload(workDir)
                ├ 重新扫描两层路径
                ├ 通过读写锁原子替换 byName / order
                └ command 端不会立刻感知—但 dispatcher 每轮按命令名查找 reg，
                   reload 完成后下次 /<name> 即可命中新 Skill。然而启动时已注册的
                   旧命令仍在 registry 中。为简化，提供下面策略：
```

进一步：`catalog.reload` 返回 `(added, removed)` 两个列表，`InstallSkillTool` 拿到结果后调 cmdReg `removeSkillCommands` + `registerSkillsAsCommands`，确保 /help 和补全菜单立即同步。

### Fork 模式

```
executor.execute (fork) →
   ┌──────────────────── 子 Agent ────────────────────┐
   │ 新 Conversation 按 forkContext 初始化             │
   │ Agent.builder().provider(provider).registry(...) │
   │   .version(version).engine(eng)                   │
   │   .runtime(forkRuntime).build()                   │
   │ forkAgent.run(ctx, conv, defaultMode)             │
   │ 累计 token, 取末尾 assistant text                  │
   └───────────────────────────────────────────────────┘
   将 finalText 作为一条 assistant 消息插入主 conv
```

注：fork 模式下子 Agent 的 registry 是用 `ToolRegistry.definitionsFiltered(allowed)` 构造的临时视图（共享底层 `Tool` 实例），系统工具豁免列入。

## 文件组织

```
mewcode/
├── src/main/java/com/mewcode/
│   ├── MewCode.java                       # 接线：构造 catalog / executor / 注册工具与命令
│   ├── skills/                         # 新包
│   │   ├── SkillMeta.java              # record
│   │   ├── SkillSource.java            # enum USER / PROJECT
│   │   ├── Skill.java                  # 持有 promptBody（可变）
│   │   ├── ActiveEntry.java            # record
│   │   ├── SkillParser.java            # parseSkillDir / parseFrontmatter
│   │   ├── Catalog.java                # load / reload / get / list / names / validateTools
│   │   ├── ActiveSkills.java
│   │   ├── Render.java                 # renderBody, $ARGUMENTS 替换, allowed_tools 顶部提示
│   │   ├── Executor.java               # execute(inline / fork)
│   │   ├── Install.java                # installFromUrl（zip 下载与 zip-slip 防护）
│   │   └── Adapter.java                # toPromptItems / toPromptEntries 桥接到 prompt 包
│   ├── tool/
│   │   ├── ToolRegistry.java           # 修改：isSystem 标记 + definitionsFiltered
│   │   ├── LoadSkillTool.java          # 新：LoadSkill 工具
│   │   ├── InstallSkillTool.java       # 新：InstallSkill 工具
│   ├── command/
│   │   ├── Builtins.java               # 修改：改 handleClear、加 /skill
│   │   ├── BuiltinSkill.java           # 新：handleSkill (KindLocal 列表)
│   │   ├── Skills.java                 # 新：registerSkillsAsCommands / removeSkillCommands
│   │   └── UI.java                     # 修改：新增 4 个 UI 方法 + NopUI 兜底
│   ├── prompt/
│   │   ├── Modules.java                # 修改：active-skills → skills-catalog
│   │   ├── Prompt.java                 # 修改：buildSystemPrompt 增 catalog 参数
│   │   ├── SkillsBlock.java            # 新：renderActiveSkillsBlock / renderSkillsCatalog / 类型
│   │   └── Environment.java            # 不动
│   ├── agent/
│   │   ├── SessionRuntime.java         # 修改：activeSkills 字段
│   │   └── Agent.java                  # 修改：catalog 选项 / run 内构造 sys 与 env 拼接
│   └── tui/
│       ├── MewCodeModel.java                 # 修改：持有 catalog/executor + 实现新 UI 方法
│       └── ...
├── src/test/java/dev/mewcode/
│   ├── skills/SkillParserTest.java
│   ├── skills/CatalogTest.java
│   ├── skills/InstallTest.java
│   ├── prompt/PromptTest.java
│   └── agent/AgentRuntimeTest.java
└── docs/ch11/
    ├── spec.md
    ├── plan.md
    ├── task.md
    └── checklist.md
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 数据格式 | 仅 SKILL.md（frontmatter+body） | 与 README 一致；解析路径单一；不引入 yaml/md 分离的认知负担 |
| Skill 形态 | 必须是目录 | 与 references 自然契合；将来扩展空间大 |
| 优先级覆盖 | 用户 < 项目 | 与 npm/git 习惯一致 |
| 第一阶段注入位置 | system prompt 模块（priority 90） | 享受 prompt cache 稳定前缀 |
| 第二阶段注入位置 | env context（每轮重建） | 多 Skill 同激活、嵌套场景下 SOP 永远靠前；prompt cache 失效是设计意图 |
| LoadSkill 入参 | 仅 name | 与"意图识别"语义一致；参数走后续 user message 更自然 |
| LoadSkill 权限 | read-only + 系统工具 | 没有外部副作用；为支持嵌套必须豁免 allowed_tools |
| InstallSkill 权限 | 普通工具，受权限模式约束 | 写盘+网络，必须走授权 |
| fork 模式实现 | Java 端起子 Agent | 直接复用现成 `Agent.run`，不依赖将来 SubAgent 章节 |
| fork_context 默认 | none | "隔离"才是 fork 本意；需要带上下文的显式声明 |
| allowed_tools 在 inline 模式 | 仅 fail-fast + SOP 提示 | 避免 inline 期间动态切换工具集的生命周期复杂度；安全靠 ch08 权限引擎兜底 |
| Skill 与已有命令冲突 | 跳过加载 + warning | 保护内置命令的可靠性；Skill 想替换内置命令需要用户主动改源码 |
| 解析失败 | 跳过单个 Skill，warning，不阻断 | 与 instructions loader 一致的容错策略 |
| 热加载 | InstallSkill 后主动 reload；execute 时重读 body | 用户改 SKILL.md 下次执行立即生效；新装 Skill 不需要重启 |
| Skill 列表数据流 | adapter 桥接，prompt 包不依赖 skills 包 | 避免循环依赖 |
| UI 接口扩展 | 4 个新方法 + NopUI 全量实现 | 与 ch10 风格一致 |
| 闭包变量捕获 | 显式 `final String name = skill.name();` 拷贝再用 | Java lambda 仅能捕获 effectively final 变量，显式拷贝可读性更佳 |
| Skill 自身参数 | 本期 /<name> 仅零参数；后续轮次对话补 | 与 ch10 F7 一致，不破坏 dispatcher |
| zip 下载 | JDK `java.net.http.HttpClient` + `java.util.zip.ZipInputStream` | 标准库；无需第三方依赖 |
| YAML 解析 | SnakeYAML Engine（已用） | 项目既有依赖，避免引新栈 |