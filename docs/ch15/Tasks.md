# ch15: AgentTeam Tasks

> 任务粒度：每个任务可在一次会话内完成，可独立交付。

## T1: 定义 TeamManager / Team / Member / TeamMode
- 影响文件: `src/main/java/com/mewcode/teams/TeamManager.java`（`TeamMode` 枚举 @ 21；`teams` map @ 23；`createTeam` @ 25-29；`getTeam` @ 31-33；`deleteTeam` @ 35-40；`listTeams` @ 42-44；`closeAll` @ 46-51；`detectBackend` @ 53-62；`teamsBaseDir` @ 66-68；`Team` 内部类 @ 70-134；`Member` 内部类 @ 136-151）
- 依赖任务: 无
- 完成标准: `TeamMode` 枚举含 `IN_PROCESS / TMUX`；`Team` 字段 `name / mode / members / mailBox` 齐全；`Team` 方法 `addMember / startMember / stopMember / stopAll / getMember / hasMember / memberNames / sendMessage` 全部 `synchronized`；`Member` 字段 `name / agent / conv / active / thread` 齐全（`active / thread` volatile）；`TeamManager` 顶层 CRUD 方法全部 `synchronized`；`detectBackend` 优先级 `TMUX env → which tmux → IN_PROCESS`。
- [ ] 完成

## T2: 实现 FileMailBox（JSON + 文件锁）
- 影响文件: `src/main/java/com/mewcode/teams/FileMailBox.java`（`MailMessage` record @ 16-21；常量 `MAPPER / MAX_RETRIES / MIN_SLEEP_MS / MAX_SLEEP_MS` @ 23-26；构造器 @ 30-35；`inboxPath` @ 37-39；`lockPath` @ 41-43；`send` @ 45-51；`readUnread` @ 53-60；`markAllRead` @ 62-70；`withLock` @ 76-112；`readInbox` @ 114-123；`writeInbox` @ 125-131）
- 依赖任务: 无
- 完成标准: 每个收件人对应 `<baseDir>/<agentId>.json`；`MailMessage` record 含 6 字段且便利构造器自动填 timestamp/read=false；`send` 落盘时把 `read` 强制置 false；`markAllRead` 用 `withLock` 批量翻转所有消息为 read=true；并发安全靠 `<agentId>.json.lock` 文件用 `Files.createFile` 抛 `FileAlreadyExistsException` 时重试，最多 10 次 5-100ms 随机退避，>10s 视为过期锁清理；`withLock` 在 fn 返回后 finally 删锁文件；Jackson 用 `ObjectMapper` 默认配置 + `TypeReference<List<MailMessage>>`。
- [ ] 完成

## T3: 实现 Tmux 后端
- 影响文件: `src/main/java/com/mewcode/teams/TmuxBackend.java`（`spawnTmuxTeammate` @ 16-27；`stopTmuxTeammate` @ 29-41）
- 依赖任务: T1
- 完成标准: `spawnTmuxTeammate` 用 `ProcessBuilder("tmux", "new-window", "-d", "-n", paneName, cliCommand)` 创建后台窗口；30s 超时，非 0 退出码或超时抛 `RuntimeException`；`stopTmuxTeammate` 先 `send-keys C-c` 等 5s + `Thread.sleep(200)` 再 `kill-window`，best-effort 失败仅 `log.fine` 不重抛。
- [ ] 完成

## T4: 实现 iTerm2 后端
- 影响文件: `src/main/java/com/mewcode/teams/ITermBackend.java`（`spawnITermTeammate` @ 16-40；`stopITermTeammate` @ 42-61）
- 依赖任务: T1
- 完成标准: `spawnITermTeammate` 用 `osascript -e <AppleScript>` 在当前 window 创建 tab 设 name 并 `write text <cliCommand>`，内嵌双引号转义为 `\"`；30s 超时；`stopITermTeammate` AppleScript 遍历所有 window 的 tab 找 name 匹配的 close 掉，10s 超时、best-effort 失败仅 `log.fine`。
- [ ] 完成

## T5: 实现队员主循环 TeammateRunner.runInProcessTeammate
- 影响文件: `src/main/java/com/mewcode/teams/TeammateRunner.java`（常量 `LEAD_NAME / SHUTDOWN_PREFIX / IDLE_POLL_MS` @ 16-18；`runInProcessTeammate` @ 26-66；`waitForNextPromptOrShutdown` @ 142-170；`drainAgentEvents` @ 172-187）
- 依赖任务: T1, T2
- 完成标准: 主循环 7 步——1) addendum 非空时加为 system reminder；2) `injectPendingMessages` 把未读邮件转 system reminder；3) `addUserMessage(initialPrompt)`；4) `member.agent.run(conv)` 拿 event queue；5) `drainAgentEvents` 转发到 eventOut；6) `sendMessage(self, LEAD, "[idle]...")` 发 idle 通知；7) 进入 while 循环 `waitForNextPromptOrShutdown` 轮询，shutdown 或线程中断退出，命中新消息加为 user message 继续下一轮。退出前置 `member.active=false`。`drainAgentEvents` 收到 `LoopComplete` 或 `ErrorEvent` 即返回。
- [ ] 完成

## T6: 实现 Lead-side 通信原语
- 影响文件: `src/main/java/com/mewcode/teams/TeammateRunner.java`（`drainLeadMailbox` @ 72-92；`buildTeammateAddendum` @ 97-109；`injectPendingMessages` @ 114-127；`isShutdownRequest` @ 129-131；`createIdleNotification` @ 133-136）
- 依赖任务: T1, T2
- 完成标准: `drainLeadMailbox(null)` 返 `List.of()`；非空时遍历所有团队读 Lead 邮箱，按 `<team-notification team="X">\nfrom=Y: text\n...\n</team-notification>` 包装返字符串数组，并把读过的标记为已读。`buildTeammateAddendum` 文本必须含队员名、其他队友名、"通过 SendMessage 沟通"、"停止调用工具自动发 idle"四条信息。`injectPendingMessages` 在有未读时拼 `"You have new messages:\n\n..."` system reminder 并 `markAllRead`，无未读直接返回。`isShutdownRequest` 用 `text.strip().startsWith(SHUTDOWN_PREFIX)` 判定。`createIdleNotification` 产出 `"[idle] <name>: <reason> (at <iso-instant>)"`。
- [ ] 完成

## T7: 实现 SpawnDispatcher 统一入口
- 影响文件: `src/main/java/com/mewcode/teams/SpawnDispatcher.java`（`SpawnConfig` record @ 15-24；`SpawnResult` record @ 26-29；`spawnTeammate` @ 33-61；`recordExternalMember` @ 80-88）
- 依赖任务: T1, T3, T5
- 完成标准: `spawnTeammate` switch `team.getMode()` 分发；`IN_PROCESS` 路径调 `team.addMember` 注册（可选 `setWorkDir(workdir)`） → 置 `active=true` → `Thread.startVirtualThread` 跑 `runInProcessTeammate` → 返 `SpawnResult(IN_PROCESS, null)`；`TMUX` 路径先把 task 写入对方 mailbox（用 `team.sendMessage(LEAD_NAME, memberName, task)`） → `buildTeammateCLI` 拼命令 → `TmuxBackend.spawnTmuxTeammate` 拿 paneId → `recordExternalMember` 注册占位 member → 返 `SpawnResult(TMUX, paneId)`；未知 mode 抛 `IllegalStateException`。
- [ ] 完成

## T8: 实现 BuildTeammateCLI + shellQuote
- 影响文件: `src/main/java/com/mewcode/teams/SpawnDispatcher.java`（`buildTeammateCLI` @ 67-73；`shellQuote` @ 75-78）
- 依赖任务: T7
- 完成标准: `buildTeammateCLI` 用 `ProcessHandle.current().info().command().orElse("mewcode")` 拿当前可执行；workdir 空时默认 `System.getProperty("user.dir")`；返回 `cd <quoted_wd> && <quoted_exe> --teammate --team-name <quoted_team> --agent-name <quoted_member>`。`shellQuote` 简单字符（`[a-zA-Z0-9_./-]+` 正则命中）直接返回原串，含特殊字符时单引号包裹并把内嵌 `'` 替换为 `'\''`。
- [ ] 完成

## T9: 实现 Coordinator Mode 工具白名单
- 影响文件: `src/main/java/com/mewcode/teams/Coordinator.java`（`ALLOWED_TOOLS` @ 19-32；`isCoordinatorTool` @ 34-36）
- 依赖任务: 无
- 完成标准: 12 项白名单 `Set<String>`：`Agent / SendMessage / TaskCreate / TaskGet / TaskList / TaskUpdate / TeamCreate / TeamDelete / ReadFile / Glob / Grep / Bash`；`isCoordinatorTool(name)` 返回 set.contains 布尔（写工具 `WriteFile / EditFile` 等不在内）。
- [ ] 完成

## T10: 实现 SendMessage / TeamCreate / TeamDelete 三个工具
- 影响文件: `src/main/java/com/mewcode/teams/TeamTools.java`（`SendMessageTool` @ 20-72；`TeamCreateTool` @ 76-128；`TeamDeleteTool` @ 132-181）
- 依赖任务: T1
- 完成标准:
 - `SendMessageTool.execute`：`to/content` 必填；遍历所有团队找 `to` 这个 member 所在团队调 `team.sendMessage(senderName, to, content)` 投递；未匹配返 `recipient '<to>' not found in any team` 错误；schema 含 `to / content` 两个 string 必填字段。
 - `TeamCreateTool.execute`：`team_name` 必填；同名时追加 `-2/-3/...` 后缀去重；调 `TeamManager.detectBackend()` + `teamMgr.createTeam`；Output 含 `"Team \"X\" created (mode: Y). Use Agent tool with team_name=\"X\" to add teammates."`。
 - `TeamDeleteTool.execute`：`team_name` 必填；不存在返错误；调 `teamMgr.deleteTeam`（内部 `stopAll` 中断所有 member）；返回 `"Team \"X\" deleted. Stopped N member(s): a, b, c"` 清单。
- [ ] 完成

## T11: 实现 AgentNameRegistry 单例
- 影响文件: `src/main/java/com/mewcode/teams/AgentNameRegistry.java`（`INSTANCE` @ 12；`nameToId` map @ 13；`getInstance` @ 17；`register / resolve / unregister / listAll` @ 19-35）
- 依赖任务: 无
- 完成标准: 单例模式（`private static final INSTANCE`，私有构造）；`nameToId` 用 `LinkedHashMap` 保证遍历顺序；`register / resolve / unregister / listAll` 全部 `synchronized`；`resolve` 先查 name → id，未命中时检查 `containsValue(input)` 返回 input 本身（反向 id 寻址），都不命中返 null；`listAll` 返新建 `LinkedHashMap` 副本避免外部修改。
- [ ] 完成

## T12: 实现 SharedTaskStore
- 影响文件: `src/main/java/com/mewcode/teams/SharedTaskStore.java`（`SharedTask` record @ 21-32；常量 `MAPPER` + 字段 `filePath / nextId / tasks` @ 34-37；构造器 @ 39-42；`create` @ 44-50；`get` @ 52-54；`listTasks` @ 56-61；`update` @ 63-85；`load` @ 87-95；`save` @ 97-102）
- 依赖任务: 无
- 完成标准: `SharedTask` record 含 `id / title / description / status / assignee / blocks / blockedBy / createdBy` 字段，并提供 `withStatus / withAssignee` 不可变更新；`@JsonIgnoreProperties(ignoreUnknown=true)` 注解保证向前兼容；构造器 `new SharedTaskStore(teamDir)` 自动 load 已有 `tasks.json`；`create` 用 `AtomicInteger` 自增 id；`listTasks` 支持按 status/assignee 过滤；`update` 用记录类 wither 模式产新对象，`addBlocks/addBlockedBy` 是追加（用新建 ArrayList 拷贝旧值后 addAll）；全部 mutating 方法 `synchronized`；save 用 `MAPPER.writerWithDefaultPrettyPrinter()` 美化输出。
- [ ] 完成

## T13: 实现 FileMailBox 单元测试
- 影响文件: `src/test/java/com/mewcode/teams/FileMailBoxTest.java`（`sendCreatesFileWithMessage` @ 17-29；`readUnreadReturnsOnlyUnread` @ 31-41；`markAllReadMakesUnreadEmpty` @ 43-53；`nonexistentAgentReturnsEmpty` @ 55-60；`teamSendMessageIntegration` @ 62-74）
- 依赖任务: T1, T2
- 完成标准: 用 `@TempDir` 把 inbox 重定向到测试临时目录，避免污染仓库根；5 个用例覆盖——1) `send` 落盘后文件含 `from / text / read=false` 三字段；2) 连续 `send` 后 `readUnread` 返所有未读；3) `markAllRead` 后 `readUnread` 为空；4) 不存在的 agentId 返 `readUnread` 空列表；5) 集成测试创建 `Team` + 单独 mailbox 验证 send/read 完整流程。
- [ ] 完成

## T14: 实现 AgentTool team_name 分支
- 影响文件: `src/main/java/com/mewcode/agents/AgentTool.java`（新增 `teamMgr` 字段；`execute` 解析 `team_name` 参数；当 `team_name != null && teamMgr != null` 走 team 分支调 `SpawnDispatcher.spawnTeammate`；当 in-process 模式启虚拟线程消费 `eventOut` queue 转发到 `progressCh`）
- 依赖任务: T6, T7
- 完成标准: `AgentTool` 新增 `private TeamManager teamMgr` 字段及 setter；`execute` 在解析完 `subagent_type / prompt` 后检查 `team_name`，命中且 `teamMgr != null` 即走 team 分支；team 分支校验团队存在 + 同 team 同名 + 解析子工具池 + 可选 worktree + `TeammateRunner.buildTeammateAddendum` 构造 addendum + `SpawnDispatcher.spawnTeammate` 拿 result；in-process 模式启虚拟线程 `drainTeammateEvents` 消费事件流转 `SubAgentProgress` 喷进 `progressCh`；Output 含 backend hint 和 SendMessage 使用提示。
- [ ] 完成

## T15: TUI 接入
- 影响文件: `src/main/java/com/mewcode/tui/MewCodeModel.java`（`teamMgr` 字段；`registerAgentTools` 内创建 `TeamManager` 并注册三件套工具 + 注入 `AgentTool.teamMgr`；Lead 每轮迭代调 `TeammateRunner.drainLeadMailbox(teamMgr)` 拼到下一轮 system reminder；Lead Agent 工具调用前用 `Coordinator.isCoordinatorTool` 过滤）
- 依赖任务: T6, T9, T10, T14
- 完成标准:
 1. `MewCodeModel.teamMgr` 字段声明；
 2. `registerAgentTools`（或等价初始化方法）创建 `TeamManager` → 注册 `TeamCreateTool / TeamDeleteTool / SendMessageTool` → `AgentTool.setTeamMgr(teamMgr)`；
 3. Lead 每轮迭代开头调 `TeammateRunner.drainLeadMailbox(teamMgr)` 把 `<team-notification>` 字符串拼到要喂给模型的 system reminder；
 4. Lead 工具调用过滤：`teamMgr.listTeams().isEmpty()` 为空时放行全部，非空时 `Coordinator.isCoordinatorTool(name)` 判定；
 5. 程序退出 finally 块调 `teamMgr.closeAll()` 确保所有虚拟线程被中断。
- [ ] 完成

## T16: 端到端验证
- 影响文件: 无（仅运行验证）
- 依赖任务: T1-T15
- 完成标准:
 - `./gradlew build` 通过；
 - `./gradlew test` 通过（覆盖至少 `FileMailBoxTest` 5 个用例 + `TeamManagerTest` / `SpawnDispatcherTest` / `TeammateRunnerTest` / `CoordinatorTest` 共 15+ 用例，含 detectBackend 两档优先级、SendMessage 路由、SpawnDispatcher 校验、shellQuote、drainLeadMailbox、isShutdownRequest、createIdleNotification 等）；
 - 主流程接线验证：`rg "teamMgr|TeammateRunner|Coordinator\." src/main/java/com/mewcode/tui` 命中 TUI 装配点；`rg "TeamMgr|teamMgr" src/main/java/com/mewcode/agents/AgentTool.java` 看到 team 分支被 execute 调用。
- [ ] 完成

## 进度
- [ ] T1 / [ ] T2 / [ ] T3 / [ ] T4 / [ ] T5 / [ ] T6 / [ ] T7 / [ ] T8 / [ ] T9 / [ ] T10 / [ ] T11 / [ ] T12 / [ ] T13 / [ ] T14 / [ ] T15 / [ ] T16