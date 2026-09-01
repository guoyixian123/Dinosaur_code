# ch15: AgentTeam Checklist

> 所有条目可勾选、可观测。验收方式写在条目后面括号中。验收：已通过验证的项均勾选。

## 1. 实现完整性

- [ ] 枚举 `TeamManager.TeamMode` 在 `src/main/java/com/mewcode/teams/TeamManager.java:21` 存在，含 `IN_PROCESS / TMUX` 两档
- [ ] 内部类 `TeamManager.Team` 在 `TeamManager.java:70-134` 存在，字段 `name / mode / members / mailBox` 齐全，写方法全部 `synchronized`
- [ ] 内部类 `TeamManager.Member` 在 `TeamManager.java:136-151` 存在，字段 `name / agent / conv / active / thread`（后两者 `volatile`）齐全
- [ ] 静态方法 `TeamManager.detectBackend` 在 `TeamManager.java:53-62` 实现优先级 `TMUX env → which tmux → IN_PROCESS`
- [ ] Record 类 `FileMailBox.MailMessage` 在 `FileMailBox.java:16-21` 含 6 字段 `from / text / timestamp / read / color / summary`，便利构造器自动填 timestamp/read=false
- [ ] `FileMailBox.withLock` 在 `FileMailBox.java:76-112` 使用 `Files.createFile` 抛 `FileAlreadyExistsException` 时重试，10 次 5-100ms 随机退避，>10s 过期清理
- [ ] Record 类 `SpawnDispatcher.SpawnConfig / SpawnResult` 在 `SpawnDispatcher.java:15-29` 存在
- [ ] 常量 `TeammateRunner.LEAD_NAME = "lead"` / `SHUTDOWN_PREFIX = "[shutdown]"` / `IDLE_POLL_MS = 500L` 在 `TeammateRunner.java:16-18`
- [ ] `Coordinator.ALLOWED_TOOLS` `Set<String>` 在 `Coordinator.java:19-32` 含 12 项白名单（写工具 `WriteFile / EditFile` 等被排除）
- [ ] `TeammateRunner.runInProcessTeammate` 在 `TeammateRunner.java:26-66` 主循环七步齐全：addendum 注入 → injectPendingMessages → addUserMessage → agent.run + drainAgentEvents → idle 通知 → while 循环 waitForNextPromptOrShutdown
- [ ] `SpawnDispatcher.buildTeammateCLI` 在 `SpawnDispatcher.java:67-73` 输出 `cd <quoted_wd> && <quoted_exe> --teammate --team-name <quoted> --agent-name <quoted>`；`shellQuote` 在 `:75-78` 简单字符直接返回、特殊字符单引号 POSIX 转义
- [ ] `TeammateRunner.buildTeammateAddendum` 在 `TeammateRunner.java:97-109` 文本包含 "member of team"、"Your name is"、"SendMessage tool"、"idle notification will be sent to the lead automatically" 四个关键信息
- [ ] `TeammateRunner.drainLeadMailbox` 在 `TeammateRunner.java:72-92` null 安全（`teamMgr == null` 返 `List.of()`）、读完后调 `markAllRead`、输出格式 `<team-notification team="X">\n...\n</team-notification>`
- [ ] `TeamTools.SendMessageTool.execute` 在 `TeamTools.java:54-71` 遍历所有团队找 `to` member 投递，未匹配返 `recipient '<to>' not found in any team` 错误
- [ ] `TeamTools.TeamCreateTool.execute` 在 `TeamTools.java:108-127` 同名冲突自动追加 `-2/-3/...` 后缀去重
- [ ] `TeamTools.TeamDeleteTool.execute` 在 `TeamTools.java:163-180` 返回 `"Team \"X\" deleted. Stopped N member(s): a, b, c"` 清单
- [ ] `AgentNameRegistry` 在 `AgentNameRegistry.java:10-36` 是单例（`getInstance`），全部方法 `synchronized`；`resolve` 支持反向 id 寻址
- [ ] `SharedTaskStore` 在 `SharedTaskStore.java:18-103` 实现 `create / get / listTasks / update`，全部 `synchronized`；`update` 用 wither 模式产新 record，`addBlocks/addBlockedBy` 追加而非替换

## 2. 接入完整性（必查，杜绝死代码）

- [ ] `rg "new TeamManager\\(\\)" src/main/java/com/mewcode/tui` 在 TUI 装配代码找到 `TeamManager` 实例化点
- [ ] `rg "TeamCreateTool|TeamDeleteTool|SendMessageTool" src/main/java/com/mewcode/tui` 在 TUI 找到三个工具注册点
- [ ] AgentTool 注入 `teamMgr` 的代码在 TUI 装配处可见（`agentTool.setTeamMgr(teamMgr)` 或构造器注入）
- [ ] `rg "drainLeadMailbox" src/main/java/com/mewcode/tui` 命中 Lead 每轮迭代调用点（把 `<team-notification>` 注入下一轮 system reminder）
- [ ] `rg "Coordinator.isCoordinatorTool" src/main/java/com/mewcode/tui` 命中 Lead 工具调用过滤点
- [ ] `MewCodeModel.teamMgr` 字段在 TUI 主模型类中声明
- [ ] `rg "teamMgr" src/main/java/com/mewcode/agents/AgentTool.java` 看到 `AgentTool` 的 `team_name` 分支调用 `SpawnDispatcher.spawnTeammate`
- [ ] `rg "SpawnDispatcher.spawnTeammate" src/main/java/com/mewcode/agents` 命中 in-process 模式下虚拟线程消费 eventOut 的 `drainTeammateEvents` 调用
- [ ] 程序退出 finally 块调 `teamMgr.closeAll()` 确保所有虚拟线程被中断

## 3. 编译与测试

- [ ] `./gradlew build` 通过
- [ ] `./gradlew test` 通过（覆盖至少 15 个用例：FileMailBoxTest 5 个 + TeamManagerCRUD / DetectBackendFallback / DetectBackendPrefersTmuxWhenInside / SendMessageToolRoutes / TeamCreateNameCollision / TeamDeleteStopsMembers / IsShutdownRequest / CreateIdleNotification / DrainLeadMailbox / DrainLeadMailboxNullSafe / ShellQuote / BuildTeammateCLIFormat / SpawnDispatcherInProcess / SpawnDispatcherTmuxValidation / CoordinatorAllowedTools / SharedTaskStoreCRUD / AgentNameRegistryRoundtrip）
- [ ] `./gradlew check` 无警告（含 SpotBugs / Checkstyle 若启用）
- [ ] 测试运行不在仓库根残留 `.mewcode/teams/` 目录（`@TempDir` 重定向到 tmp）
- [ ] FileMailBox 并发测试用 `ExecutorService` + `CountDownLatch` 验证文件锁正确性，多线程并发 `send` 后 `readUnread` 数量与发送次数一致

## 4. 端到端验证

- [ ] 注册路径：TUI 启动后装配代码创建 `TeamManager` 并把 `TeamCreate / TeamDelete / SendMessage` 三件套放入 registry；用户向 Lead 说 "create a team to refactor X" → LLM 调 `TeamCreate(team_name="refactor-X")` → `detectBackend()` 选模式 → Output 返回 `"Team \"refactor-X\" created (mode: ...). Use Agent tool with team_name=\"refactor-X\" to add teammates."`
- [ ] Spawn 路径：Lead 继续说 "spawn alice to do data layer" → LLM 调 `Agent(team_name="refactor-X", name="alice", prompt="...")` → `AgentTool.execute` 识别 `team_name` 分支调 `SpawnDispatcher.spawnTeammate(IN_PROCESS|TMUX)` → 队员开始干活
- [ ] 通信路径：队员 alice 通过 `SendMessage(to="bob", content="...")` 给 bob 写 mailbox → bob 下一轮 idle poll 拿到消息作为 user message 注入对话
- [ ] Lead 感知路径：每个队员 turn 结束写 `[idle] alice: completed initial task (at <iso>)` 通知到 Lead 邮箱 → Lead 下一轮迭代调 `drainLeadMailbox` 抽出 `<team-notification team="refactor-X">\nfrom=alice: [idle] ...\n</team-notification>` 注入 Lead 上下文
- [ ] Coordinator Mode 路径：团队存活期间 Lead 每轮工具调用前 `Coordinator.isCoordinatorTool` 过滤，调用 `WriteFile` / `EditFile` 会被拒绝；`TeamDelete` 清空所有团队后下一轮恢复全工具集
- [ ] Tmux 后端：`TMUX` env 非空时 `detectBackend` 返 `TMUX` → spawn 时先把 task 写 mailbox → `tmux new-window -d` 拉起新窗口跑 `mewcode --teammate ...` → 子进程加载同一 mailbox 目录 → 第一次 idle poll 拿到初始任务开始干活
- [ ] iTerm 后端（备用）：`ITermBackend` 类已实现 `spawnITermTeammate / stopITermTeammate`，可通过手工调用验证 AppleScript 解析正确（`SpawnDispatcher` 当前未接此分支，作为后续扩展点）
- [ ] 关闭路径：`TeamDelete(team_name="refactor-X")` → `teamMgr.deleteTeam` → `team.stopAll` 遍历 member 调 `thread.interrupt()`（in-process）或 `TmuxBackend.stopTmuxTeammate`（tmux）→ 全部清理后 Lead 下轮恢复全工具集
- [ ] JVM 退出路径：`teamMgr.closeAll()` 在 TUI 程序 finally 块调用，所有虚拟线程被中断、所有 tmux 窗口被关闭

## 5. 文档

- [ ] `docs/java/ch15/spec.md` 已写
- [ ] `docs/java/ch15/tasks.md` 已写，16 个 T 全部勾完
- [ ] `docs/java/ch15/checklist.md` 已写并逐项验收
- [ ] commit 信息标注 `ch15` 与三件套关闭状态（待用户确认后由人或 CI 触发）