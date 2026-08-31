# 工具系统 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为；括号内为验证方式与对应需求。
> 命令用 `mvn`（Java 21 / 顶层包 `dinocode`）。
> 标记：`[x]` = 已用单测/离线 E2E 验证；`[ ]` = 需真实 key 或交互终端，人工执行。

## 实现完整性
- [x] 注册中心导出 6 条工具定义且按名可查（`ToolRegistryTest#definitionsReturnsSixOrdered`，断言 size==6、名称有序、`get` 命中/未命中）。(AC1/F1)
- [x] read_file 带行号读出内容；读不存在/目录返回结构化错误（`ToolRegistryTest#readFileExists/Missing/Directory`）。(AC2/F2)
- [x] write_file 创建/覆盖文件，父目录自动创建（`ToolRegistryTest#writeFileNestedDir`，`@TempDir` 写 `a/b/c.txt`）。(AC3/F2)
- [x] edit_file 唯一匹配替换；0 处与 >1 处返回可区分错误（`ToolRegistryTest#editFileZero/Unique/Multiple`，文案不同且 >1 含 N）。(AC4/F2)
- [x] bash 返回 stdout/退出码；超时被终止（`ToolRegistryTest#bashEcho/Timeout`）。(AC5/F2/N1)
- [x] glob 列出匹配文件；grep 返回 `file:line:content`（`ToolRegistryTest#globStarStarJava/grepKeyword`）。(AC6/F2)
- [x] 流式工具调用解析：工具名与 JSON 参数拼齐（`OpenAiProviderTest#replaysToolCallsIntoToolCallComplete` + `AnthropicProviderTest#replaysToolUseIntoTextAndToolCall`）。(AC7/F4)
- [x] 单轮闭环端到端：请求#1 工具调用 → 执行 → 回灌 → 请求#2 最终答复（`AgentTest` + `AgentHttpE2eTest` 离线 mock）。(AC8/F5/F6)
- [x] 单轮上限：第二轮工具调用被忽略、不发起第三轮（`AgentTest#secondToolCallIsIgnored`）。(AC9/F6)
- [ ] 工具行 Claude Code 风格 `● name(args)` + 缩进结果摘要（代码已写 `Renderer.toolLine/toolSummary`，需交互终端肉眼验证）。(AC11/F8)
- [ ] 工具失败结构化回灌且 UI 可区分、程序不退出（结构化错误已单测；「程序不退出」需交互终端验证）。(AC12/F9/N4)

## 集成
- [ ] 两协议工具流程一致：anthropic 与 openai（含兼容 base_url）跑同一组任务，行为一致（解析已分别单测；真实链路需真实 key）。(AC10/F0/F3/F7/N3)
- [x] 结果回灌进历史并被第二轮请求携带（`AgentHttpE2eTest`：请求#2 body 含 `tool_call_id`）。(F6)
- [ ] 工具执行不阻塞界面：执行期间显示进行中指示，界面可响应（需交互终端验证）。(N2)
- [ ] scrollback 顺序正确（preamble → 工具行 → 结果摘要 → 最终答复，需交互终端回滚验证）。(F8)
- [x] 结果体量受控：截断并标 `[truncated]`（`ToolRegistryTest#truncateCapsLinesAndBytes` + 各工具上限）。(AC13/N5)
- [x] 系统提示词体现 Agent 角色（`Prompt.SYSTEM_PROMPT` 已注入 OpenAI/Anthropic 请求）。(F3)

## 编译与测试
- [x] `mvn test` 通过（77 个测试全绿）。
- [x] `mvn package` 无错误（fat jar `target/dinocode.jar` 可启动）。
- [x] 密钥不回显/不打印（代码不打印 `api_key`）。(N6)

## 端到端场景
- [ ] 场景 1（读文件并总结）：真实 openai 兼容端点 → 问「读 docs/ch03/Spec.md 用一句话总结」→ 工具行 + 结果摘要 + 最终答复（需真实 key）。
- [ ] 场景 2（写/改/执行链路）：让模型「新建文件并写入，再用 bash 查看」→ WriteFile + Bash 工具行依次出现（需真实 key）。
- [ ] 场景 3（错误恢复）：让模型 edit 不存在的文本 → 结构化错误、程序不退出、可继续对话（需真实 key）。
- [ ] 场景 4（跨协议）：切 anthropic 配置重跑场景 1 → 与 openai 行为一致（需真实 key）。
