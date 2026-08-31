# Agent Loop Tasks

> 基于已批准的 Spec.md + plan.md（dinocode 栈版）。任务有序，每步留绿编译。验证一律「先跑命令看输出，再下结论」。
> 顶层包 `dinocode`（Java 21 / Maven），命令用 `mvn`。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 修改 | `src/main/java/dinocode/tool/Tool.java` | 接口加 `boolean readOnly()` |
| 修改 | `src/main/java/dinocode/tool/ToolRegistry.java` | `readOnlyDefinitions()`、`isReadOnly(name)` |
| 修改 | `src/main/java/dinocode/tool/{ReadFile,WriteFile,EditFile,Bash,Glob,Grep}Tool.java` | 各加 `readOnly()` |
| 修改 | `src/main/java/dinocode/prompt/Prompt.java` | `PLAN_MODE_REMINDER`、`EXECUTE_DIRECTIVE`；`SYSTEM_PROMPT` 增循环约定 |
| 修改 | `src/main/java/dinocode/provider/ChatRequest.java` | 加 `systemSuffix` 字段 + 3 参兼容构造 |
| 修改 | `src/main/java/dinocode/provider/{OpenAi,Anthropic}Provider.java` | `effectiveSystem(suffix)` |
| 新建 | `src/main/java/dinocode/agent/Mode.java` | `enum Mode { NORMAL, PLAN }` |
| 新建 | `src/main/java/dinocode/agent/CancelToken.java` | per-turn 取消句柄 |
| 新建 | `src/main/java/dinocode/agent/AgentConstants.java` | 上限与文案常量 |
| 修改 | `src/main/java/dinocode/agent/TurnEvent.java` | 加 `UsageReport`/`Iter`/`Notice` |
| 重写 | `src/main/java/dinocode/agent/Agent.java` | ReAct 循环、`executeBatched`、停止条件、历史收尾 |
| 修改 | `src/main/java/dinocode/tui/Tui.java` | `mode`/`usage` 累计、`/plan /do`、按键、新事件分派 |
| 修改 | `src/main/java/dinocode/tui/Renderer.java` | `iter()` 轮次提示 |
| 重写 | `src/test/java/dinocode/agent/AgentTest.java` | 多轮、并发分批、停止条件、Plan、取消、流出错 |
| 修改 | `src/test/java/dinocode/agent/AgentHttpE2eTest.java` | `run` 调用补 `Mode`/`CancelToken` |
| 修改 | `src/test/java/dinocode/tool/ToolRegistryTest.java` | 只读分类断言 |

## T1: tool 只读分类 ✅

**文件：** `tool/{Tool,ToolRegistry}.java` + 6 工具
**步骤：** `Tool` 加 `readOnly()`；ReadFile/Glob/Grep → true，Write/Edit/Bash → false；`ToolRegistry` 加 `readOnlyDefinitions()`（按注册序过滤）与 `isReadOnly(name)`（未知 false）。

**验证：** `mvn -q compile` 通过。

## T2: prompt 与 provider 系统后缀 ✅

**文件：** `prompt/Prompt.java`、`provider/ChatRequest.java`、两个 Provider
**步骤：** `Prompt` 增循环约定、`PLAN_MODE_REMINDER`、`EXECUTE_DIRECTIVE`；`ChatRequest` 加第 4 字段 `systemSuffix`（null 归一空串）+ 3 参兼容构造；两适配器 `effectiveSystem(suffix)` 拼接。

**验证：** `mvn -q compile` 通过；现有测试不回归。

## T3: agent 包扩展（Mode/CancelToken/Constants/TurnEvent） ✅

**文件：** `agent/{Mode,CancelToken,AgentConstants,TurnEvent}.java`
**步骤：** 见 plan「核心数据结构」。`TurnEvent` 加 `UsageReport`/`Iter`/`Notice` 三个 record。

**验证：** `mvn -q compile` 通过。

## T4: Agent 重写为 ReAct 循环 ✅

**文件：** `agent/Agent.java`
**步骤：** 见 plan「run 算法」：`run(history, maxTokens, mode, cancel)` → virtual thread 内 `for` 循环（Iter 事件 → streamOnce → UsageReport → 无工具自然完成 / 有工具分批执行回灌 → 取消/未知工具/上限收尾）。`executeBatched` 保序分批：连续只读合批（virtual thread + CountDownLatch + 独占下标），有副作用串行；Start/End 事件均按调用序。`ensureAssistantTail` 保证历史以 assistant 收尾。

**验证：** `mvn -q compile` 通过。

## T5: TUI 接线 ✅

**文件：** `tui/{Tui,Renderer}.java`
**步骤：** `Tui` 加 `mode`/`usageIn`/`usageOut`/`turnCancel`；`/plan`、`/do` 命令；`turn()` 传 `mode`+`CancelToken`，分派新事件；Ctrl+C（生成中）→ `turnCancel.cancel()`；`Renderer.iter()` 轮次提示；PLAN 提示符徽标。

**验证：** `mvn -q compile` 通过。

## T6: 测试重写与扩展 ✅

**文件：** `test/agent/{AgentTest,AgentHttpE2eTest}.java`、`test/tool/ToolRegistryTest.java`
**步骤：** AgentTest 七场景：A 多轮链路、B 迭代上限、C 连续未知工具（含混入重置）、D 保序分批并发（插桩工具断言峰值≥2 + rw 后置 + Start/End 按调用序 + 结果按原序回灌）、E 取消历史一致、F Plan 工具集与后缀、G 流出错。AgentHttpE2eTest 补 `Mode.NORMAL` + `new CancelToken()`。

**验证：** `mvn test` 全绿。

## T7: 全量验证

**步骤：** `mvn test`、`mvn package`；端到端（真实 key）跑多轮任务、取消、Plan Mode、流出错恢复（见 Checklist）。

**验证：** 全部命令通过、端到端符合预期。

## 执行顺序

```
T1 → T2 → T3 → T4 → T5 → T6 → T7
```
