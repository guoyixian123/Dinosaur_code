# Skill 系统 Checklist

## 1. 实现完整性

- [ ] `SkillCatalog.SkillMeta` record 在 `src/main/java/com/mewcode/skill/SkillCatalog.java:24-33` 含 `name / description / whenToUse / tags / allowedTools / mode / model / forkContext` 八个字段
- [ ] `SkillCatalog.Skill` record 在 `SkillCatalog.java:35-39` 含 `meta / promptBody / sourceDir / bodyLoaded`，提供 `withBody` 副本构造
- [ ] `SkillCatalog` 状态在 `SkillCatalog.java:43-45`：`skills / sources` 全部 `LinkedHashMap` 保序
- [ ] `register / get / getFull / list / source / reload / loadFromDirectory` 在 `SkillCatalog.java:49-158` 实现
- [ ] `getFull` 在 `SkillCatalog.java:71-89` 触发 phase-2 热重载，sourceDir 为 null 直接返回缓存，读失败 `IOException ignored` 后保留旧缓存
- [ ] `loadCatalog(workDir)` 在 `SkillCatalog.java:107-123` 按 tier1 `~/.mewcode/skills/` → tier2 `/.mewcode/skills/` 顺序加载
- [ ] `loadTier` 在 `SkillCatalog.java:142-158` 容错：目录不存在 / list 抛 IOException 都静默跳过
- [ ] `loadSkill(dir)` 在 `SkillCatalog.java:184-199` 优先 `skill.yaml + prompt.md`，否则 `SKILL.md`，都不存在返回 null
- [ ] `parseSkillMD` 在 `SkillCatalog.java:221-262` 处理可选 YAML frontmatter；YAML 解析失败降级为「无 frontmatter」；缺描述时从 body 第一行非标题行回退
- [ ] `metaFromMap` 在 `SkillCatalog.java:264-313`：name 缺省取目录名小写+空格换 `-`；mode 缺省 `inline` 并兼容 `context: fork`；`fork_context` 缺省 `none`
- [ ] `buildActiveContext(activeSkillNames)` 在 `SkillCatalog.java:166-180` 拼 `## Active Skills` 段，空集合返回 ""
- [ ] `SkillHost` 接口在 `src/main/java/com/mewcode/skill/SkillHost.java:12-19` 提供 `activateSkill / setToolFilter / toolRegistry`
- [ ] `SkillForkHost extends SkillHost` 在 `src/main/java/com/mewcode/skill/SkillForkHost.java:12-17` 追加 `runSubAgent / snapshotParentMessages`
- [ ] `SkillExecutor.executeInline` 在 `src/main/java/com/mewcode/skill/SkillExecutor.java:25-37` 顺序：`assertAllowedToolsExist` → `substituteArguments` → `activateSkill` → 按 `allowed_tools` 调 `setToolFilter`
- [ ] `SkillExecutor.executeFork` 在 `SkillExecutor.java:43-48` 顺序：校验 → 渲染 → `buildForkSeed` → `runSubAgent`
- [ ] `substituteArguments` 在 `SkillExecutor.java:50-58`：args 空白原样返回；含 `$ARGUMENTS` 占位符替换；否则追加 `## User Request` 段
- [ ] `buildForkSeed` 在 `SkillExecutor.java:60-74`：`full` 全量、`recent` 取尾 5 条（`FORK_RECENT_COUNT = 5`）、其他（含 `none`）返回 `List.of()`
- [ ] `assertAllowedToolsExist` 在 `SkillExecutor.java:76-88` 工具未注册时抛 `IllegalStateException`
- [ ] 边界处理: 空目录、目录不存在、坏 yaml、`allowed_tools` 为空都不抛异常

## 2. 接入完整性

- [ ] `grep -rn "new SkillCatalog" --include="*.java" ./src` 命中 `MewCodeModel.java:494` 的非测试调用
- [ ] `grep -rn "skillCatalog.loadFromDirectory" --include="*.java" ./src` 命中 `MewCodeModel.java:497`
- [ ] `grep -rn "wireSkillsToAgent" --include="*.java" ./src` 命中 `MewCodeModel.java:500` / `MewCodeModel.java:511`
- [ ] 字段 `skillCatalog` 在 `MewCodeModel.java:102`；provider 就绪后初始化 `MewCodeModel.java:494-498`
- [ ] `registerSkillCommand(name)` 在 `MewCodeModel.java:518-533`：跳过已存在命令、注册 PROMPT 类型 `Command`、description 后缀 `[skill]`、handler 从 catalog 取 promptBody
- [ ] PROMPT 分发的 skill 分支在 `MewCodeModel.java:928-967`：`isSkill = cmd.description().endsWith("[skill]")`，命中后在 UI 上 println `skill() Successfully loaded skill`
- [ ] `/skills` 命令 handler 在 `src/main/java/com/mewcode/command/CommandRegistry.java:255-265` 列出 `skillList` supplier 返回的技能名
- [ ] `skillList` supplier 在 `MewCodeModel.java:984-986`：`skillCatalog != null` 时返回 `list().stream().map(s -> s.name()).toList()`
- [ ] 入口路径：用户输入 `/` → `executeCommand`（MewCodeModel）→ PROMPT 分支 → `cmdRegistry.execute` 返回 promptBody → `conversation.addUserMessage` → `agent.run`

## 3. 编译与测试

- [ ] `cd . && ./gradlew build` 通过
- [ ] `cd . && ./gradlew compileJava` 无警告
- [ ] `com.mewcode.skill` 包不 import `com.mewcode.agent` / `com.mewcode.tui`，仅通过 `SkillHost` / `SkillForkHost` 接口与外界交互

### 远程安装

- [ ] `SkillInstaller.parseSkillURL` 支持三种 URL 格式
- [ ] `SkillInstaller.install` 走 GitHub Contents API 递归拉取，atomic rename
- [ ] 限额常量 maxFileSize / maxTotalSize / maxFileCount / maxRecursionDepth
- [ ] 下载完没有 SKILL.md 时拒绝安装并清理 staging
- [ ] `InstallSkillTool` name = InstallSkill，category = write
- [ ] 执行成功后调 catalog.reload() + onInstalled 回调

## 4. 端到端验证

- [ ] 启动 MewCode 后输入 `/skills`，若 `.mewcode/skills/` 下无技能则提示 `No skills installed.\n\nAdd skills to .mewcode/skills//SKILL.md`（`CommandRegistry.java:260`）
- [ ] 创建测试技能目录 `.mewcode/skills/test-skill/SKILL.md`，写入简单的 inline SOP（如 `name: test-skill`、`description: a test skill`、body 为一段简单指令），验证以下三点：1) `/help` 显示 `/test-skill`；2) `/test-skill` 加载 SOP，UI 显示 `skill(test-skill) Successfully loaded skill`；3) 修改 `SKILL.md` 内容后不重启，再次执行 `/test-skill` 验证热重载生效（通过 `getFull` 重读到新内容）

## 5. 文档

- [ ] `docs/java/ch11/spec.md` 存在
- [ ] `docs/java/ch11/tasks.md` 存在
- [ ] `docs/java/ch11/checklist.md` 存在
- [ ] Java 实现位于 `origin/java` 分支，包路径 `com.mewcode.skill` / `com.mewcode.command`