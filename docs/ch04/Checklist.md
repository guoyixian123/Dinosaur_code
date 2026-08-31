# Agent Loop Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为；括号内为验证方式与对应需求。
> 命令用 `mvn`（Java 21 / 顶层包 `dinocode`）。
> 标记：`[x]` = 已用单测/离线 E2E 验证；`[ ]` = 需真实 key 或交互终端，人工执行。

## 实现完整性
- [ ] 多轮自动连环：需要连续两步工具的任务，Agent 无需中途催促即自动多轮执行工具直到给出最终答复（验证：`java -jar target/dinocode.jar` 跑「读 A 文件 → 据内容新建 B 文件」，观察工具跨多轮依次出现、最终答复）。(AC1/F1)
- [x] 自然完成停止：模型给出无工具调用的纯文本即停（`AgentTest#multiTurnLoopRunsUntilFinalText`）。(AC2/F2)
- [x] 迭代上限兜底：恰好 `MAX_ITERATIONS` 轮后停 + `Notice(NOTICE_MAX_ITER)`（`AgentTest#iterationCapStopsLoop`）。(AC3/F2)
- [x] 连续未知工具停止：连续 `MAX_UNKNOWN_RUN` 轮即停；混入已注册工具计数重置（`AgentTest#consecutiveUnknownToolsStopLoop` / `#mixedKnownToolResetsUnknownCounter`）。(AC4/F2)
- [x] 流出错恢复：停止本轮、发 Error、会话可继续（`AgentTest#streamFailureStopsLoopAndKeepsSession`；真实恢复需端到端）。(AC5/F2)
- [x] 事件流完备：Text/Thinking/ToolStart/ToolEnd/UsageReport/Iter/Notice/Done/Error 全部定义并被 TUI 分派（`AgentTest` + `Tui.turn`）。(AC6/F3)
- [x] 流式收集双路：文本实时转发同时完整工具调用被收集用于下一轮（`AgentTest` 场景 A 断言历史 tool_use 回合）。(AC7/F4)
- [x] 保序分批并发：只读并发峰值≥2、有副作用工具在只读批之后、Start/End 按调用序、结果按原序回灌（`AgentTest#readOnlyBatchRunsConcurrentlyInOrder`）。(AC8/F5/N6)
- [x] 取消历史一致：取消后历史配对合法（tool_results + assistant 尾巴，无悬空 tool_use）、不再发起下一轮（`AgentTest#cancelMidExecutionKeepsHistoryLegal`）。(AC9/F6)
- [ ] 用户取消：流式态 Esc 或 Ctrl+C 中断本轮回空闲态、不退出；空闲态 Ctrl+C 退出（需交互终端验证；机制：SIGINT → `turnCancel.cancel()`）。(AC10/F7)
- [ ] 用量展示：会话累计 token 随轮次增长（累加逻辑已实现 `Tui.accumulateUsage`；展示需端到端验证）。(AC11/F8)
- [ ] 进度展示：流式态显示当前迭代轮次（`Renderer.iter` 已实现；视觉需端到端验证）。(AC12/F9)
- [x] Plan Mode：`Mode.PLAN` 只发只读工具定义 + `PLAN_MODE_REMINDER` 后缀（`AgentTest#planModeSendsReadOnlyToolsAndSuffix`）；`/plan`/`/do` 已接线（真实切换需端到端）。(AC13/F10)

## 集成
- [ ] 跨协议一致：anthropic 与 openai（含兼容 base_url）跑同一多轮任务，行为一致（需真实 key）。(AC14/F11/N3)
- [x] 多轮历史正确携带：assistant(tool) + tool 回合按序入历史并被下一轮请求携带（`AgentTest` 场景 A 断言历史序列）。(F6)
- [ ] 界面不阻塞：多轮循环与并发批期间 spinner/轮次刷新（需交互终端）。(N2)
- [ ] scrollback 顺序正确：preamble → 工具行 → 摘要 → 最终答复按序（需交互终端）。(N3)
- [x] 结果体量受控：工具级截断沿用 ch03（`ToolRegistryTest#truncateCapsLinesAndBytes`）。(N4)
- [x] 取消无泄漏：取消用例通过、virtual thread 随 latch/队列自然收敛（`AgentTest` 场景 E）。(N5)
- [ ] 系统提示体现 Agent 循环：问「你能做什么」答复体现多步工具能力（需真实模型）。(F3)

## 编译与测试
- [x] `mvn test` 通过（含 AgentTest 七场景 + 全部回归）。
- [x] `mvn package` 无错误（fat jar `target/dinocode.jar`）。
- [x] 密钥不回显（代码不打印 api_key）。(N7)

## 端到端场景
- [ ] 场景 1（多轮连环）：openai 兼容端点 → 「读 docs/ch03/Spec.md，再据内容新建摘要文件」→ 工具跨多轮自动出现 → 最终答复（需真实 key）。
- [ ] 场景 2（用户取消）：多步任务中途 Esc / Ctrl+C → 回空闲不退出 → 再发一条继续对话（需真实 key + 交互终端）。
- [ ] 场景 3（流出错恢复）：临时改坏 base_url 发一条 → 错误提示不退出 → 改回后继续（需真实 key）。
- [ ] 场景 4（Plan Mode）：/plan → 问改动类需求 → 只出现只读工具 + 计划文本 → /do → 按计划执行出现写/执行工具（需真实 key）。
- [ ] 场景 5（跨协议）：切 anthropic 配置重跑场景 1，行为一致（需真实 key）。
