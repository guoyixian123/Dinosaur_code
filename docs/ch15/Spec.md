# ch15: AgentTeam Spec

## 1. 背景

SubAgent（ch13）解决了一次性子任务的上下文隔离，但拓扑是星型：所有子 Agent 只能和主 Agent 通信，子 Agent 之间彼此看不见。当任务规模上来——四个模块同时重构、多角度并行调查 bug、一个 Agent 需要把发现告诉另一个——星型拓扑下主 Agent 成了信息中转瓶颈，子任务被迫串行。这一章把"长期协作团队"做成 MewCode 的一等概念：多个 Agent 组成 Team，并行干活、直接互发消息、共享任务列表，主 Agent 升级为 Team Lead 专职调度。Java 版本利用 JDK 21 虚拟线程跑 in-process 队员，外部后端则通过 `ProcessBuilder` 拉起 tmux / iTerm2 进程，由共享 `FileMailBox` 目录串联跨进程通信。

## 2. 目标

提供 `TeamManager` / `TeamManager.Team` / `TeamManager.Member` / `FileMailBox` / `SharedTaskStore` / `AgentNameRegistry` / `Coordinator` / `TeamTools.SendMessageTool` / `TeamTools.TeamCreateTool` / `TeamTools.TeamDeleteTool` 一整套类型与工具，让 LLM 在对话里：1) 调 `TeamCreate` 建团队（按环境自动选 tmux / in-process 后端），2) 后续通过 `Agent` 工具带 `team_name` 把队员加入团队，3) 队员之间通过 `SendMessage` 走 `FileMailBox` 互发消息、idle 后通知 Lead，4) Lead 借助 `Coordinator.ALLOWED_TOOLS` 收窄工具集进入纯调度模式。tmux 后端由 `SpawnDispatcher.buildTeammateCLI` 拼出 `mewcode --teammate --team-name X --agent-name Y` 由独立进程跑 worker，和 Lead 共享同一份 mailbox 目录。

## 3. 功能需求

- F1: `TeamManager.TeamMode` 枚举包含 `IN_PROCESS / TMUX` 两档；`TeamManager.detectBackend()` 按 `TMUX` 环境变量 → `which tmux` 命中 → 退化到 `IN_PROCESS` 的优先级自动选择。
- F2: `TeamManager.Team` 持有 `name / mode / members LinkedHashMap / mailBox` 字段；`TeamManager.Member` 含 `name / agent / conv / active / thread` 字段，外部后端的 Member 由 `SpawnDispatcher.recordExternalMember` 创建，`agent` 与 `conv` 字段保持为 null。
- F3: `TeamManager` 提供 `createTeam` / `getTeam` / `deleteTeam` / `listTeams` / `closeAll` 同步方法；`Team` 暴露 `addMember` / `startMember` / `stopMember` / `stopAll` / `getMember` / `hasMember` / `memberNames` / `sendMessage`，全部用 `synchronized` 保护成员表。
- F4: `FileMailBox` 基于 `<baseDir>/<agentId>.json` 文件持久化消息；`send` / `readUnread` / `markAllRead` 三件套；并发安全靠 `<agentId>.json.lock` 文件锁，`Files.createFile` 抛 `FileAlreadyExistsException` 时重试（最多 10 次，5-100ms 随机退避），>10s 视为过期锁强制清理。
- F5: `FileMailBox.MailMessage` 记录类含 `from / text / timestamp / read / color / summary` 六个字段；便利构造器 `MailMessage(from, text)` 自动填 `Instant.now()` 时间戳、`read=false`、空 color/summary；`send` 落盘时强制把 `read` 置 false。
- F6: `SpawnDispatcher.spawnTeammate(SpawnConfig)` 统一入口按 `Team.mode` 分发到 in-process / tmux 两条路径，返回 `SpawnResult{mode, paneId}`。`IN_PROCESS` 模式调 `team.addMember` 注册并用 `Thread.startVirtualThread` 跑 `TeammateRunner.runInProcessTeammate`；`TMUX` 模式先把 task 写入对方 mailbox，再拼 CLI 调 `TmuxBackend.spawnTmuxTeammate`，最后 `recordExternalMember` 注册。
- F7: `SpawnDispatcher.buildTeammateCLI(teamName, memberName, workdir)` 用 `ProcessHandle.current().info().command()` 拿当前可执行路径；workdir 空时退化到 `System.getProperty("user.dir")`；输出 `cd <quoted_wd> && <quoted_exe> --teammate --team-name <quoted_team> --agent-name <quoted_member>`，所有变量经 `shellQuote` 处理。
- F8: `SpawnDispatcher.shellQuote(s)` 简单字符（`[a-zA-Z0-9_./-]+`）直接返回；含特殊字符时单引号包裹并把内嵌的 `'` 替换为 `'\''`（POSIX 标准转义）。
- F9: `TmuxBackend.spawnTmuxTeammate` 用 `tmux new-window -d -n <teamName>-<memberName> <cliCommand>` 创建后台窗口；命令返回码非 0 或超时（30s）抛 `RuntimeException("Failed to spawn tmux window: ...")`；`TmuxBackend.stopTmuxTeammate` 先 `send-keys C-c` 再 `kill-window`，best-effort 不重抛异常，失败仅 `log.fine`。
- F10: `ITermBackend.spawnITermTeammate` 用 `osascript -e <AppleScript>` 在 iTerm2 当前 window 创建 tab 并 `write text <cliCommand>`，内嵌双引号转义为 `\"`；30s 超时；`stopITermTeammate` 遍历所有 window 和 tab 找名字匹配的 close 掉，10s 超时、best-effort 失败静默。
- F11: `TeammateRunner.runInProcessTeammate(team, member, initialPrompt, addendum)` 队员主循环：先把 addendum 作为 system reminder 注入 → 调 `injectPendingMessages` 把未读邮件转 system reminder → 把 `initialPrompt` 加为 user message → 调 `member.agent.run(conv)` 跑一轮 → 通过 `drainAgentEvents` 转发事件 → 给 Lead 发 `[idle]` 通知 → 循环 `waitForNextPromptOrShutdown` 轮询邮箱，500ms 间隔，命中新消息加为 user message 跑下一轮，命中 shutdown 或线程中断退出。退出前置 `member.active=false`。
- F12: `TeammateRunner.LEAD_NAME = "lead"` / `SHUTDOWN_PREFIX = "[shutdown]"` / `IDLE_POLL_MS = 500` 三常量；`isShutdownRequest(text)` 用 `text.strip().startsWith(SHUTDOWN_PREFIX)` 判定；`createIdleNotification(memberName, reason)` 产出 `"[idle] <name>: <reason> (at <iso-instant>)"` 文本。
- F13: `TeammateRunner.drainLeadMailbox(teamMgr)` 扫所有团队的 Lead 收件箱，把未读消息按 `<team-notification team="X">\nfrom=Y: text\n...\n</team-notification>` 包装返回 `List<String>`，并把消息标记为已读；`teamMgr == null` 时返回 `List.of()`。
- F14: `TeammateRunner.buildTeammateAddendum(teamName, memberName, otherMembers)` 产出注入到队员对话顶端的 system reminder，告诉它身份、其他队友名字、必须通过 `SendMessage` 沟通、停止调用工具会自动发 idle 通知给 Lead。
- F15: `TeammateRunner.injectPendingMessages(team, memberName, conv)` 读 mailbox 未读，非空时拼 `"You have new messages:\n\nFrom <sender>: \n\n..."` 作为 system reminder 注入并 `markAllRead`，无未读直接返回。
- F16: `Coordinator.ALLOWED_TOOLS` 是 12 项白名单 `Set<String>`：`Agent / SendMessage / TaskCreate / TaskGet / TaskList / TaskUpdate / TeamCreate / TeamDelete / ReadFile / Glob / Grep / Bash`；`Coordinator.isCoordinatorTool(name)` 返回 set 命中布尔。写工具 `WriteFile / EditFile` 等被排除。
- F17: `TeamTools.SendMessageTool` 暴露 `to / content` 两个必填字段；`execute` 遍历所有团队找 `to` 这个 member 所在团队调 `team.sendMessage(senderName, to, content)` 投递；未匹配返 `recipient '<to>' not found in any team` 错误。
- F18: `TeamTools.TeamCreateTool` 暴露 `team_name` 必填、`description` 可选；同名时追加 `-2/-3/...` 后缀去重；调 `TeamManager.detectBackend()` + `teamMgr.createTeam`；Output 提示 `"Team \"X\" created (mode: Y). Use Agent tool with team_name=\"X\" to add teammates."`。
- F19: `TeamTools.TeamDeleteTool` 暴露 `team_name` 必填；不存在返错误；调 `teamMgr.deleteTeam`（内部 `stopAll` 中断所有 member 的虚拟线程）；返回 `"Team \"X\" deleted. Stopped N member(s): a, b, c"` 清单。
- F20: `AgentNameRegistry` 是单例（`getInstance()`），维护 `name → agentId` 映射；`register / resolve / unregister / listAll` 全部 `synchronized`；`resolve` 支持反向匹配——传入的字符串既可以是 name 也可以是 agentId，两边都查不到返 null。
- F21: `SharedTaskStore` 基于 `<teamDir>/tasks.json` 持久化 `SharedTask` 记录列表；`create / get / listTasks / update` 全部 `synchronized`；`update` 支持 `status / assignee` 覆盖以及 `addBlocks / addBlockedBy` 追加（不替换），自增 `id` 由 `AtomicInteger` 保证。

## 4. 非功能需求

- N1: FileMailBox 跨进程并发安全——tmux 启动的队友进程和 Lead 进程不共享 JVM 堆，必须靠文件锁保证写入原子性。锁文件 10 秒过期自动清理避免死锁。
- N2: 外部后端队员的初始任务必须在 spawn 之前写入 mailbox，因为 tmux 新进程启动到第一次 idle poll 期间无法接消息；先写后启即可保证第一次 poll 必命中。
- N3: In-process 队员的虚拟线程退出路径有三条：`Thread.currentThread().isInterrupted()` 为真、收到 shutdown 消息、`agent.run` 自然结束后无新消息。退出时必须置 `member.active=false`，否则 Lead 拿不到队员已停的状态。
- N4: Coordinator Mode 通过 `Coordinator.isCoordinatorTool` 在每轮迭代开头动态判定，而非一次性裁剪 registry。这样团队全部 Delete 后下一轮 Lead 自动恢复全工具集，无需重建 registry。
- N5: 队员的 `buildTeammateAddendum` 必须明确告诉 LLM "纯文本回复对队友不可见，最终结果必须通过 `SendMessage` 发给 Lead"——否则队员模型容易写一段汇报作为最后输出就结束，Lead 永远拿不到结果（只能看到 idle 通知）。
- N6: `SendMessage` 当前实现走"遍历所有团队找 `to` member"路径；若 Lead 不在任何 team.members 中，给 Lead 发消息会失败。Java 版的简化方案是发送时直接走当前 Sender 所在团队的 mailbox.send（绕过 hasMember 检查）。
- N7: `SpawnDispatcher.buildTeammateCLI` 必须把 `workdir / mewcode / teamName / memberName` 都通过 `shellQuote` 包裹，否则空格或特殊字符的 workdir 路径会破坏 shell 解析；`shellQuote` 单引号转义遵循 POSIX `'\''` 标准。
- N8: `ITermBackend` 里的 AppleScript 字面量必须把内嵌的双引号转义为 `\"`，否则 `osascript -e` 解析失败；关闭流程是 best-effort，找不到 tab 不应报错（用户可能手动关掉了）。
- N9: `TeammateRunner.runInProcessTeammate` 应当使用 JDK 21 虚拟线程（`Thread.startVirtualThread`）而非平台线程，避免大团队时线程开销爆炸；mailbox 轮询采用 `Thread.sleep(IDLE_POLL_MS)` 而非自旋。
- N10: 测试运行时 `@TempDir` 必须用 `org.junit.jupiter.api.io.TempDir`，让 FileMailBox 写到测试临时目录，否则跑完测试会在仓库根残留 `.mewcode/teams/` 目录；并发测试需用 `ExecutorService` + `CountDownLatch` 验证文件锁正确性。

## 5. 设计概要

- 核心类型:
 - `TeamManager`：全局团队注册表（`Map<String, Team>` + `synchronized` 方法），暴露 CRUD + `detectBackend` 静态方法。
 - `TeamManager.Team`：团队聚合，持有 `mode` 决定后端、`members LinkedHashMap` 注册表、`mailBox FileMailBox` 通信媒介，所有写方法 `synchronized`。
 - `TeamManager.Member`：队员元信息，in-process 模式 `agent + conv` 有值（LLM 跑在虚拟线程），tmux 模式两者为空、`thread` 也为空、靠 paneId（存储为 `name` 字段一部分）句柄。
 - `FileMailBox` + `FileMailBox.MailMessage`：文件锁 + JSON 数组的 mailbox 实现，跨进程共享同一目录，依赖 Jackson `ObjectMapper`。
 - `SpawnDispatcher.SpawnConfig` / `SpawnResult`：`spawnTeammate` 的入参/出参 record，把 in-process 与 tmux 后端的差异收敛到统一返回类型。
 - `Coordinator.ALLOWED_TOOLS`：12 项白名单 `Set<String>`，TUI 每轮按 `teamMgr.listTeams().isEmpty()` 决定是否启用过滤。
 - `SharedTaskStore`：JSON 持久化的任务表，提供 `id / title / description / status / assignee / blocks / blockedBy` 字段及 `addBlocks / addBlockedBy` 追加语义。
 - `AgentNameRegistry`：全局单例 `name → agentId` 映射，方便 SendMessage 通过名字寻址。
- 主流程（按生命周期）:
 - 创建：用户消息 → 主 Agent → LLM 调 `TeamCreate(team_name)` → `TeamManager.detectBackend()` 选模式 → `teamMgr.createTeam` 落到 `~/.mewcode/teams/<name>/inboxes/` → 返回 mode 提示给 Lead。
 - Spawn 队员：Lead LLM 调 `Agent(team_name=X, name=Y, prompt=Z)` → AgentTool 识别 `team_name` 走 team 分支 → `SpawnDispatcher.spawnTeammate(SpawnConfig)` → 按 mode 分发。
 - In-process：`team.addMember` 注册成员 → `Thread.startVirtualThread` 跑 `TeammateRunner.runInProcessTeammate` → 队员在自己的虚拟线程里跑 agent loop。
 - 外部后端：先把初始任务写 mailbox → `buildTeammateCLI` 拼命令 → `TmuxBackend.spawnTmuxTeammate` 调 `tmux new-window` → 新进程跑 `mewcode --teammate` worker 模式 → 第一次 idle poll 命中初始消息开始干活。
 - 通信：队员 → `SendMessage` 工具 → 找对方所在团队 → `team.sendMessage` → `mailBox.send` 写文件。队员收信走 `runInProcessTeammate` 顶端的 `injectPendingMessages` 或 `waitForNextPromptOrShutdown`。
 - Lead 感知：每轮 Lead Agent 开头调 `TeammateRunner.drainLeadMailbox` → 抽 Lead 邮箱所有未读 → 包成 `<team-notification>` system reminder 喂回 LLM。
 - Coordinator Mode：只要 `teamMgr.listTeams()` 非空，TUI 把 Lead 的工具调用拦截 → `Coordinator.isCoordinatorTool(name)` 判定 → 非白名单工具被过滤 → 全部团队清理后下一轮恢复全工具集。
 - Stop：`TeamDelete` 工具 → `teamMgr.deleteTeam` → `team.stopAll` 遍历 member 调 `thread.interrupt()`（in-process）或后端关闭脚本（tmux/iTerm）。
- 调用链（模块层级）:
 - TUI 装配 → 创建 `TeamManager` → 注册 `TeamCreateTool / TeamDeleteTool / SendMessageTool` 三个工具
 - Agent loop 每轮调 `TeammateRunner.drainLeadMailbox` 拼到下一轮系统提示
 - Lead 工具集过滤通过 `Coordinator.isCoordinatorTool` 在每次工具调用前判定
 - 外部工作进程入口 `MewCode.main` 增加 `--teammate` flag 早期拦截，命中走 worker bootstrap 不进 TUI（当前 `MewCode.java` 尚未实现此路径，是后续扩展点）
- 与其他模块的交互:
 - 依赖 `com.mewcode.agent`（Agent / AgentEvent）、`com.mewcode.conversation`（ConversationManager）、`com.mewcode.llm`（LlmClient）、`com.mewcode.tool`（Tool / ToolRegistry / ToolCategory / ToolResult）
 - 被 AgentTool（解析 `team_name` 参数）、TUI（注册工具 + 收件箱 drain + Coordinator filter）、`MewCode.main`（未来 worker 入口）调用

## 6. Out of Scope

- 不实现完整的 `TeammateInfo` 模型（`agentType / model / planModeRequired` 字段、planModeRequired 审批工作流）——本章仅做工具链层面的 Team / Member 骨架。
- 不实现 `plan_approval_response` / `shutdown_response` 结构化消息类型——目前仅 `[shutdown]` 文本前缀 + 纯文本消息两种。
- 不实现 `MewCode --teammate` worker 进程入口完整实现——`SpawnDispatcher.buildTeammateCLI` 已经能产出命令，但 `MewCode.java` 的 main 还没接 `parseTeammateFlags`，留作后续章节扩展。
- 不实现 `TeamManager.createTeamWith` 让外部 worker 进程注册本地构造的 Team——当前 worker 入口未实现，所以此扩展点不必要。
- 不实现 iTerm2 后端在 `SpawnDispatcher` 内的分支——`ITermBackend` 类已经存在但 `spawnTeammate` 的 switch 没接 `ITERM` 分支；本章先保证 tmux + in-process 两档可用。
- 不实现共享任务依赖图的 BFS 校验/循环依赖检测——`SharedTaskStore.update` 只做字段追加，不验证 `blocks/blockedBy` 是否构成环。
- 不实现"协调模式四阶段工作流"系统提示词注入（Research / Synthesis / Implementation / Verification）——`Coordinator` 仅做工具收窄，不做提示词增强。
- 不实现"配置持久化到 ~/.mewcode/teams/<name>/config.json" 的团队元数据——只持久化邮箱 JSON 和 tasks.json，Team 实例本身随 JVM 退出消失。
- 不实现 Worktree 团队层面的"收敛阶段 Lead 用 Bash 跑 git merge"自动化——合并由 Lead LLM 自己用 Bash 工具完成，本章不做封装。

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。