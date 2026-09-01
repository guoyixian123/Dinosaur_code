# ch13: SubAgent Spec（Java 版）

## 1. 背景

主 Agent 做大任务时会塞满上下文：研究、规划、写代码、跑测试都堆在一个对话里，单一窗口很快耗尽。这一章把"开一个上下文隔离的新 Agent 去做一件事"做成主 Agent 可以直接调用的工具，让主 Agent 学会分发工作，避免上下文爆炸，同时通过专门角色（plan / explore）和后台异步执行扩展并发能力。

## 2. 目标

提供 `Agent` 工具（`AgentTool implements Tool`），主 Agent 在对话里写一次工具调用即可：1) 按 `subagent_type` 启动一个定义式专家子 Agent（系统提示词、模型、工具白名单都按 Markdown 定义文件来），2) 不带 `subagent_type` 时直接 fork 当前对话上下文跑一个临时子 Agent，3) 带 `team_name` 时把这个 spawn 注册成长期团队成员（衔接 ch15）。后台任务的完成通过 `TaskNotification` 由父 Agent 在下一轮抽取注入。

## 3. 功能需求

- F1: `AgentTool` 实现 `com.mewcode.tool.Tool` 接口，注册到主 Agent 的 `ToolRegistry`，被 LLM 当成普通工具调用；`shouldDefer()` 返回 `true`，只在 ToolSearch 选中时才把 schema 暴露给模型。
- F2: 三档内建 Agent 类型 `general-purpose` / `plan` / `explore`（`SubAgentSpec.GENERAL_PURPOSE / PLAN / EXPLORE` 静态实例），每档可定制工具黑名单（`disallowedTools`）、最大轮数（`maxTurns`）、模型（`model`）、系统提示词覆盖（`systemPromptOverride`）。
- F3: `AgentLoader.loadAll(projectRoot)` 按 builtin → `~/.mewcode/agents/*.md`（用户级）→ `<projectRoot>/.mewcode/agents/*.md`（项目级）顺序加载，同名后注册覆盖前者；Markdown frontmatter 解析为 `SubAgentSpec`。
- F4: 三种执行路径：sync（前台阻塞、`AgentTool.runSync` 流式回写 LLM）/ async（后台虚拟线程、立即返回 `task_N`）/ fork（fork 父对话上下文，强制后台）。
- F5: `SubAgentTaskManager` 跟踪后台子 Agent 生命周期（`PENDING / RUNNING / COMPLETED / FAILED / CANCELLED`），完成或失败时把 `TaskNotification` 入队，主 Agent 下一轮通过 `drainNotifications()` 取出并注入到 conversation。
- F6: 六层工具过滤（`ToolFilter.filterForAgent`）：MCP 豁免 → 全局禁（`ALWAYS_DISALLOWED`：`Agent` / `AskUserQuestion` 等 7 项防递归）→ custom agent 额外禁（`CUSTOM_AGENT_DISALLOWED`）→ async 白名单（`ASYNC_ALLOWED` 仅 15 项基础工具）→ definition 级黑名单 → definition 级白名单交集。
- F7: Fork 路径：构造完整 forked conversation（拷贝父消息，给悬挂的 `toolUses` 补 placeholder `ToolResultBlock("(tool execution interrupted by fork)")`），追加 fork boilerplate 系统约束 + 任务文本；fork-of-fork 通过扫描父对话内容中的 `<fork_boilerplate>` 标签拒绝。
- F8: 可选 worktree 隔离与 `WorktreeManager` 配合，子 Agent 在临时 git worktree 中跑；执行结束按 `WorktreeChanges.hasChanges(...)` 决定保留 / 移除。
- F9: 可选团队模式与 `TeamManager` 配合，走 `SpawnDispatcher.spawnTeammate` 注册长期团队成员（详见 ch15）。
- F10: in-process teammate 在 async 白名单层额外放行 `Agent` + `IN_PROCESS_TEAMMATE_ALLOWED`（`TaskCreate / TaskGet / TaskList / TaskUpdate / SendMessage / CronCreate / CronDelete / CronList`）。
- F11: 子 Agent 后台执行通过 `Thread.startVirtualThread` 启动；`cancelTask(id)` 通过 `Thread.interrupt()` 取消。
- F12: 模型选择 `selectClient` 优先用调用级 `model` 参数，其次用 spec 的 `model`，都没设或为 `inherit` / 空字符串时复用父 client；`ModelResolver` 把 `haiku/sonnet/opus` 别名解析为具体 model ID。
- F13: 父对话引用 (`parentConversation`) 由 TUI 通过 `setParentConversation` 注入；缺失时 fork 路径报错。

## 4. 非功能需求

- N1: 子 Agent 不能再调 `Agent` 工具（防止无限递归 / 上下文爆炸），任意层级的子 Agent 都通过 `ALWAYS_DISALLOWED` 屏蔽。
- N2: 后台 Agent 通过 `Thread.interrupt()` 受控；`cancelTask` 状态置为 `CANCELLED` 并发出对应 `TaskNotification`。
- N3: `SubAgentTaskManager` 所有公共方法用 `synchronized` 守护（虚拟线程与主线程同时操作 `tasks` / `notifications`）。
- N4: fork 操作必须先在父对话所有消息内容里搜 `<fork_boilerplate>` 标签拒绝嵌套 fork。
- N5: Sync 路径要走子 Agent 的完整 `BlockingQueue<AgentEvent>` 事件流：`StreamText` 累积输出 / `ToolResultEvent` 发 progress / `ErrorEvent` 报错退出 / `LoopComplete` 结束并清理 worktree。
- N6: Fork 子 Agent 复用父池工具（直接传 `parentRegistry`）与对话内容（含 `ThinkingBlock`），通过 `conv.addAssistantFull(content, thinkingBlocks, toolUses)` 保形。
- N7: 工具集传递使用 `ToolRegistry.listTools()` 枚举 + `register(tool)` 复制，避免污染父 registry。
- N8: 子 Agent 定义 frontmatter 字段集合需在解析层完整保留；未来章节扩展字段必须在解析层先存得下，避免重复迁移。

## 5. 设计概要

- 核心类型:
 - `AgentTool`（`src/main/java/com/mewcode/subagent/AgentTool.java`）：承载 `client` / `parentRegistry` / `protocol` / `modelResolver` / `agentSpecs` / `progressListener` / `taskManager` / `parentConversation` / `worktreeManager` / `teamManager` 等运行时依赖；`description()` 动态把可用 agent 类型拼进描述文案。
 - `SubAgentSpec`（record）：`name / description / tools / disallowedTools / systemPromptOverride / maxTurns / model`；`PLAN_AGENT_SYSTEM_PROMPT` 为 plan 角色的硬编码系统提示。
 - `SubAgentTaskManager`：内部 `TaskEntry`（id / name / status / output / error / thread）状态机；`TaskNotification` record；`spawnSubAgent` 启动虚拟线程。
 - `SubAgentProgress`（record）：进度事件，含 `agentType / description / toolName / toolOutput / toolError / done / toolCount / totalTime`。
 - `ToolFilter`：四个 `Set<String>`（`ALWAYS_DISALLOWED` 7 项 / `CUSTOM_AGENT_DISALLOWED` 7 项 / `ASYNC_ALLOWED` 15 项 / `IN_PROCESS_TEAMMATE_ALLOWED` 8 项）实现六层过滤。
 - `AgentLoader`：`VALID_MODELS = {"", "inherit", "haiku", "sonnet", "opus"}`；`parseAgentFile` 用 SnakeYAML 解析 frontmatter。
- 主流程:
 - 同步：用户消息 → 主 Agent → LLM 输出 `Agent` 工具调用 → `AgentTool.execute(args)` → 解析 `subagent_type` → `resolveSpec` → `runSync` → `ToolFilter.filterForAgent` → 构造子 `Agent` → `subAgent.run(conv)` → 消费 `BlockingQueue<AgentEvent>` 直到 `LoopComplete` → 返回结果。
 - 异步：调 `taskManager.spawnSubAgent`，立即返回 `Agent "..." launched in background (task task_N).`；后台虚拟线程跑完写 `setCompleted` 或 `setFailed`，主 Agent 下一轮 `drainNotifications` 抽出 `TaskNotification` 注入对话。
 - Fork：扫父对话 → 拷贝消息（含 `ThinkingBlock` 与悬挂 `toolUses` 占位 `ToolResultBlock`）→ 追加 `FORK_BOILERPLATE + "\n\nYour task:\n" + prompt` → 始终调 `taskManager.spawnSubAgent` 走后台。
 - 团队成员：校验 team 存在、name 去重 → 过滤工具集 + 注入 `SendMessageTool` → 调 `SpawnDispatcher.spawnTeammate` 拿 backend hint → 立即返回。
- 调用链:
 - 主流程组装在主 Agent 启动时把 `AgentTool` 注册到 `ToolRegistry`，并通过 setter 注入 `taskManager` / `agentSpecs` / `parentConversation` / `progressListener` / `worktreeManager` / `teamManager` / `modelResolver`。
 - Agent loop（`com.mewcode.agent.Agent.agentLoop`）每轮开头通过 `notificationFn` 抽取 `TaskNotification` 注入 `conv.addSystemReminder`。
- 与其他模块的交互:
 - 依赖 `com.mewcode.agent`（创建子 Agent）、`com.mewcode.conversation`（forked ConversationManager）、`com.mewcode.tool`（注册中心 + 过滤）、`com.mewcode.llm`（`LlmClient` / `ModelResolver`）、`com.mewcode.worktree`（隔离）、`com.mewcode.teams`（团队成员）。
 - 被主 Agent 装配点（`Main` / TUI 层）调用。

## 6. Out of Scope

- 子 Agent 输出全在内存事件流里，不落盘 task 输出文件。
- 不实现 RemoteAgent / DreamTask / LocalWorkflow / MonitorMcp 这些 TaskType。
- 不实现 fork 路径的 worktree notice（仅同步 isolation 路径支持）。
- 不接入 plugin / flag / managed 加载源（只支持 builtin / user / project）。
- 不消费 `skills` / `hooks` / `mcpServers` / `memory` / `permissionMode` 等扩展字段——本章 frontmatter 解析层保留五个核心字段，扩展字段留给后续章节。
- 不实现 PermissionMode 的 bubble / auto 模式。
- 不实现 120s 自动超时切后台 / ESC 切后台 / 持久化后台恢复。
- 不实现 `isolation: remote` 远端运行后端。
- 不内置 Verification 等附加 Agent。
- 不在本章实现 Fork 模式的字节级 prompt cache 命中重构（thinking blocks 拷贝已具备，但调用级 `useExactTools / cloneRegistryForFork` 留作后续）。

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。