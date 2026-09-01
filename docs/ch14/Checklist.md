# ch14: Worktree Checklist（Java 版）

> 所有条目可勾选、可观测。验收方式写在条目后面括号中。验收：已通过验证的项均勾选。

## 1. 实现完整性

- [ ] 常量 `MAX_LENGTH = 64` 在 `src/main/java/com/mewcode/worktree/SlugValidator.java:11` 定义
- [ ] 正则 `VALID_SEGMENT = ^[a-zA-Z0-9._-]+$` 在 `src/main/java/com/mewcode/worktree/SlugValidator.java:12` 定义
- [ ] 函数 `SlugValidator.validate` 在 `src/main/java/com/mewcode/worktree/SlugValidator.java:16-37` 含空 / 长度 / `.`-`..` / 非法段四类 `IllegalArgumentException`
- [ ] 函数 `SlugValidator.flatten` 在 `src/main/java/com/mewcode/worktree/SlugValidator.java:39` 把 `/` 替换成 `+`；`branchName` 在 `:43` 加 `worktree-` 前缀
- [ ] record `WorktreeManager.WorktreeInfo(path, branch, createdAt)` 在 `src/main/java/com/mewcode/worktree/WorktreeManager.java:25` 定义
- [ ] 函数 `WorktreeManager.create` 在 `src/main/java/com/mewcode/worktree/WorktreeManager.java:51-65` 用大写 `-B` 创建 + 调 `PostCreationSetup.perform` + 写内存 map
- [ ] 函数 `WorktreeManager.remove` 在 `src/main/java/com/mewcode/worktree/WorktreeManager.java:70-78` 跑 `git worktree remove ... --force`
- [ ] 函数 `WorktreeManager.list` 在 `src/main/java/com/mewcode/worktree/WorktreeManager.java:86-97` 先解析 porcelain 输出，失败回退内存 map
- [ ] 函数 `WorktreeManager.parsePorcelain` 在 `src/main/java/com/mewcode/worktree/WorktreeManager.java:211-240` 按 blank line 分块，正确处理 `refs/heads/<branch>` 前缀剥离 + 最后一个块无尾随空行
- [ ] 函数 `WorktreeManager.runGit` 在 `src/main/java/com/mewcode/worktree/WorktreeManager.java:180-200` 用 `waitFor(60, TimeUnit.SECONDS)` 超时 + 退出非 0 抛 `IOException`
- [ ] 函数 `PostCreationSetup.perform` 在 `src/main/java/com/mewcode/worktree/PostCreationSetup.java:19-24` 依序调四项 A/B/C/D
- [ ] 函数 `PostCreationSetup.symlinkDirectories` 在 `src/main/java/com/mewcode/worktree/PostCreationSetup.java:60-73` 跳过含 `..` 项 + `Files.createSymbolicLink` 错误 `log.fine`
- [ ] 函数 `PostCreationSetup.copyWorktreeIncludeFiles` 在 `src/main/java/com/mewcode/worktree/PostCreationSetup.java:75-106` 单文件失败 catch 不中断（异常被外层 try 包裹）
- [ ] 函数 `PostCreationSetup.matchesAnyPattern` 在 `src/main/java/com/mewcode/worktree/PostCreationSetup.java:108-116` 含 exact / basename / dir prefix 三种匹配
- [ ] record `AgentWorktree.Result(worktreePath, worktreeBranch, headCommit, gitRoot)` 在 `src/main/java/com/mewcode/worktree/AgentWorktree.java:20` 定义，不含 sessionId
- [ ] 函数 `AgentWorktree.create` 在 `src/main/java/com/mewcode/worktree/AgentWorktree.java:27-59` 在已存在时 `Files.setLastModifiedTime(wtPath, FileTime.from(Instant.now()))` bump mtime
- [ ] 函数 `AgentWorktree.create` 中 `ProcessBuilder.environment().put("GIT_TERMINAL_PROMPT","0")` 和 `put("GIT_ASKPASS","")` 在 `:45-46`
- [ ] 函数 `AgentWorktree.remove` 在 `src/main/java/com/mewcode/worktree/AgentWorktree.java:64-89` 从 `gitRoot` 跑 `ProcessBuilder.directory()`（不是 wtPath，否则把自己删掉）
- [ ] 函数 `AgentWorktree.remove` 在 `:76` 含 `Thread.sleep(100)` 等 git lockfile 释放
- [ ] 函数 `AgentWorktree.buildNotice` 在 `src/main/java/com/mewcode/worktree/AgentWorktree.java:95-104` 含 `parentCwd` / `worktreeCwd` 占位 + "isolated git worktree" / "translate them" / "Re-read files before editing" / "will not affect the parent's files" 关键句
- [ ] record `WorktreeChanges.ChangeSummary(changedFiles, commits)` 在 `src/main/java/com/mewcode/worktree/WorktreeChanges.java:12` 定义
- [ ] 函数 `WorktreeChanges.hasChanges` 在 `src/main/java/com/mewcode/worktree/WorktreeChanges.java:20-31` 任何异常 catch 后返 true（fail-closed）
- [ ] 函数 `WorktreeChanges.countChanges` 在 `src/main/java/com/mewcode/worktree/WorktreeChanges.java:38-62` `originalHeadCommit` null / blank 时返 null，`NumberFormatException` 时返 null
- [ ] record `WorktreeSession` 在 `src/main/java/com/mewcode/worktree/WorktreeSession.java:11-20` 含 8 字段且 `@JsonProperty` snake_case 标注
- [ ] 类 `WorktreeSession` 标 `@JsonIgnoreProperties(ignoreUnknown = true)` 兼容字段增减
- [ ] 字段 `WorktreeSessionStore.currentSession` 在 `src/main/java/com/mewcode/worktree/WorktreeSessionStore.java:16` 标 `private static volatile`
- [ ] 函数 `WorktreeSessionStore.save` 在 `src/main/java/com/mewcode/worktree/WorktreeSessionStore.java:28-36` session=null 时 `Files.deleteIfExists`
- [ ] 函数 `WorktreeSessionStore.load` 在 `src/main/java/com/mewcode/worktree/WorktreeSessionStore.java:38-48` `IOException` 时返 null
- [ ] 函数 `WorktreeSessionStore.sessionPath` 在 `src/main/java/com/mewcode/worktree/WorktreeSessionStore.java:54-56` 返 `<repo>/.mewcode/worktree_session.json`
- [ ] 变量 `StaleCleanup.EPHEMERAL_PATTERNS` 在 `src/main/java/com/mewcode/worktree/StaleCleanup.java:23-29` 含五个正则
- [ ] 函数 `StaleCleanup.cleanup` 在 `src/main/java/com/mewcode/worktree/StaleCleanup.java:41-88` 三层过滤顺序固定（L1 命名 → L2 时态 → L3 git 状态 fail-closed）
- [ ] 函数 `StaleCleanup.cleanup` 末尾在 `removed > 0` 时跑 `git worktree prune`（`:84-86`）
- [ ] 函数 `StaleCleanup.startCleanupLoop` 在 `src/main/java/com/mewcode/worktree/StaleCleanup.java:93-111` `intervalSeconds <= 0` 直接 return
- [ ] 函数 `StaleCleanup.runGitQuiet` 在 `:113-134` 含 `GIT_TERMINAL_PROMPT=0` + `GIT_ASKPASS` 安全壳
- [ ] 类 `EnterWorktreeTool` 在 `src/main/java/com/mewcode/tool/impl/EnterWorktreeTool.java:17` 实现 `Tool` 接口，含 `worktreeManager` + `sessionId` 字段，`shouldDefer()` 返 true
- [ ] 类 `ExitWorktreeTool` 在 `src/main/java/com/mewcode/tool/impl/ExitWorktreeTool.java:17` 实现 `Tool` 接口，含 `worktreeManager` 字段，`shouldDefer()` 返 true
- [ ] `ExitWorktreeTool.schema` 在 `src/main/java/com/mewcode/tool/impl/ExitWorktreeTool.java:34-55` 含 `action: enum["keep","remove"]`（required）+ `discard_changes?: bool`
- [ ] `ExitWorktreeTool.execute` 在 `:81-95` 实现 file/files 和 commit/commits 单复数正确处理
- [ ] `AgentTool` 字段 `worktreeManager` 在 `src/main/java/com/mewcode/subagent/AgentTool.java:51` 定义，setter `setWorktreeManager` 在 `:98-100`
- [ ] `AgentTool.runSync` 在 `src/main/java/com/mewcode/subagent/AgentTool.java:319-335` 用 `SecureRandom` + `HexFormat.formatHex(...).substring(0,7)` 生成 `agent-a<7hex>` slug
- [ ] `AgentTool.runSync` 在 `:388-399` 完成时按 `WorktreeChanges.hasChanges` 决定保留还是 `AgentWorktree.remove`
- [ ] `AgentTool.runAsTeammate` 在 `src/main/java/com/mewcode/subagent/AgentTool.java:456-472` 创建 worktree + workdir + notice 注入，但**不**自动清理

## 2. 接入完整性（必查，杜绝死代码）

- [ ] `grep -rn "EnterWorktreeTool" --include="*.java" src/` 在应用启动入口（`Main.java` 或 TUI 启动器）找到 `new EnterWorktreeTool(...)` 注册调用
- [ ] `grep -rn "ExitWorktreeTool" --include="*.java" src/` 在应用启动入口找到 `new ExitWorktreeTool(...)` 注册调用
- [ ] `grep -rn "WorktreeSessionStore.load" --include="*.java" src/` 在应用启动入口找到调用方
- [ ] `grep -rn "WorktreeSessionStore.restoreSession" --include="*.java" src/` 同时在 `EnterWorktreeTool` / `ExitWorktreeTool` / 启动恢复处找到调用
- [ ] `grep -rn "StaleCleanup.startCleanupLoop" --include="*.java" src/` 在应用启动入口找到调用方
- [ ] `grep -rn "AgentWorktree.create" --include="*.java" src/` 在 `src/main/java/com/mewcode/subagent/AgentTool.java:325` 和 `:463` 找到两处调用（runSync + runAsTeammate）
- [ ] `grep -rn "AgentWorktree.buildNotice" --include="*.java" src/` 同上两处调用（runSync 在 `:329`，runAsTeammate 在 `:466`）
- [ ] `grep -rn "WorktreeChanges.hasChanges" --include="*.java" src/` 在 `src/main/java/com/mewcode/subagent/AgentTool.java:391` 找到主流程调用方（决定 remove 还是保留）
- [ ] `grep -rn "AgentWorktree.remove" --include="*.java" src/` 在 `AgentTool.java:396` 和 `StaleCleanup.java:76` 找到调用方
- [ ] `grep -rn "WorktreeChanges.countChanges" --include="*.java" src/` 在 `src/main/java/com/mewcode/tool/impl/ExitWorktreeTool.java:74` 找到唯一调用方（变更保护错误信息）
- [ ] `grep -rn "setWorktreeManager" --include="*.java" src/` 在应用启动入口找到注入调用（把 WorktreeManager 注入 AgentTool）

## 3. 编译与测试

- [ ] `./gradlew build` 通过
- [ ] `./gradlew test --tests "com.mewcode.worktree.*"` 通过（SlugValidator / WorktreeManager / PostCreationSetup / AgentWorktree / WorktreeChanges / StaleCleanup / WorktreeSessionStore 各对应测试 PASS）
- [ ] `./gradlew test --tests "com.mewcode.subagent.*"` 通过（含 isolation 集成测试）
- [ ] `./gradlew test --tests "com.mewcode.tool.impl.EnterWorktreeToolTest"` 和 `ExitWorktreeToolTest` 通过

## 4. 端到端验证

- [ ] **路径 A — 工具直接驱动**：用户对主 Agent 说"用 EnterWorktree 工具创建一个名叫 demo 的工作树" → LLM 调 `EnterWorktree({name:"demo"})` → 返回 `Created worktree at .../.mewcode/worktrees/demo on branch worktree-demo. The session is now working in the worktree. Use ExitWorktree to leave mid-session.`；让 Agent 在 worktree 里创建 `hello.txt` 并 `git commit`；让 Agent 调 `ExitWorktree({action:"remove"})` → 因有未推送 commit 被变更保护拒绝，错误文本包含具体 file/commit 数和单复数；`ExitWorktree({action:"remove", discard_changes:true})` 强删成功；`ls .mewcode/worktrees/` 看到 `demo/` 已消失。
- [ ] **路径 B — 子 Agent 自动隔离**：用户让主 Agent 在主目录建 `witness.txt`（内容 "original content from main agent"）→ 调 `Agent({subagent_type:"general-purpose", isolation:"worktree", description:"...", prompt:"把 witness.txt 改成 \"modified by isolated worker\"，然后 git 提交"})`；验证 `cat witness.txt` 主目录内容仍是 "original ..."；`cat .mewcode/worktrees/agent-a*/witness.txt` 是修改后版本；若子 Agent 有 commit → 结果末尾出现 `"Worktree kept at ... (branch worktree-agent-a...) — has uncommitted changes or new commits."`；若无修改 → worktree 自动清理（`.mewcode/worktrees/` 下 `agent-a*` 目录消失）。
- [ ] **持久化与 crash 恢复**：`EnterWorktree({name:"crashtest"})` 创建 worktree → `kill -9` 杀 JVM 进程 → `cat .mewcode/worktree_session.json` 文件仍在并含 crashtest 会话；重启应用 → 启动期间 `WorktreeSessionStore.load + restoreSession` 将 session 写回全局 `volatile` 字段；下一次工具调用时 `WorktreeSessionStore.getCurrentSession()` 非 null。
- [ ] **变更保护单复数**：在 worktree 里建 1 个未提交修改 → `ExitWorktree({action:"remove"})` 返回 `"1 uncommitted file"`；建 2+ 个修改 → 返回 `"N uncommitted files"`；同样验证 commit 数的单复数（`"1 commit"` / `"N commits"`）。
- [ ] **后台清理保守不删**：手动在 `.mewcode/worktrees/agent-aabcdef1/` 下建一个有未推送 commit 的目录（mtime 设为过期前）→ 等 cleanup loop 跑一轮（或手动调 `StaleCleanup.cleanup(repoRoot, Instant.now())` 测试）→ 该目录仍保留（L3 fail-closed 拦住）。
- [ ] **用户命名永不删**：在 `.mewcode/worktrees/my-feature/` 下建一个目录（mtime 设为非常老）→ 跑 cleanup → 目录仍保留（L1 命名过滤拦住）。

## 5. 文档

- [ ] `docs/java/ch14/spec.md` 已按 ch13 风格写完（F1-F17 + N1-N8，无 file:line 代码标注）
- [ ] `docs/java/ch14/tasks.md` 已写，13 个 T 全部勾完（T1-T13）
- [ ] `docs/java/ch14/checklist.md` 已写并逐项验收
- [ ] commit 信息标注 `ch14`，新增代码的调用链已在 PR 描述或 commit message 里说明