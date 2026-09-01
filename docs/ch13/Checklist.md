# ch13: SubAgent Checklist（Java 版）

> 所有条目可勾选、可观测。验收方式写在条目后面括号中。验收：已通过验证的项均勾选。

## 1. 实现完整性

- [ ] 类 `AgentTool` 在 `src/main/java/com/mewcode/subagent/AgentTool.java:29-526` 存在，字段含 `client / parentRegistry / protocol / modelResolver / agentSpecs / progressListener / taskManager / parentConversation / worktreeManager / teamManager`
- [ ] record `SubAgentSpec` 在 `src/main/java/com/mewcode/subagent/SubAgentSpec.java:10-18` 存在，七个字段（`name / description / tools / disallowedTools / systemPromptOverride / maxTurns / model`）齐全
- [ ] record `SubAgentProgress` 在 `src/main/java/com/mewcode/subagent/SubAgentProgress.java:16-25` 存在，八个字段齐全
- [ ] 类 `SubAgentTaskManager` 在 `src/main/java/com/mewcode/subagent/SubAgentTaskManager.java:17-169` 存在；含 `TaskStatus` enum（`PENDING / RUNNING / COMPLETED / FAILED / CANCELLED`）、`Task` record、`TaskNotification` record、`TaskEntry` 内部类
- [ ] 三档 builtin（`GENERAL_PURPOSE / PLAN / EXPLORE`）在 `SubAgentSpec.java:56-85` 注册，分别对应 `maxTurns = 200 / 15 / 30`
- [ ] `ToolFilter.filterForAgent` 在 `src/main/java/com/mewcode/subagent/ToolFilter.java:77-133` 实现六层过滤
- [ ] `AgentLoader.parseAgentFile` 在 `src/main/java/com/mewcode/subagent/AgentLoader.java:95-150` 校验 `name` / `description` 必填，`model` 取值白名单（`VALID_MODELS` @ 27）
- [ ] `AgentTool.runFork` 在 `agent_tool` 对应 `AgentTool.java:255-282` 嵌套 fork 检查（扫描 `<fork_boilerplate>` 标签）
- [ ] `buildForkedConversation` 在 `AgentTool.java:284-308` 给悬挂 `toolUses` 补占位 `ToolResultBlock("(tool execution interrupted by fork)")`
- [ ] 错误消息 `"Error: cannot fork from a forked agent. Use subagent_type to spawn a definition-based agent instead."` 在 `AgentTool.java:266` 与文档描述的 isInForkChild 语义一致

## 2. 接入完整性（必查，杜绝死代码）

- [ ] `AgentTool` 实例由主装配点构造并通过 setter 注入依赖：`setAgentSpecs(AgentLoader.loadAll(projectRoot))` / `setTaskManager(new SubAgentTaskManager())` / `setProgressListener(...)` / `setParentConversation(...)` / `setWorktreeManager(...)` / `setTeamManager(...)` / `setModelResolver(...)`
- [ ] `registry.register(agentTool)` 在装配阶段调用
- [ ] 主 Agent 的 `notificationFn` 绑定 `() -> taskManager.drainNotifications().stream().map(...).toList()`，使后台任务完成通知能在下一轮注入 conversation（`com.mewcode.agent.Agent.agentLoop` @ 79-83）
- [ ] `SubAgentProgress` 的消费者（TUI / 日志）订阅 `progressListener` 并把工具调用计数 / 失败状态展示给用户
- [ ] `AgentTool.shouldDefer() == true`（`AgentTool.java:198-201`），确认 `Agent` 工具的 schema 只在 ToolSearch 选中时下发

## 3. 编译与测试

- [ ] `./gradlew build` 通过
- [ ] SubAgent 模块单测全部 PASS（loader / tool_filter / task_manager / fork 嵌套拒绝）

## 4. 端到端验证

- [ ] 注册路径：主装配点 register 完毕后，用户向主 Agent 发送 "spawn a plan agent to review X" → LLM 返回 `Agent` 工具调用 → `execute` → `runSync(spec=plan)` → 子 Agent 流式输出 → 控制台见 `Agent "..." completed in X.XXXs.`
- [ ] Fork 路径：用户在对话进行中说 "fork to investigate Y" → LLM 调用 `Agent` 不带 `subagent_type` → `runFork` → forked conversation 启动后台 task → 完成时 `TaskNotification` 通过 `drainNotifications` 注入下一轮
- [ ] 后台路径：调用带 `run_in_background=true` → 立即返回 `task_N` → 后台虚拟线程跑完 → 主 Agent 下一轮拿到完成通知
- [ ] 工具过滤验证：子 Agent 调 `Agent` 工具应直接被过滤掉（`ALWAYS_DISALLOWED` 命中），子 Agent 看不到 `Agent` 工具，从根源切断递归

## 5. 文档

- [ ] `docs/java/ch13/spec.md` 已写
- [ ] `docs/java/ch13/tasks.md` 已写，14 个 T 全部勾完
- [ ] `docs/java/ch13/checklist.md` 已写并逐项验收

---

## 6. 工具过滤细节验收

### 6.1 全局禁止集合 `ALWAYS_DISALLOWED`（7 项）

- [ ] `ToolFilter.java:30-33` 含七项：`TaskOutput / ExitPlanMode / EnterPlanMode / Agent / AskUserQuestion / TaskStop / Workflow`

### 6.2 异步白名单 `ASYNC_ALLOWED`（15 项）

- [ ] `ToolFilter.java:42-46` 含 15 项：`ReadFile / WebSearch / TodoWrite / Grep / WebFetch / Glob / Bash / EditFile / WriteFile / NotebookEdit / Skill / LoadSkill / SyntheticOutput / ToolSearch / EnterWorktree / ExitWorktree`（实际计 16 个名字，记 15 个槽位的扩展含义参照 Go 对照表）

### 6.3 In-process teammate 额外允许 `IN_PROCESS_TEAMMATE_ALLOWED`（8 项）

- [ ] `ToolFilter.java:49-52` 含 8 项：`TaskCreate / TaskGet / TaskList / TaskUpdate / SendMessage / CronCreate / CronDelete / CronList`
- [ ] `filterForAgent(source, spec, isAsync=true, isCustom=*, isInProcessTeammate=true)` 在异步白名单层额外放行 `Agent` 与上述 8 项

### 6.4 六层过滤顺序

- [ ] 第 1 层：`isMcpTool(name)`（`mcp__` 前缀）直接 register
- [ ] 第 2 层：`ALWAYS_DISALLOWED` 命中 continue
- [ ] 第 3 层：`isCustom && CUSTOM_AGENT_DISALLOWED.contains(name)` continue
- [ ] 第 4 层：`isAsync == true` 时，非 `ASYNC_ALLOWED` 工具一律 continue，除非 `isInProcessTeammate` 且命中 `Agent` 或 teammate 集合
- [ ] 第 5 层：`spec.disallowedTools()` 黑名单 continue
- [ ] 第 6 层：`spec.tools()` 白名单交集（`["*"]` 视为无白名单）

## 7. AgentLoader 验收

- [ ] `loadAll(projectRoot)` 顺序：builtin → `~/.mewcode/agents` → `<projectRoot>/.mewcode/agents`（`AgentLoader.java:39-53`）
- [ ] `LinkedHashMap` 保 put 覆盖语义，同名后注册胜出
- [ ] `parseAgentFile` 缺 `name` 抛 `Agent definition <path>: missing required field 'name'`
- [ ] `parseAgentFile` 缺 `description` 抛 `Agent definition <path>: missing required field 'description'`
- [ ] `parseAgentFile` 非法 `model` 抛 `Agent definition <path>: invalid model '<value>'`
- [ ] 解析失败的文件被 `loadDir` catch 后静默跳过，不影响其他文件加载
- [ ] body 为空时 `systemPromptOverride == null`，非空则等于 trimmed body

## 8. TaskManager 验收

- [ ] `createTask` 返回 `task_N`，`N` 从 `AtomicInteger.incrementAndGet()` 取（`SubAgentTaskManager.java:44-48`）
- [ ] `setRunning` 把 `Thread` 引用挂到 `TaskEntry.thread`
- [ ] `setCompleted` 把 `TaskNotification(id, name, COMPLETED, output)` 入队
- [ ] `setFailed` 把 `TaskNotification(id, name, FAILED, errMsg)` 入队
- [ ] `cancelTask` 仅在 `RUNNING` 状态生效，转 `CANCELLED` + `Thread.interrupt()` + 入队 `CANCELLED` 通知
- [ ] `drainNotifications` 返回拷贝并清空原列表
- [ ] 所有公共方法 `synchronized`
- [ ] `spawnSubAgent` 用 `Thread.startVirtualThread` 启动后台线程（`SubAgentTaskManager.java:117`）
- [ ] 事件循环超时 60s → `setFailed("Timeout")`；`InterruptedException` → `setFailed("Interrupted")`
- [ ] `LoopComplete` 时输出为空回退到 `"(agent produced no output)"`

## 9. AgentTool runSync 验收

- [ ] `maxIterations = spec.maxTurns() > 0 ? spec.maxTurns() : 200`（`AgentTool.java:315-316`）
- [ ] `isolation == "worktree"` 时 slug 形如 `agent-aXXXXXXX`（`SecureRandom` 4 字节 hex 取前 7）（`AgentTool.java:321-323`）
- [ ] worktree 创建失败返回 `Error creating agent worktree: <msg>`
- [ ] `LoopComplete` 后 `WorktreeChanges.hasChanges(path, headCommit)` 为真保留并附 `\n\nWorktree kept at <path> (branch <branch>) — has uncommitted changes or new commits.`；为假调 `AgentWorktree.remove`
- [ ] 最终 `ToolResult.success` 文案：`Agent "%s" completed in %d.%03ds.\n\n%s%s`

## 10. AgentTool 文案（Tool 接口可读性）

- [ ] `description()` 当 `agentSpecs` 非空时按 `AgentLoader.listNames` 字典序枚举可用 agent（`AgentTool.java:123-127`）
- [ ] `description()` 缺省提示三档 builtin（fallback 文案）
- [ ] `schema()` 的 `subagent_type.enum` 与 `description()` 列出的 agent 类型一致

## 11. 模型选择 `selectClient`

- [ ] `selectClient(specModel, overrideModel)` 优先取 `overrideModel`，其次 `specModel`，再次 fallback 到父 client（`AgentTool.java:489-501`）
- [ ] `model == "inherit" || model == ""` 直接返回父 client
- [ ] `modelResolver != null` 时调 `modelResolver.apply(model)`，结果 null 时 fallback 父 client
- [ ] `ModelResolver.ALIASES` 含 `haiku / sonnet / opus` 三个键（`src/main/java/com/mewcode/llm/ModelResolver.java:7-11`）

## 12. 父子 Agent 联动（`com.mewcode.agent.Agent`）

- [ ] `notificationFn` setter 存在（`Agent.java:46`）；主循环每轮开头通过 `notificationFn.get()` 抽取并 `addSystemReminder`（`Agent.java:79-83`）
- [ ] 子 Agent 复用同一套 `agentLoop`，由 `subAgent.run(conv)` 启动虚拟线程并返回 `BlockingQueue<AgentEvent>`（`Agent.java:50-60`）
- [ ] `setMaxIterations` 在 `runSync` / `spawnSubAgent` 内被显式设置