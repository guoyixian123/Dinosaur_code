# ch13: SubAgent Tasks（Java 版）

> 任务粒度：每个任务可在一次会话内完成，可独立交付。

## T1: 定义 `SubAgentSpec` record + 三档 builtin
- 影响文件: `src/main/java/com/mewcode/subagent/SubAgentSpec.java`（record 头 @ 10-18；`PLAN_AGENT_SYSTEM_PROMPT` @ 20-54；`GENERAL_PURPOSE` @ 56-64；`PLAN` @ 66-75；`EXPLORE` @ 77-85）
- 依赖任务: 无
- 完成标准:
 - record 字段七项（`name / description / tools / disallowedTools / systemPromptOverride / maxTurns / model`）齐全；
 - `PLAN.disallowedTools()` 含 `EditFile / WriteFile`，`maxTurns == 15`，使用 `PLAN_AGENT_SYSTEM_PROMPT` 作为 prompt override；
 - `EXPLORE.disallowedTools()` 含 `EditFile / WriteFile`，`maxTurns == 30`，`model == "haiku"`；
 - `GENERAL_PURPOSE.maxTurns == 200`，无 prompt override。
- [ ] 完成

## T2: 实现 `AgentLoader.parseAgentFile`（Markdown frontmatter 解析）
- 影响文件: `src/main/java/com/mewcode/subagent/AgentLoader.java`（`VALID_MODELS` @ 27；`parseAgentFile` @ 95-150；`getString` @ 152-155；`getStringList` @ 157-170）
- 依赖任务: T1
- 完成标准:
 - 用 SnakeYAML 解析两个 `---` 之间的 frontmatter；
 - 缺 `name` / `description` 抛 `IllegalArgumentException`（含路径与字段名）；
 - `model` 非空时校验 ∈ `{"", "inherit", "haiku", "sonnet", "opus"}`，非法值抛错；
 - body 为空时 `systemPromptOverride == null`；
 - `tools` / `disallowedTools` 缺省返回 `List.of()`。
- [ ] 完成

## T3: 实现 `AgentLoader.loadAll`（builtin → user → project 三层优先级）
- 影响文件: `src/main/java/com/mewcode/subagent/AgentLoader.java`（`agents` 字段 @ 29；`loadAll` @ 39-53；`listNames` @ 58-62；`loadBuiltins` @ 64-68；`loadDir` @ 70-89）
- 依赖任务: T2
- 完成标准:
 - 先 `loadBuiltins` 注入三档 builtin；
 - 再 `~/.mewcode/agents/*.md`（user）；
 - 最后 `<projectRoot>/.mewcode/agents/*.md`（project）；
 - 同名后注册覆盖前者（`LinkedHashMap` 保 put 覆盖语义）；
 - 目录不存在静默跳过；解析失败的文件静默跳过（catch 后不抛）。
- [ ] 完成

## T4: 实现 `ToolFilter` 六层过滤
- 影响文件: `src/main/java/com/mewcode/subagent/ToolFilter.java`（`ALWAYS_DISALLOWED` @ 30-33；`CUSTOM_AGENT_DISALLOWED` @ 36-39；`ASYNC_ALLOWED` @ 42-46；`IN_PROCESS_TEAMMATE_ALLOWED` @ 49-52；`filterForAgent(source, spec)` @ 60-62；`filterForAgent(source, spec, isAsync, isCustom, isInProcessTeammate)` @ 77-133；`isMcpTool` @ 135-137）
- 依赖任务: 无（独立模块）
- 完成标准:
 - `ALWAYS_DISALLOWED` 含 7 项（`TaskOutput / ExitPlanMode / EnterPlanMode / Agent / AskUserQuestion / TaskStop / Workflow`）；
 - `ASYNC_ALLOWED` 含 15 项（详见 checklist 7.1）；
 - `mcp__` 前缀工具直接通过；
 - 异步模式下 in-process teammate 额外允许 `Agent` + `IN_PROCESS_TEAMMATE_ALLOWED` 8 项；
 - 自定义 spec 的 `disallowedTools` 与 `tools`（白名单交集）都生效；
 - `tools == ["*"]` 视为无白名单（即不过滤）。
- [ ] 完成

## T5: 实现 `SubAgentTaskManager` 状态机 + 通知队列
- 影响文件: `src/main/java/com/mewcode/subagent/SubAgentTaskManager.java`（`TaskStatus` @ 19；`Task` @ 21；`TaskNotification` @ 23；`TaskEntry` @ 29-42；`createTask` @ 44-48；`setRunning` @ 50-56；`setCompleted` @ 58-65；`setFailed` @ 67-74；`cancelTask` @ 76-85；`drainNotifications` @ 87-91；`getTask` @ 93-97；`listTasks` @ 99-103）
- 依赖任务: 无
- 完成标准:
 - 状态机覆盖 `PENDING / RUNNING / COMPLETED / FAILED / CANCELLED`；
 - `setCompleted` / `setFailed` / `cancelTask` 各自把 `TaskNotification` 入队；
 - `drainNotifications` 一次性取出并清空，返回不可变拷贝；
 - 所有公共方法 `synchronized`；
 - `nextId` 用 `AtomicInteger`，taskId 形如 `task_N`。
- [ ] 完成

## T6: 实现 `SubAgentTaskManager.spawnSubAgent`（后台虚拟线程）
- 影响文件: `src/main/java/com/mewcode/subagent/SubAgentTaskManager.java`（`spawnSubAgent` @ 108-164；`truncate` @ 166-168）
- 依赖任务: T1, T4, T5
- 完成标准:
 - 调 `createTask` 拿 `task_N`；
 - `Thread.startVirtualThread` 启动后台线程；
 - 内部 `ToolFilter.filterForAgent(registry, spec)` 拿子 registry（注：本章 spawn 路径不带 async 标志，等价 sync 过滤）；
 - 启动 `subAgent.run(conv)` 拿 `BlockingQueue<AgentEvent>`；
 - 事件循环：`StreamText` 累积；`ErrorEvent` → `setFailed`；`LoopComplete` → `setCompleted`；`InterruptedException` → `setFailed("Interrupted")`；`poll(60s)` 超时 → `setFailed("Timeout")`；
 - 线程引用通过 `setRunning(taskId, thread)` 写回。
- [ ] 完成

## T7: 实现 `AgentTool` 框架 + `schema()` + `description()`
- 影响文件: `src/main/java/com/mewcode/subagent/AgentTool.java`（类头 @ 29-66；构造器 + setter @ 68-104；`name()` @ 108-111；`description()` @ 113-137；`category()` @ 139-142；`schema()` @ 144-196；`shouldDefer()` @ 198-201）
- 依赖任务: T1, T3
- 完成标准:
 - 实现 `Tool` 接口，`name() == "Agent"`；
 - `description()` 动态把 `agentSpecs` 里的 agent 列出来；缺省时 fallback 列出三档 builtin；
 - `schema()` 暴露 6 个属性：`description / prompt / subagent_type / model / run_in_background / isolation / team_name`；`subagent_type.enum` 由 `AgentLoader.listNames(agentSpecs)` 动态生成；
 - `required = ["description", "prompt"]`；
 - `shouldDefer() == true`；
 - `FORK_BOILERPLATE_TAG = "<fork_boilerplate>"`，`FORK_BOILERPLATE` text block 含五条规则。
- [ ] 完成

## T8: 实现 `AgentTool.execute` 五条分支
- 影响文件: `src/main/java/com/mewcode/subagent/AgentTool.java`（`execute` @ 204-240；`resolveSpec` @ 415-425；`getStringArg` @ 522-525）
- 依赖任务: T6, T7
- 完成标准:
 - 缺 `description` / `prompt` 返回 `ToolResult.error("Error: description and prompt are required")`；
 - 分支顺序：`subagent_type` 空 → `runFork`；`teamName != null && teamManager != null` → `runAsTeammate`；`run_in_background == true` → `runAsync`；默认 → `runSync`；
 - `resolveSpec` 优先查 `agentSpecs`，回退到 switch 三档 builtin；
 - 未知 `subagent_type` 返回 `Error: unknown agent type '...'. Available: ...`。
- [ ] 完成

## T9: 实现 `runSync`（前台流式 + 可选 worktree）
- 影响文件: `src/main/java/com/mewcode/subagent/AgentTool.java`（`runSync` @ 310-413；`selectClient` @ 489-501；`emitProgress` @ 503-516；`elapsedSeconds` @ 518-520）
- 依赖任务: T4, T8
- 完成标准:
 - `ToolFilter.filterForAgent(parentRegistry, spec)` 拿子 registry；
 - 子 Agent `maxIterations` 取 `spec.maxTurns()` 或 fallback 200；
 - 事件循环消费 `StreamText` 累积输出 / `ToolResultEvent` 发 progress / `ErrorEvent` 报错退出 / `LoopComplete` 结束；
 - `poll(60, SECONDS)` 超时返回 `Agent timed out waiting for events`；
 - `isolation == "worktree"` 且 `worktreeManager != null` 时创建临时分支，slug `agent-aXXXXXXX`（7 位 hex）；
 - 结束时 `WorktreeChanges.hasChanges` 决定保留 / 调用 `AgentWorktree.remove`；
 - 最终消息含 `Agent "%s" completed in %d.%03ds.\n\n%s%s`。
- [ ] 完成

## T10: 实现 `runFork`（fork 父对话）
- 影响文件: `src/main/java/com/mewcode/subagent/AgentTool.java`（`runFork` @ 255-282；`buildForkedConversation` @ 284-308）
- 依赖任务: T6, T8
- 完成标准:
 - `parentConversation == null` → 报错 `Error: fork requires parent conversation context`；
 - `taskManager == null` → 报错 `Error: fork requires task manager for background execution`；
 - 扫父对话每条 `getContent().contains(FORK_BOILERPLATE_TAG)` → 报错 `Error: cannot fork from a forked agent. Use subagent_type to spawn a definition-based agent instead.`；
 - `buildForkedConversation`：对带 `toolUses` 但无 `toolResults` 的 assistant 消息走 `addAssistantFull` + 追加占位 `ToolResultBlock("(tool execution interrupted by fork)")`；对带 `toolUses` 有 `toolResults` 的走 `addAssistantFull`；对纯 assistant 走 `addAssistantMessage`；对 user 走 `addUserMessage`；
 - 最后 `addUserMessage(FORK_BOILERPLATE + "\n\nYour task:\n" + task)`；
 - fork 始终调 `taskManager.spawnSubAgent`，提示文案含 `Forked agent "%s" launched in background (task %s). Results will arrive via task-notification.`。
- [ ] 完成

## T11: 实现 `runAsync`（builtin spec → 后台）
- 影响文件: `src/main/java/com/mewcode/subagent/AgentTool.java`（`runAsync` @ 244-253）
- 依赖任务: T6, T8
- 完成标准:
 - `taskManager == null` → 报错 `Background execution not available (no task manager configured)`；
 - 调 `selectClient(spec.model(), modelOverride)` 拿子 client；
 - 调 `taskManager.spawnSubAgent` 拿 `task_N`；
 - 返回 `Agent "%s" launched in background (task %s). You will be notified when it completes.`。
- [ ] 完成

## T12: 实现 `runAsTeammate`（团队成员路径，衔接 ch15）
- 影响文件: `src/main/java/com/mewcode/subagent/AgentTool.java`（`runAsTeammate` @ 427-487）
- 依赖任务: T8（ch15 的 `SpawnDispatcher.spawnTeammate`）
- 完成标准:
 - 校验 `teamManager.getTeam(teamName) != null`，否则报错 `Error: team '%s' not found. Create it first with TeamCreate.`；
 - memberName 用 `description` 处理（小写 + `\\s+` 替换为 `-` + 截断 30 字符 + 同名递增 `-2 / -3 ...`）；
 - `ToolFilter.filterForAgent` 之后注入 `TeamTools.SendMessageTool(teamManager, memberName)`；
 - 可选 worktree 隔离（同 `runSync` 逻辑）；
 - 调 `SpawnDispatcher.spawnTeammate(SpawnConfig(...))` 拿 `spawnResult`；
 - 返回 `Teammate "%s" spawned in team "%s" (mode: %s). The teammate is now working on the assigned task.`。
- [ ] 完成

## T13: 接入主流程
- 影响文件: 主 Agent 装配点（`cmd/mewcode/main.go` 对应的 Java 装配类，例如 `com.mewcode.Main` 或 `TuiBootstrap`）
- 依赖任务: T1-T12
- 完成标准:
 1. 构造 `AgentTool(client, registry, protocol)` 后通过 setter 注入 `agentSpecs`（来自 `AgentLoader.loadAll(projectRoot)`）、`taskManager` (`new SubAgentTaskManager()`)、`progressListener`、`parentConversation`、`worktreeManager`、`teamManager`、`modelResolver`；
 2. `registry.register(agentTool)`；
 3. 主 Agent 的 `notificationFn` 绑定到一个把 `taskManager.drainNotifications()` 转成可读字符串列表的 supplier。
- [ ] 完成

## T14: 端到端验证
- 影响文件: 无（仅运行验证）
- 依赖任务: T13
- 完成标准:
 - `./gradlew build` 成功；
 - SubAgent 模块单测全通过（loader 解析正确 / 三档 builtin 字段断言 / 六层过滤分支覆盖 / TaskManager 状态机覆盖 / `runFork` 嵌套拒绝）；
 - 手动跑一次：主 Agent → 调 `Agent` 工具（`subagent_type=plan`）→ 看到 `Agent "..." completed in ...` 输出；
 - 手动跑一次：主 Agent → 调 `Agent` 工具（`run_in_background=true`）→ 看到 `task_N` 立即返回，下一轮收到完成通知。
- [ ] 完成

## 进度
- [ ] T1 / [ ] T2 / [ ] T3 / [ ] T4 / [ ] T5 / [ ] T6 / [ ] T7 / [ ] T8 / [ ] T9 / [ ] T10 / [ ] T11 / [ ] T12 / [ ] T13 / [ ] T14