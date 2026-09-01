# ch14: Worktree Spec（Java 版）

## 1. 背景

SubAgent 隔离了消息、权限、工具结果缓存，但所有子 Agent 仍然共享同一个工作目录——两个子 Agent 并发改同一个文件会互相覆盖。Git 分支不解决这个问题：分支只是时间维度的快照，同一时刻整个仓库仍然只有一份 working tree，切换分支会动所有文件的修改时间触发不必要的全量重编。多 Agent 并行要的是空间维度的隔离：同时存在多份独立的 working tree，每份对应不同分支，但共享同一个 `.git`。Git Worktree 提供的就是这个能力。这一章把它接进 dinoCode 的 Java 实现，让主 Agent 和每个子 Agent 都能拥有独立的文件视图。

## 2. 目标

把 worktree 做成两层 API：会话级让 LLM 通过 `EnterWorktreeTool` / `ExitWorktreeTool` 自主进出 worktree，Agent 级让 SubAgent 通过 `isolation: "worktree"` 声明自动获得独立 worktree。底层共用 `WorktreeManager` 的 `git worktree add/remove` 调用、`AgentWorktree` 的快速恢复路径，以及 `PostCreationSetup`（本地配置复制 / git hooks 配置 / 大目录软链接 / `.worktreeinclude` 文件复制）。叠加 `WorktreeChanges` 的 fail-closed 变更检测（无变更才允许清掉、有变更默认保留）和 `StaleCleanup` 对孤儿 worktree 的后台过期清理，保证既不丢用户工作、又不让磁盘堆积。

## 3. 功能需求

- F1: worktree 名称（slug）安全校验：限定字符集、长度上限 64、按 `/` 切段、显式拒绝 `.` / `..` 段，校验失败抛 `IllegalArgumentException` 分类错误（长度 / 段名非法 / 路径遍历）；任何 git 命令或路径拼接之前先跑。
- F2: slug 到路径和分支的映射：用 `+` 替换 `/`（git 安全但不在 slug 字符集），避免嵌套 slug 导致目录或分支命名冲突；分支统一加 `worktree-` 前缀，方便从 `git branch` 输出里识别 MewCode 创建的。
- F3: 快速恢复路径：worktree 目录已存在时跳过 `git worktree add`，用 `Files.isDirectory` + `Files.setLastModifiedTime` bump mtime + 调一次 `git rev-parse HEAD` 拿 SHA；任一步失败回退到完整创建路径。
- F4: git 子进程统一安全壳：所有 `ProcessBuilder` 调用都在 `environment()` 里写 `GIT_TERMINAL_PROMPT=0` 和 `GIT_ASKPASS=`，绝不挂起等待用户输入；用 `waitFor(N, TimeUnit.SECONDS)` 超时保护，超时后 `destroyForcibly()`；进程失败抛 `IOException` 而不是 `RuntimeException`。
- F5: 创建/恢复主入口：`WorktreeManager.create` 接收 branch + 可选 targetDir，未给 targetDir 时默认 `<projectRoot>/.mewcode/worktrees/<branch>`，用大写 `-B` 创建 worktree（容忍上次未清干净的孤儿分支）；`AgentWorktree.create` 在 slug 校验后先看目录是否存在，命中则快速恢复，未命中跑 `git worktree add -B <branch> <path> HEAD`。
- F6: 创建后设置四项：从主仓复制 `.mewcode/settings.local.json`；按 `.husky` > `.git/hooks` 优先级在 worktree 里跑 `git config core.hooksPath <path>`；按 `WorktreeManager.symlinkDirs` 配置软链接 `node_modules` 等目录（跳过含 `..` 项）；按 `.worktreeinclude` gitignore 风格模式复制被 `.gitignore` 忽略但运行需要的文件；任何单项失败只记日志、不中断创建。
- F7: 会话级 API 三件套：进入（`WorktreeManager.create` + 写 `WorktreeSessionStore` 单例 + 持久化 JSON）、Keep（`ExitWorktreeTool action=keep`：清单例 + 删持久化文件，保留 worktree 目录和分支）、Remove（`action=remove`：清单例 + 删持久化 + `WorktreeManager.remove`）。
- F8: 会话持久化：`WorktreeSessionStore.save` 把 `WorktreeSession` record 序列化到 `<repo>/.mewcode/worktree_session.json`，用 Jackson `ObjectMapper` + `@JsonProperty` snake_case 映射；`save(repo, null)` 等价于 `Files.deleteIfExists`。
- F9: 启动恢复：应用启动时调 `WorktreeSessionStore.load(repoRoot)`，非 null 时调 `restoreSession` 写回 `volatile` 全局字段；不主动切 cwd（让用户或工具自行决定），不重跑创建后设置。
- F10: Agent 级 API：`AgentWorktree.create(slug, repoRoot, symlinkDirs)` 静态方法返回 `Result(worktreePath, worktreeBranch, headCommit, gitRoot)` record；不动 `WorktreeSessionStore` 单例、不切 JVM cwd、不写持久化；快速恢复路径要 `Files.setLastModifiedTime` 防被 `StaleCleanup` 误判为孤儿。
- F11: SubAgent 集成：`AgentTool` 在解析参数时拿到 `isolation: "worktree"` 且 `worktreeManager != null` 时，生成 `agent-a<7hex>` slug → 调 `AgentWorktree.create` → 把 `subAgent.setWorkDir(wtResult.worktreePath())` → 在任务 prompt 前面拼 `AgentWorktree.buildNotice(parentCwd, wtPath)` 注入隔离 notice → 跑子 Agent。
- F12: 子 Agent 完成后决策：`LoopComplete` 事件触发时调 `WorktreeChanges.hasChanges(wtPath, headCommit)`，干净自动 `AgentWorktree.remove`、脏则保留并在返回结果末尾附 `"Worktree kept at <path> (branch <branch>) — has uncommitted changes or new commits."`。
- F13: 变更保护：`ExitWorktreeTool` 在 `action="remove"` 且 `discard_changes` 不为 `true` 时跑 `WorktreeChanges.countChanges`——返回 null（状态无法验证）报 `"Could not verify worktree state..."`；`changedFiles > 0` 或 `commits > 0` 报具体数字（"N uncommitted file(s) and M commit(s)"）；要求 LLM 显式传 `discard_changes=true` 才能强删。
- F14: 变更检测 fail-closed：`WorktreeChanges.hasChanges` 在 git status / rev-list 任何一步失败（runGit 返 null 或抛异常）都返 `true`；`countChanges` 在状态拿不到时返 `null`，强制调用方按"未知即不安全"处理。
- F15: LLM Tool 暴露：`EnterWorktreeTool`（input 仅可选 `name`，已有 session 时拒绝 `"Already in a worktree session"`）和 `ExitWorktreeTool`（input `action` 必填枚举 `["keep","remove"]` / `discard_changes` 可选 bool，无 session 时拒绝）；两个 Tool 的 `shouldDefer()` 都返 `true`，由 Agent loop 在工具批次结束时统一执行。
- F16: 临时 worktree 命名模式：用前缀正则区分"自动产物"（`agent-a` / `wf_` / `wf-` / `bridge-` / `job-` 五类）和"用户手动命名"；用户起名永远不会被后台清理动。
- F17: 后台过期清理三层过滤：`StaleCleanup.cleanup` 扫 `<repo>/.mewcode/worktrees/`，依次过滤——L1 `isEphemeral`（不匹配五个正则的跳过）→ L2 时态（跳过当前 session 占用的 + `lastModifiedTime().toInstant().isAfter(cutoff)` 的）→ L3 git 状态 fail-closed（`status --porcelain -uno` 非空或失败跳过 + `rev-list --max-count=1 HEAD --not --remotes` 非空或失败跳过）；删完跑 `git worktree prune` 同步 git 内部表；`startCleanupLoop` 通过 `ScheduledExecutorService.scheduleAtFixedRate` 周期跑。

## 4. 非功能需求

- N1: `WorktreeSessionStore` 用 `volatile` + 静态字段保证并发可见性；`WorktreeManager` 所有公开方法 `synchronized` 保护内存里的 `LinkedHashMap<String, WorktreeInfo>`；Agent 级 API（`AgentWorktree.create/remove`）是无状态静态方法，天然并发安全。
- N2: 任何路径的 worktree 删除（会话级 Remove / Agent 级 Remove / 后台清理）都不在 worktree 内执行 git 命令——`AgentWorktree.remove` 显式从 `gitRoot` 跑 `ProcessBuilder` 的 `directory()`，否则 `git worktree remove` 会因为当前在被删目录里失败。
- N3: `git worktree remove` 和 `git branch -D` 之间必须 `Thread.sleep(100)` 等 git lockfile 释放，否则 branch 删除会偶发失败。
- N4: Agent 级 API 在快速恢复（worktree 目录已存在）时必须 `Files.setLastModifiedTime(wtPath, FileTime.from(Instant.now()))` bump mtime，否则同一 worktree 被反复复用时会因为 mtime 太老被 `StaleCleanup` 误删。
- N5: 三层过滤的执行顺序固定：先廉价的命名模式 → 再时态判断 → 最后贵的 git 检查；任何一层判定保留都 `continue`，不进入下一层。
- N6: `PostCreationSetup` 的四项里软链接和 `.worktreeinclude` 复制是 best-effort——`catch (IOException e)` 只 `log.fine` 不抛、不中断创建，保证主路径鲁棒。
- N7: 变更保护的错误信息必须包含具体数字（N 文件 + M commits）和单复数（"1 file" vs "2 files"、"1 commit" vs "2 commits"），让 LLM 能据此判断要不要强删；不能只回 "has changes" 这种空话。
- N8: worktree 子系统不假设统一日志层存在，所有创建/退出/清理的关键信息通过 `ToolResult` 文本传达；这同时是给 LLM 的运行时反馈，`java.util.logging.Logger` 只用于内部 best-effort 失败。

## 5. 设计概要

- 核心数据结构（全部 Java 17+ `record`）:
  - `WorktreeManager.WorktreeInfo(path, branch, createdAt)`：底层创建路径返回值，挂在 `WorktreeManager` 内存 map 里。
  - `AgentWorktree.Result(worktreePath, worktreeBranch, headCommit, gitRoot)`：Agent 级 API 返回值，不写全局状态。
  - `WorktreeSession(originalCwd, worktreePath, worktreeName, worktreeBranch, originalBranch, originalHeadCommit, sessionId, creationDurationMs)`：会话级单例，Jackson 序列化到磁盘，`@JsonProperty` 写 snake_case key。
  - `WorktreeChanges.ChangeSummary(changedFiles, commits)`：变更计数，供变更保护错误信息生成。
  - 配置块：`WorktreeManager` 构造参数 `symlinkDirs` + `staleCutoffHours`，由应用启动时注入；后台清理由 `StaleCleanup.startCleanupLoop` 单独调度，间隔 ≤ 0 时不启动。
- 主流程:
  - **会话级 Enter**：`EnterWorktreeTool.execute` → guard `WorktreeSessionStore.getCurrentSession() != null` → slug 校验（`SlugValidator.validate`）→ `WorktreeManager.create` → 组装 `WorktreeSession` record → `restoreSession` + `save`。
  - **会话级 Exit**：`ExitWorktreeTool.execute` → guard 无 session → 若 `action=remove && !discard_changes` 跑 `WorktreeChanges.countChanges` 变更保护 → 清单例 → `save(repo, null)` 删持久化 → `action=remove` 时调 `WorktreeManager.remove`。
  - **Agent 级隔离**：`AgentTool.runSync` → `isolation=="worktree" && worktreeManager != null` → 生成 `agent-a<7hex>` slug → `AgentWorktree.create` → `subAgent.setWorkDir(wtPath)` → `prompt = buildNotice(parentCwd, wtPath) + "\n\n" + prompt` → 跑子 Agent → `LoopComplete` 时 `WorktreeChanges.hasChanges`：干净 `AgentWorktree.remove` / 脏拼 `wtInfo` 后缀。
  - **后台过期清理**：`StaleCleanup.startCleanupLoop(executor, repoRoot, intervalSeconds, cutoffHours)` → `scheduleAtFixedRate` → 每轮 `cleanup(repoRoot, Instant.now().minusSeconds(cutoffHours*3600))` → 三层过滤 → 通过的 `AgentWorktree.remove` → 末尾若有删除跑一次 `git worktree prune`。
- 调用链（模块层级）:
  - 应用启动 → 构造 `WorktreeManager(projectRoot, symlinkDirs, staleCutoffHours)` → 注册 `EnterWorktreeTool` 和 `ExitWorktreeTool` → `WorktreeSessionStore.load + restoreSession` 恢复 session → `StaleCleanup.startCleanupLoop` 起后台任务。
  - LLM Enter/Exit → Tool dispatcher → `WorktreeManager` / `WorktreeSessionStore` / `WorktreeChanges`。
  - `AgentTool` → 看到 `isolation: worktree` → `AgentWorktree.create` → 子 Agent 跑完 → `WorktreeChanges.hasChanges` → `AgentWorktree.remove` 或拼字符串保留。
- 与其他模块的交互:
  - 依赖 `com.mewcode.tool`（Tool 接口 + ToolResult + ToolCategory）、`com.mewcode.subagent`（AgentTool 注入 `setWorktreeManager`）、`com.mewcode.agent`（`Agent.setWorkDir`）；底层只依赖 `ProcessBuilder`（git）+ `java.nio.file` + `com.fasterxml.jackson.databind.ObjectMapper`。
  - 不依赖 `com.mewcode.config` 通用加载链路——worktree 配置当前由应用启动时手动注入；也不依赖 `com.mewcode.memory` / `com.mewcode.prompt`。

## 6. Out of Scope

- 不实现非 git VCS 适配（hg / jj / sapling 等），所有 worktree 操作 hardcode 走 `ProcessBuilder("git", ...)`
- 不实现 sparse checkout / partial clone 优化，大型 mono-repo 优化推到后续
- 不实现 `--worktree` CLI 启动快速路径（涉及终端子系统，留给 ch15）
- 不实现 PR fetch 或 pull request 头引用解析（远端协作场景）
- 不实现 prepare-commit-msg hook 注入 commit attribution（商业 feature 场景）
- 不实现 ReadFile / Memory / SystemPrompt 缓存清理 hook（MewCode 当前没有这几类缓存）
- 不引入第三方 gitignore 库（`PostCreationSetup.matchesAnyPattern` 简化匹配够用）
- 团队成员（teammate）路径的 worktree 自动清理推到 ch15 收尾，本章 teammate 路径只创建并隔离、不负责清理

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。