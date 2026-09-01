# ch14: Worktree Tasks（Java 版）

> 任务粒度：每个任务可在一次会话内完成，可独立交付。

## T1: 实现 Slug 校验 + 命名映射
- 影响文件: `src/main/java/com/mewcode/worktree/SlugValidator.java`（`MAX_LENGTH` @ 11；`VALID_SEGMENT` @ 12；`validate` @ 16-37；`flatten` @ 39-41；`branchName` @ 43-45）
- 依赖任务: 无
- 完成标准: `validate(String slug)` 校验长度 ≤ 64、按 `/` 切段、每段匹配 `^[a-zA-Z0-9._-]+$`、显式拒绝 `.` / `..` 段，错误分类（cannot be empty / 长度 / `.` `..` 段 / 非法段）通过 `IllegalArgumentException` 抛出；`flatten(s) = s.replace('/', '+')`；`branchName(s) = "worktree-" + flatten(s)`；类声明为 `final`，构造私有，只暴露静态方法。
- [ ] 完成

## T2: 实现 git 进程执行壳
- 影响文件: `src/main/java/com/mewcode/worktree/WorktreeManager.java`（`runGit` @ 180-200）、`src/main/java/com/mewcode/worktree/WorktreeChanges.java`（`runGit` @ 64-87）、`src/main/java/com/mewcode/worktree/StaleCleanup.java`（`runGitQuiet` @ 113-134）、`src/main/java/com/mewcode/worktree/AgentWorktree.java`（`readHead` @ 106-118）
- 依赖任务: 无
- 完成标准: 所有 `ProcessBuilder` 调用前在 `environment()` put `GIT_TERMINAL_PROMPT=0` 和 `GIT_ASKPASS=""`（`WorktreeChanges.runGit` @ 72-73、`StaleCleanup.runGitQuiet` @ 120-121、`AgentWorktree.create` @ 45-46）；用 `waitFor(N, TimeUnit.SECONDS)` 超时保护（30 或 60 秒），未完成时 `destroyForcibly()`；进程退出非 0 时按调用约定要么抛 `IOException`（`WorktreeManager.runGit` @ 196-198）要么返 `null`（`WorktreeChanges.runGit` @ 83）。
- [ ] 完成

## T3: 实现 WorktreeManager 主入口
- 影响文件: `src/main/java/com/mewcode/worktree/WorktreeManager.java`（`WorktreeInfo` record @ 25；构造 @ 32-36；`create` @ 51-65；`remove` @ 70-78；`list` @ 86-97；`cleanupStale` @ 112-132；`detectChanges` @ 156-176；`parsePorcelain` @ 211-240）
- 依赖任务: T2
- 完成标准: `WorktreeInfo(path, branch, createdAt)` record；构造接收 `projectRoot` + `symlinkDirs`（null 容忍为 `List.of()`）+ `staleCutoffHours`（<=0 时默认 24）；`create(branch, targetDir)` 在 `targetDir==null` 时默认 `<projectRoot>/.mewcode/worktrees/<branch>`，调 `git worktree add -B <branch> <wtDir>` 大写 `-B` 容忍孤儿分支，成功后调 `PostCreationSetup.perform` 跑四项设置，最后把 `WorktreeInfo` 放进 `LinkedHashMap`；`remove(branch)` 拿出 map 项跑 `git worktree remove <path> --force` 然后 `worktrees.remove(branch)`；`list()` 优先解析 `git worktree list --porcelain` 输出（`parsePorcelain` 按 blank line 分块），失败回退内存 map；所有公开方法 `synchronized`。
- [ ] 完成

## T4: 实现 PostCreationSetup 四项
- 影响文件: `src/main/java/com/mewcode/worktree/PostCreationSetup.java`（`perform` @ 19-24；`copySettingsLocal` @ 26-36；`configureHooksPath` @ 38-58；`symlinkDirectories` @ 60-73；`copyWorktreeIncludeFiles` @ 75-106；`matchesAnyPattern` @ 108-116）
- 依赖任务: 无
- 完成标准: `perform(repoRoot, worktreePath, symlinkDirs)` 依次跑四项；`copySettingsLocal` 复制 `<repo>/.mewcode/settings.local.json`（不存在静默 return），失败 `log.fine`；`configureHooksPath` 优先 `.husky` 回退 `.git/hooks`，找到第一个存在目录后在 worktree 目录里跑 `git config core.hooksPath <hooksPath>`；`symlinkDirectories` 跳过含 `..` 项 + 跳过 src 不存在或 dst 已存在的 + `Files.createSymbolicLink(dst, src)` 错误 `log.fine`；`copyWorktreeIncludeFiles` 读 `.worktreeinclude` 按行收集（跳空行和 `#`）→ 在 repoRoot 跑 `git ls-files --others --ignored --exclude-standard --directory` → 对每行（跳目录和空）`matchesAnyPattern` 判定后 `Files.createDirectories(dst.getParent()) + Files.copy(src, dst)`；`matchesAnyPattern` 支持去前导 `/` 后 exact / basename / dir prefix 三种匹配。
- [ ] 完成

## T5: 实现变更检测 fail-closed
- 影响文件: `src/main/java/com/mewcode/worktree/WorktreeChanges.java`（`ChangeSummary` record @ 12；`hasChanges` @ 20-31；`countChanges` @ 38-62；`runGit` @ 64-87）
- 依赖任务: T2
- 完成标准: `ChangeSummary(changedFiles, commits)` record；`hasChanges(wtPath, headCommit)` — `git status --porcelain` 非 null 非空 → true；`git rev-list --count <headCommit>..HEAD` 为 null 或解析后 > 0 → true；任何异常 catch 后返 `true`（**fail-closed**）。`countChanges(wtPath, originalHeadCommit)` — `originalHeadCommit==null||isBlank` 返 null；`status --porcelain` 返 null 时返 null，否则按 `\n` 切并数非空行；`rev-list --count` 返 null 或 `NumberFormatException` 时返 null；否则返 `new ChangeSummary(changedFiles, commits)`。
- [ ] 完成

## T6: 实现 AgentWorktree 静态 API
- 影响文件: `src/main/java/com/mewcode/worktree/AgentWorktree.java`（`Result` record @ 20；`create` @ 27-59；`remove` @ 64-89；`buildNotice` @ 95-104；`readHead` @ 106-118）
- 依赖任务: T1, T2, T4
- 完成标准: `Result(worktreePath, worktreeBranch, headCommit, gitRoot)` record；`create(slug, repoRoot, symlinkDirs)` — `SlugValidator.validate` → `wtPath = <repoRoot>/.mewcode/worktrees/<flatten(slug)>` + `branch = "worktree-" + flatten(slug)` → `Files.isDirectory(wtPath)` 时快速恢复（`Files.setLastModifiedTime(wtPath, FileTime.from(Instant.now()))` bump mtime + `readHead`）→ 否则 `Files.createDirectories(wtPath.getParent())` + `ProcessBuilder("git","worktree","add","-B",branch,wtPath,"HEAD")` + `PostCreationSetup.perform` → 返 `Result`；**不动 `WorktreeSessionStore`、不切 JVM cwd、不写持久化**。`remove(wtPath, wtBranch, gitRoot)` — gitRoot 空返 false → `ProcessBuilder` 从 `gitRoot.toFile()` 跑 `git worktree remove --force <wtPath>`（**不**从 wtPath 否则把自己删掉）→ 成功后 `Thread.sleep(100)` 等 lockfile → 分支非空跑 `git branch -D <branch>` → 返 true；异常时 `log.fine` 后返 false。`buildNotice(parentCwd, worktreeCwd)` 返固定模板字符串含 `parentCwd` / `worktreeCwd` 占位 + "isolated git worktree" / "translate them" / "Re-read files before editing" / "will not affect the parent's files" 关键句。
- [ ] 完成

## T7: 实现 WorktreeSession + Store
- 影响文件: `src/main/java/com/mewcode/worktree/WorktreeSession.java`（record @ 11-20）、`src/main/java/com/mewcode/worktree/WorktreeSessionStore.java`（`MAPPER` @ 15；`currentSession` @ 16；`getCurrentSession` @ 20；`restoreSession` @ 24；`save` @ 28-36；`load` @ 38-48；`sessionPath` @ 54-56）
- 依赖任务: 无
- 完成标准: `WorktreeSession` Java record，8 字段 + Jackson `@JsonProperty` snake_case：`original_cwd` / `worktree_path` / `worktree_name` / `worktree_branch` / `original_branch` / `original_head_commit` / `session_id` / `creation_duration_ms`；类标注 `@JsonIgnoreProperties(ignoreUnknown = true)` 兼容字段增减。`WorktreeSessionStore` 用 `private static volatile WorktreeSession currentSession` 保证并发可见；`getCurrentSession` 直接返字段；`restoreSession(WorktreeSession)` 直接写字段（也接受 null 清除）；`save(repoRoot, session)` — session=null 时 `Files.deleteIfExists(sessionPath)`，否则 `Files.createDirectories(parent) + MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, session)`；`load(repoRoot)` 读 `.mewcode/worktree_session.json`，不存在返 null，反序列化 `IOException` 返 null；`sessionPath = <repo>/.mewcode/worktree_session.json`。
- [ ] 完成

## T8: 实现 EnterWorktreeTool
- 影响文件: `src/main/java/com/mewcode/tool/impl/EnterWorktreeTool.java`（`worktreeManager / sessionId / RANDOM` @ 19-21；构造 @ 23-26；`name / category / shouldDefer / description` @ 28-35；`schema` @ 37-52；`execute` @ 54-91）
- 依赖任务: T1, T3, T7
- 完成标准: 实现 `Tool` 接口；`name()="EnterWorktree"`、`category()=ToolCategory.COMMAND`、`shouldDefer()=true`；input schema 仅 `name: string`（可选，max 64 chars 提示）；`execute` guard `WorktreeSessionStore.getCurrentSession() != null` → `ToolResult.error("Already in a worktree session")`；`name` 缺省时用 `RANDOM.nextInt()` 生成 `"wt-" + Integer.toHexString(...)`；`SlugValidator.validate` 失败时返 error；调 `worktreeManager.create(slug, null)` → 组装 `WorktreeSession(System.getProperty("user.dir"), info.path(), slug, info.branch(), "", "", sessionId, 0)` → `restoreSession + save` → 返 `ToolResult.success("Created worktree at <path> on branch <branch>. The session is now working in the worktree. Use ExitWorktree to leave mid-session.")`。
- [ ] 完成

## T9: 实现 ExitWorktreeTool
- 影响文件: `src/main/java/com/mewcode/tool/impl/ExitWorktreeTool.java`（`worktreeManager` @ 19；构造 @ 21-23；`name / category / shouldDefer / description` @ 25-32；`schema` @ 34-55；`execute` @ 57-121）
- 依赖任务: T3, T5, T7
- 完成标准: 实现 `Tool` 接口；`name()="ExitWorktree"`、`shouldDefer()=true`；input schema `action: enum["keep","remove"]`（required）+ `discard_changes?: bool`；`execute` scope guard：`getCurrentSession()==null` → `ToolResult.error("No-op: there is no active EnterWorktree session to exit. This tool only operates on worktrees created by EnterWorktree in the current session.")`；变更保护：`action="remove" && !discard_changes` 时 `WorktreeChanges.countChanges`：null 报 `"Could not verify worktree state. Refusing to remove without explicit confirmation. Re-invoke with discard_changes: true, or use action: \"keep\"."`；`changedFiles>0 || commits>0` 时按部分拼接 — `changedFiles==1 ? "file" : "files"` + `commits==1 ? "commit" : "commits"` 单复数正确，用 `String.join(" and ", parts)`；`restoreSession(null) + save(repoRoot, null)`（save 失败 swallow）；`action="remove"` 调 `worktreeManager.remove(session.worktreeName())` 失败返 error，成功返 `"Exited and removed worktree at <path>. Session is now back in <originalCwd>."`；`action="keep"` 返 `"Exited worktree. Your work is preserved at <path>. Session is now back in <originalCwd>."`。
- [ ] 完成

## T10: 接入 SubAgent isolation（AgentTool.runSync）
- 影响文件: `src/main/java/com/mewcode/subagent/AgentTool.java`（`worktreeManager` 字段 @ 51；`setWorktreeManager` @ 98-100；`isolation` schema @ 176-180；`execute` 解析 `isolation` @ 228；`runSync` worktree 分支 @ 310-335 和 388-399；`runAsTeammate` worktree 分支 @ 456-472）
- 依赖任务: T5, T6, T3
- 完成标准: `AgentTool.schema()` 中 `properties.put("isolation", Map.of("type","string","enum", List.of("worktree"), ...))`；`execute` 调 `getStringArg(args, "isolation")` 解析；`runSync(spec, description, prompt, modelOverride, isolation)` 在 `"worktree".equals(isolation) && worktreeManager != null` 时：
  1. 用 `SecureRandom` 生成 4 字节 → `HexFormat.of().formatHex(rndBytes).substring(0,7)` → `slug = "agent-a" + 7hex`（匹配 cleanup 正则 `^agent-a[0-9a-f]{7}$`）；
  2. `wtResult = AgentWorktree.create(slug, worktreeManager.getProjectRoot(), worktreeManager.getSymlinkDirs())`；
  3. `subAgent.setWorkDir(wtResult.worktreePath())`；
  4. `notice = AgentWorktree.buildNotice(System.getProperty("user.dir"), wtResult.worktreePath())`；
  5. `prompt = notice + "\n\n" + prompt`；
  6. 创建失败 → `return ToolResult.error("Error creating agent worktree: " + e.getMessage())`；
  `LoopComplete` 事件处理时（`wtResult != null` 分支）调 `WorktreeChanges.hasChanges(wtResult.worktreePath(), wtResult.headCommit())`：true → `wtInfo = "\n\nWorktree kept at <path> (branch <branch>) — has uncommitted changes or new commits."`；false → `AgentWorktree.remove(wtResult.worktreePath(), wtResult.worktreeBranch(), wtResult.gitRoot())`；最后 `result + wtInfo` 拼回。`runAsTeammate` 在 `"worktree".equals(isolation)` 时执行同样三步（创建 + workdir + notice 注入），但**不**做完成后自动清理（teammate 长生命周期，留给 ch15 收尾）。
- [ ] 完成

## T11: 实现后台过期清理
- 影响文件: `src/main/java/com/mewcode/worktree/StaleCleanup.java`（`EPHEMERAL_PATTERNS` @ 23-29；`isEphemeral` @ 33-35；`cleanup` @ 41-88；`startCleanupLoop` @ 93-111；`runGitQuiet` @ 113-134）
- 依赖任务: T6
- 完成标准: 五个临时命名正则常量列表：`^agent-a[0-9a-f]{7}$` / `^wf_[0-9a-f]{8}-[0-9a-f]{3}-\d+$` / `^wf-\d+$` / `^bridge-[A-Za-z0-9_]+(-[A-Za-z0-9_]+)*$` / `^job-[a-zA-Z0-9._-]{1,55}-[0-9a-f]{8}$`；`isEphemeral(slug)` 任一匹配返 true。`cleanup(repoRoot, cutoff)` — `dir = <repoRoot>/.mewcode/worktrees`，不存在返 0 → 取 `WorktreeSessionStore.getCurrentSession()?.worktreePath()` 作为白名单 → `Files.list(dir)` 遍历每项 `slug = entry.getFileName()`：
  - **L1 命名**：`!isEphemeral(slug)` → continue（用户命名永不删）
  - **L2 时态**：`wtPath.equals(currentPath)` → continue；`Files.readAttributes(entry, BasicFileAttributes.class).lastModifiedTime().toInstant().isAfter(cutoff)` → continue；读 attrs 异常也 continue
  - **L3 git 状态 fail-closed**：`runGitQuiet(wtPath, "--no-optional-locks", "status", "--porcelain", "-uno")` 返 null 或 非空 → continue；`runGitQuiet(wtPath, "rev-list", "--max-count=1", "HEAD", "--not", "--remotes")` 返 null 或非空 → continue
  - 三层通过 → `AgentWorktree.remove(wtPath, SlugValidator.branchName(slug), repoRoot)` 成功 `removed++`；
  末尾 `removed > 0` 时跑 `runGitQuiet(repoRoot, "worktree", "prune")`；返 `removed`。`startCleanupLoop(executor, repoRoot, intervalSeconds, cutoffHours)`：`intervalSeconds <= 0` 直接 return；否则 `executor.scheduleAtFixedRate(task, interval, interval, TimeUnit.SECONDS)`，task 算 `cutoff = Instant.now().minusSeconds(cutoffHours*3600L)` 后调 `cleanup`。
- [ ] 完成

## T12: 接入应用启动装配
- 影响文件: 应用入口（如 `src/main/java/com/mewcode/Main.java` 或 TUI 启动器，按项目实际路径）
- 依赖任务: T7, T8, T9, T10, T11
- 完成标准:
  1. 构造 `WorktreeManager(projectRoot, symlinkDirs, staleCutoffHours)`，`projectRoot` 由 `System.getProperty("user.dir")` 或仓库根解析得到；
  2. 注册 `new EnterWorktreeTool(worktreeManager, sessionId)` 和 `new ExitWorktreeTool(worktreeManager)` 到 `ToolRegistry`；
  3. `AgentTool.setWorktreeManager(worktreeManager)` 把 `worktreeManager` 注入到 `AgentTool` 实例；
  4. `WorktreeSession saved = WorktreeSessionStore.load(projectRoot)` → 非 null 且 `Files.exists(Path.of(saved.worktreePath()))` 时 `WorktreeSessionStore.restoreSession(saved)`；
  5. `ScheduledExecutorService cleanupExec = Executors.newSingleThreadScheduledExecutor()` → `StaleCleanup.startCleanupLoop(cleanupExec, projectRoot, intervalSeconds, cutoffHours)`，间隔由配置控制（默认 0 = 不启动）；
  6. 应用退出时 `cleanupExec.shutdown()`。
- [ ] 完成

## T13: 端到端验证
- 影响文件: 无（仅运行）
- 依赖任务: T1-T12
- 完成标准:
  - `./gradlew build` 通过（无编译错误，所有单元测试 PASS）；
  - **路径 A — 工具直接驱动**：主 Agent 调 `EnterWorktree({name:"demo"})` 创建 worktree → 在 worktree 里 `WriteFile + Bash("git commit ...")` → `ExitWorktree({action:"remove"})` 被变更保护拒绝并列出具体 file/commit 数（带正确单复数）→ `ExitWorktree({action:"remove", discard_changes:true})` 强删成功，`.mewcode/worktrees/demo` 消失；
  - **路径 B — 子 Agent 自动隔离**：主 Agent 在主目录 `WriteFile witness.txt = "original content from main agent"` → 调 `Agent({subagent_type:"general-purpose", isolation:"worktree", description:"...", prompt:"把 witness.txt 改成 ..."})` → 验证主目录 `witness.txt` 内容不变；`.mewcode/worktrees/agent-a*/witness.txt` 是修改后版本；若有 commit → 结果末尾出现 `"Worktree kept at ... (branch worktree-agent-a...) — has uncommitted changes or new commits."`；若无修改 → worktree 自动被 `AgentWorktree.remove` 清理；
  - **持久化与重启**：`EnterWorktree({name:"crashtest"})` 后强杀进程 → `.mewcode/worktree_session.json` 仍存在 → 重启后 `WorktreeSessionStore.load + restoreSession` 把 session 写回全局 `volatile` 字段。
- [ ] 完成

## 进度
- [ ] T1 / [ ] T2 / [ ] T3 / [ ] T4 / [ ] T5 / [ ] T6 / [ ] T7 / [ ] T8 / [ ] T9 / [ ] T10 / [ ] T11 / [ ] T12 / [ ] T13