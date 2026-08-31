# Skill 系统 Spec

## 1. 背景

Slash Command 让用户绕过 LLM 直接触发本地动作，但所有 handler 都硬编码在源码里：想加一个 `/commit` 让 Agent 自动分析 diff、生成 message、提交，就得改 Java 再重编。Slash Command 是确定性的快车道，Skill 系统则把可扩展性补上——用户在 `.mewcode/skills//` 或 `~/.mewcode/skills//` 放一个 `SKILL.md`（可选 frontmatter）或 `skill.yaml + prompt.md`，启动时被发现并注册成提示型命令，运行时按 inline 或 fork 模式注入 SOP，让 Agent 借助 LLM 能力完成更复杂的工作流。

## 2. 目标

交付一套进程内的技能编目与执行链路：`SkillCatalog` 两层扫描（用户全局 `~/.mewcode/skills/` + 项目 `.mewcode/skills/`）发现技能；phase-1 仅读 frontmatter 加快启动，`getFull` 触发 phase-2 重读 body 实现热更新；`SkillExecutor` 提供 `executeInline` 与 `executeFork` 两种执行模式，前者把 SOP 注入主 Agent 并按 `allowed_tools` 过滤工具，后者跑隔离的子 Agent，按 `fork_context`（none / recent / full）决定父消息种子；`SkillHost` / `SkillForkHost` 通过接口而非具体类把 Agent 状态切片暴露给 executor，避免 `com.mewcode.skill` 反向依赖 agent 包。`MewCodeModel` 在 provider 就绪后调用 `loadFromDirectory` 加载项目目录，再把每个技能注册为 PROMPT 类型的 Slash Command，输入 `/` 时把 promptBody 当作 user message 发给 LLM，UI 上紧跟 `Successfully loaded skill` 系统消息。

## 3. 功能需求

- F1: `SkillCatalog` 暴露 `register / get / getFull / list / source / reload / loadCatalog / loadFromDirectory / buildActiveContext` 方法，内部 `skills` 与 `sources` 用 `LinkedHashMap` 保序。
- F2: 两层目录加载 `loadCatalog(workDir)`：tier 1 用户 `~/.mewcode/skills/`、tier 2 项目 `/.mewcode/skills/`，按名字后者覆盖前者。
- F3: 单技能加载策略两选一：优先 `skill.yaml + prompt.md`（`loadFromYamlAndPrompt`），否则 `SKILL.md`（`parseSkillMD`，可选 YAML frontmatter，缺描述时回退到 body 第一行非标题）。
- F4: `getFull(name)` 触发热重载：对 `sourceDir != null` 的技能每次重读 body，读失败时保留旧缓存，避免编辑过程中读到半成品。
- F5: `SkillMeta` 字段包含 `name / description / whenToUse / tags / allowedTools / mode / model / forkContext`；name 缺省时取目录名小写化并把空格换 `-`；mode 缺省 `inline`，向后兼容 `context: fork`；`fork_context` 缺省 `none`。
- F6: `SkillExecutor.executeInline(skill, args, host)`：先 `assertAllowedToolsExist` 校验白名单工具均在 `ToolRegistry`；再 `substituteArguments` 渲染 prompt；最后通过 `host.activateSkill` 注入 SOP 并按 `allowed_tools` 调 `host.setToolFilter`，返回渲染后的 body。
- F7: `SkillExecutor.executeFork(skill, args, host)`：构造 prompt + `buildForkSeed` 种子消息，调 `host.runSubAgent` 起隔离子 Agent，把最终 assistant 文本回传。
- F8: `substituteArguments(body, args)`：args 为空原样返回；body 含 `$ARGUMENTS` 时占位符替换；否则追加 `## User Request` 段。
- F9: `buildForkSeed(mode, parent)`：`full` 全量拷贝；`recent` 取尾部最多 5 条；其他（含 `none`）返回空。
- F10: `SkillHost` / `SkillForkHost` 接口：`activateSkill / setToolFilter / toolRegistry` 由 TUI/Agent 层实现；fork 主机额外提供 `runSubAgent / snapshotParentMessages`。
- F11: `MewCodeModel.wireSkillsToAgent` 把 catalog 内每个技能注册为 PROMPT 命令，description 后缀 `[skill]` 用作分支判断；handler 返回 `promptBody`，executeCommand 在 PROMPT 分支把它当 user message。
- F12: PROMPT 分发命中 `[skill]` 后缀时，在 UI 上追加 `skill() Successfully loaded skill` 系统消息，提示用户技能已激活。

### 远程安装
- F13: `InstallSkillTool` 让用户把 URL 发给 mewcode、由 Agent 自动安装到 `~/.mewcode/skills//`
  - 支持三种 URL：`skills.sh` / `github.com tree` / `raw.githubusercontent.com`
  - 走 GitHub Contents API 递归拉取目录树（无需本地 git），单文件 ≤1 MiB、总大小 ≤8 MiB、文件数 ≤64、深度 ≤4
  - 暂存到兄弟 tempdir，验证含 SKILL.md 后 atomic rename 到位
  - 安装后自动 reload catalog + 重新注册斜杠命令，无需重启即可使用

### 远程安装
- F13: `InstallSkillTool` 让用户把 URL 发给 mewcode、由 Agent 自动安装到 `~/.mewcode/skills//`
  - 支持三种 URL：`skills.sh` / `github.com tree` / `raw.githubusercontent.com`
  - 走 GitHub Contents API 递归拉取目录树（无需本地 git），单文件 ≤1 MiB、总大小 ≤8 MiB、文件数 ≤64、深度 ≤4
  - 暂存到兄弟 tempdir，验证含 SKILL.md 后 atomic rename 到位
  - 安装后自动 reload catalog + 重新注册斜杠命令，无需重启即可使用

## 4. 非功能需求

- N1: `loadTier` 必须容错：目录缺失、不可读、单个技能解析失败都不中断其他技能。
- N2: phase-1 加载不能读 body：仅 frontmatter / yaml meta，避免大文件拖慢启动；body 由 `getFull` 按需加载。
- N3: `parseSkillMD` 的 YAML 解析失败要降级到「无 frontmatter」分支而不是抛异常。
- N4: `com.mewcode.skill` 不允许 import `com.mewcode.agent` / `com.mewcode.tui`——通过 `SkillHost` / `SkillForkHost` 接口反向解耦。
- N5: `assertAllowedToolsExist` 在工具未注册时抛 `IllegalStateException`，让上层在执行前暴露配置错误，而不是运行到一半才失败。
- N6: `register(skill)` 允许同名覆盖，调用方按 tier 顺序决定优先级（后注册者胜出）。
- N7: 注册成 PROMPT 命令时 `description` 必须以 `[skill]` 结尾，作为 UI 分支识别 marker。

## 5. 设计概要

- 核心数据结构:
 - `SkillCatalog.Skill`：record(`meta`, `promptBody`, `sourceDir`, `bodyLoaded`)，`withBody` 返回带新 body 的副本
 - `SkillCatalog.SkillMeta`：record(name, description, whenToUse, tags, allowedTools, mode, model, forkContext)
 - `SkillCatalog` 内部 `Map skills` + `Map sources` 全部 `LinkedHashMap`
 - `SkillHost`：`activateSkill(name, body)` + `setToolFilter(Predicate)` + `toolRegistry()`
 - `SkillForkHost extends SkillHost`：追加 `runSubAgent(body, seed, allowedTools, model)` + `snapshotParentMessages()`
- 主流程（启动期）:
 1. `MewCode.main` 装好配置 → 构造 `MewCodeModel`
 2. provider 就绪后（`MewCodeModel` line 494-498）`new SkillCatalog()` + `loadFromDirectory(/.mewcode/skills)`
 3. `wireSkillsToAgent`（line 511-516）遍历 `list()`，对每个 meta 调 `registerSkillCommand`
 4. `registerSkillCommand`（line 518-533）跳过已有命令、把技能注册为 PROMPT 类型的 `Command`，handler 在执行时从 catalog 取 `promptBody`
- 主流程（运行期 inline 模式）:
 1. 用户输入 `/ ` → `executeCommand` → PROMPT 分支
 2. `cmdRegistry.execute` 返回 promptBody → `conversation.addUserMessage(promptBody)` → 若有 args 追加 `conversation.addUserMessage(args)`
 3. `agent.run` 启动新一轮 → UI 推送 `skill() Successfully loaded skill` 系统消息
 4. 后续 turn 与普通 Agent loop 一致
- 主流程（运行期 fork 模式 / Executor 直调）:
 1. 调用方持 `SkillForkHost` 实例，调用 `SkillExecutor.executeFork(skill, args, host)`
 2. `assertAllowedToolsExist` 校验工具白名单 → `substituteArguments` 渲染 prompt
 3. `buildForkSeed(skill.forkContext, host.snapshotParentMessages())` 决定种子消息
 4. `host.runSubAgent` 跑隔离 Agent，回最终文本
- 调用链:
 - 启动: `MewCode.main` → `MewCodeModel` 构造 → provider 就绪回调 → `new SkillCatalog().loadFromDirectory` → `wireSkillsToAgent` → `cmdRegistry.register`
 - 执行 inline: TUI `executeCommand`(PROMPT) → `cmdRegistry.execute` → catalog handler → 返回 promptBody → conversation → agent
 - 执行 fork（programmatic）: 外部调用 `SkillExecutor.executeFork` → `host.runSubAgent`
- 与其他模块的交互:
 - 上行: `com.mewcode.tui.MewCodeModel`（注册 / 分发 / UI 提示）、`com.mewcode.command.CommandRegistry`（命令注册）
 - 下行: `com.mewcode.conversation.Message`（fork 种子）、`com.mewcode.tool.ToolRegistry`（白名单校验）
 - 接口反转: `SkillHost` / `SkillForkHost` 由 TUI / agent 层实现，避免循环依赖

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。