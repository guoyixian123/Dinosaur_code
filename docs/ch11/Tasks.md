# Skill 系统 Tasks

## T1: 定义 SkillCatalog 数据类型与状态
- 影响文件: `src/main/java/com/mewcode/skill/SkillCatalog.java`
- 依赖任务: 无
- 完成标准: `SkillMeta` / `Skill` record 字段齐全；`Skill.withBody` 副本构造可用；内部 `skills` / `sources` 用 `LinkedHashMap` 保序；`register / get / list / source` 行为对齐参考。
- 实际产出: `SkillCatalog.java:24-39`（record）、`SkillCatalog.java:43-45`（state）、`SkillCatalog.java:49-97`（公共方法）

## T2: 实现单技能加载策略
- 影响文件: `src/main/java/com/mewcode/skill/SkillCatalog.java`
- 依赖任务: T1
- 完成标准: `loadSkill(dir)` 优先 `skill.yaml + prompt.md`，否则 `SKILL.md`；`loadFromYamlAndPrompt` 用 snakeyaml 解析 meta + 读取 prompt.md；`parseSkillMD` 处理可选 frontmatter，缺描述时回退到 body 第一行非标题行。
- 实际产出: `SkillCatalog.java:184-199`（loadSkill）、`SkillCatalog.java:201-219`（loadFromYamlAndPrompt）、`SkillCatalog.java:221-262`（parseSkillMD）、`SkillCatalog.java:264-313`（metaFromMap）

## T3: 实现两层目录加载与热重载
- 影响文件: `src/main/java/com/mewcode/skill/SkillCatalog.java`
- 依赖任务: T1, T2
- 完成标准: `loadCatalog(workDir)` 按用户 → 项目顺序加载，后者覆盖前者；`loadTier` 容错；`getFull` 触发 phase-2 重读 body，读失败保留旧缓存；`reload(workDir)` 整体刷新。
- 实际产出: `SkillCatalog.java:107-123`（loadCatalog）、`SkillCatalog.java:125-132`（reload）、`SkillCatalog.java:138-158`（loadFromDirectory + loadTier）、`SkillCatalog.java:66-89`（getFull）

## T4: 定义 SkillHost / SkillForkHost 接口
- 影响文件: `src/main/java/com/mewcode/skill/SkillHost.java`, `src/main/java/com/mewcode/skill/SkillForkHost.java`
- 依赖任务: 无
- 完成标准: `SkillHost.activateSkill(name, body) / setToolFilter(Predicate) / toolRegistry()`；`SkillForkHost extends SkillHost` 增加 `runSubAgent(body, seed, allowedTools, model) / snapshotParentMessages()`。
- 实际产出: `SkillHost.java:12-19`、`SkillForkHost.java:12-17`

## T5: 实现 SkillExecutor（inline / fork 双模式）
- 影响文件: `src/main/java/com/mewcode/skill/SkillExecutor.java`
- 依赖任务: T1, T4
- 完成标准: `executeInline(skill, args, host)` 校验工具白名单 + 渲染 prompt + `activateSkill` + `setToolFilter`；`executeFork(skill, args, host)` 渲染 prompt + `buildForkSeed` + `runSubAgent`；`substituteArguments` 处理 `$ARGUMENTS` 占位符与缺占位符追加 `## User Request`；`buildForkSeed` 支持 `none / recent (≤5) / full`。
- 实际产出: `SkillExecutor.java:25-37`（executeInline）、`SkillExecutor.java:43-48`（executeFork）、`SkillExecutor.java:50-58`（substituteArguments）、`SkillExecutor.java:60-74`（buildForkSeed）、`SkillExecutor.java:76-88`（assertAllowedToolsExist）

## T6: buildActiveContext 系统提示注入助手
- 影响文件: `src/main/java/com/mewcode/skill/SkillCatalog.java`
- 依赖任务: T1
- 完成标准: `buildActiveContext(Set activeSkillNames)` 在系统提示里拼 `## Active Skills` 段 + 每个技能的 `### name` + body；空集合返回空串。
- 实际产出: `SkillCatalog.java:166-180`

## T7: 接入主流程 —— TUI 加载技能 / 注册为命令
- 影响文件: `src/main/java/com/mewcode/tui/MewCodeModel.java`
- 依赖任务: T1, T3
- 完成标准: provider 就绪后构造 `SkillCatalog` + `loadFromDirectory(/.mewcode/skills)`；`wireSkillsToAgent` 遍历 `list()` 调 `registerSkillCommand`；`registerSkillCommand` 跳过已存在命令，注册 PROMPT 类型 `Command`，description 以 `[skill]` 结尾，handler 从 catalog 取 `promptBody`。
- 实际产出: `MewCodeModel.java:102`（字段）、`MewCodeModel.java:494-500`（加载）、`MewCodeModel.java:511-516`（wireSkillsToAgent）、`MewCodeModel.java:518-533`（registerSkillCommand）

## T8: 接入主流程 —— PROMPT 分发的 skill 分支
- 影响文件: `src/main/java/com/mewcode/tui/MewCodeModel.java`
- 依赖任务: T7
- 完成标准: `executeCommand` 命中 PROMPT 类型时判断 description 是否以 `[skill]` 结尾；是则把 promptBody 当 user message 推入 conversation、附加 args、起 agent.run，并在 UI 上 println `skill() Successfully loaded skill`；`/skills` 命令列出当前 catalog。
- 实际产出: `MewCodeModel.java:928-967`（PROMPT 分支）、`CommandRegistry.java:255-265`（/skills handler）、`MewCodeModel.java:984-986`（skillList supplier）

## T9: 端到端验证
- 影响文件: 无
- 依赖任务: T7, T8
- 完成标准: `./gradlew build` 通过；在 `.mewcode/skills/demo/SKILL.md` 放最小 frontmatter（name: demo, description: demo skill）+ body，启动 MewCode 后 `/skills` 列出 `demo`；输入 `/demo hello` 触发 PROMPT 分发，UI 显示 `skill(demo) Successfully loaded skill`，Agent 收到 promptBody + `hello` 作为新对话起点；`origin/java` 仓库已自带 `.mewcode/skills/skill-creator/SKILL.md` 可作真实样本。
- 实际产出: `./gradlew build` 全绿、`MewCodeModel.java:494-500` 启动加载、`MewCodeModel.java:961-965` UI 提示

## T10: InstallSkill 远程安装

- 影响文件: `SkillInstaller.java`（新建）、`InstallSkillTool.java`（新建）、`SkillSource.java`（新建）、`InstallReport.java`（新建）、`MewCodeModel.java`（修改）
- 依赖任务: T3（Catalog）、T7（TUI 加载技能）
- 完成标准:
  - `SkillInstaller.parseSkillURL(url)` 支持 skills.sh / github.com tree / raw.githubusercontent.com 三种 URL
  - `SkillInstaller.install(src, installRoot)` 走 GitHub Contents API（java.net.http）递归下载到 staging temp dir，验证含 SKILL.md 后 atomic rename
  - 限额：单文件 ≤1 MiB、总大小 ≤8 MiB、文件数 ≤64、深度 ≤4
  - `InstallSkillTool` 实现 Tool 接口，name = InstallSkill，category = write
  - 执行后调 catalog.reload() + onInstalled 回调

## 进度
- [ ] T1
- [ ] T2
- [ ] T3
- [ ] T4
- [ ] T5
- [ ] T6
- [ ] T7
- [ ] T8
- [ ] T9
- [ ] T10