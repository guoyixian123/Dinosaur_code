# Hook 生命周期挂钩系统 Checklist

> 每一项通过运行代码或观察行为来验证,聚焦系统行为。

## 实现完整性

### 权限匹配器扩展

- [ ] `permission.Matcher` sealed interface 存在,四种 record 实现(ExactMatcher / GlobMatcher / RegexMatcher / NotMatcher)各自可单独编译并运行(验证：`./gradlew test -Dtest=MatchersTest` 通过)
- [ ] `permission.PermissionRule` 已替换 pattern 为 matcher 字段,`parse` 能识别 `=` / `~` / `!` 前缀(验证：`./gradlew test -Dtest=PermissionRuleTest` 通过)
- [ ] `SettingsLoader.toRuleSet` 在 `PermissionRule.parse` 抛 `RuleParseException` 时输出 stderr 错误日志(验证：单测构造非法 rule 串,捕获 `System.err` 输出含 `parse failed`)

### Hook 包

- [ ] `com.mewcode.hook` 包存在且编译通过(验证：`./gradlew -q -DskipTests compile`)
- [ ] 11 个 `Event` 枚举值全部声明且 `isBlocking()` 仅对 PRE_TOOL_USE / USER_PROMPT_SUBMIT 返回 true(验证：`./gradlew test -Dtest=EventTest` 或单测覆盖)
- [ ] `HookLoader` 能解析合法 YAML 并构造 HookEngine(验证：`HookLoaderTest` 全部通过)
- [ ] `HookLoader` 对字段缺失 / 枚举错 / async+拦截事件冲突 / matcher 编译失败均报 stderr 并跳过该条(验证：对应 `HookLoaderTest` 子用例通过)
- [ ] `HookEngine.dispatch` 按声明顺序执行 rule 且拦截后中断后续(验证：`HookEngineTest.testDispatchBlocking` 通过)
- [ ] `HookExecutor` 的 shell exit 2 触发 `blocked=true`、exit 0 放行、其它非 0 视为失败不拦截(验证：`HookExecutorTest.testRunShell*` 通过)
- [ ] `HookExecutor` 的 HTTP 在 body 含 `{"decision":"block","reason":"..."}` 时触发 `blocked=true`(验证：`HookExecutorTest.testRunHttp*` 通过)
- [ ] `HookExecutor` 的 prompt 动作通过 `ExecutionResult.prompt` 字段返回文本(验证：`HookExecutorTest.testRunPrompt` 通过)
- [ ] `HookExecutor` 的 subagent 动作仅 stderr 输出占位日志、不阻塞(验证：`HookExecutorTest.testRunSubagent` 通过)
- [ ] `onlyOnce` 状态在 `SessionRuntime` 上,/clear 与 /resume 时被 `resetForNewSession` 清空(验证：`SessionRuntimeTest.testResetForNewSession` 通过)

### agent / tui 集成

- [ ] `Agent.Builder.hookEngine` 方法存在,agent 内部 `dispatchHook` 在 11 个 emit 点全部调用(验证：`AgentTest.testHookEmit*` 覆盖每个事件)
- [ ] tui `submit()` 在 UserPromptSubmit 拦截时不消费 textBox、显示 errorBlock(验证：`MewCodeModelTest.testSubmitBlocked` 通过)
- [ ] `MewCodeModel.init()` 末尾调 `dispatchSessionStart`(验证：`MewCodeModelTest.testInitDispatchSessionStart` 或集成测试)
- [ ] `/clear` / `/resume` / `/exit` 触发 SessionEnd(验证：`MewCodeModelTest.testClearDispatchSessionEnd` 等)
- [ ] `MewCode` 退出前兜底 SessionEnd(验证：`MewCode` 调用链审查)
- [ ] `/hooks` 命令注册到命令表(验证：`BuiltinCommandsTest` 中 `/hooks` 命令存在 + 输出格式正确)
- [ ] `pendingReminders` 在 `Agent.run` `takeReminders` 后被清空(验证：`SessionRuntimeTest.testTakeReminders` 通过)

## 集成

- [ ] `HookEngine` 与 `permission.Matcher` 共用同一套匹配实现(验证：hook 包不重复实现 exact/regex/glob)
- [ ] `HookEngine` 接入 `Agent.run` 后所有现有 agent 测试不破坏(验证：`./gradlew test -Dtest='com.mewcode.agent.*Test'` 全过)
- [ ] `HookEngine` 接入 tui 后所有现有 tui 测试不破坏(验证：`./gradlew test -Dtest='com.mewcode.tui.*Test'` 全过)
- [ ] PreToolUse 拦截结果当 tool_result 回灌后,LLM 视角看到的是 `isError=true` 的 ToolResult,content 含 `[hook <name>] <reason>`(验证：`AgentTest` 检查 results map 字段)
- [ ] reminder 注入路径与 plan reminder 协同——同一轮 LLM 请求的 reminder 串同时含两类(验证：`AgentTest` 中构造 plan 模式 + hook prompt 注入,断言 reminder 串包含两段)

## 编译与测试

- [ ] 项目编译无错误:`./gradlew shadowJar`
- [ ] 所有单元测试通过:`./gradlew test`
- [ ] 格式化检查通过:`./gradlew compileJava` (代码风格由 IDE 保证)(无配置则跳过)

## 端到端场景(tmux 实跑)

每个场景在 tmux 内启动一个 mewcode 实例完成,验证人工/可视化行为。

### 场景 1:PreToolUse shell 拦截 write_file

**预置:** 在 `.mewcode/hooks.yaml` 写一条 hook:
```yaml
hooks:
  - name: block-write
    event: PreToolUse
    if:
      all_of:
        - field: tool_name
          match: { type: exact, value: write_file }
    action:
      type: shell
      command: "echo blocked by hook >&2; exit 2"
```

**步骤:**
- [ ] tmux 启动 mewcode（`java -jar build/libs/mewcode.jar`）
- [ ] 给 LLM 输入"创建一个文件 hello.txt 内容是 hi"
- [ ] LLM 应触发 write_file,工具被拦截
- [ ] scrollback 内 tool_result 显示 `[hook block-write] blocked by hook`、文件未创建
- [ ] LLM 收到反馈后调整回应,不死循环

### 场景 2:SessionStart prompt 注入

**预置:**
```yaml
hooks:
  - name: zh-cn-default
    event: SessionStart
    action:
      type: prompt
      text: "默认用 zh-CN 回复"
```

**步骤:**
- [ ] tmux 重启 mewcode
- [ ] 立刻发一句英文输入"hi there"
- [ ] LLM 应该用中文回复(因为 reminder 区注入了 zh-CN 指令)

### 场景 3:PostToolUse async shell 后台格式化

**预置:**
```yaml
hooks:
  - name: spotless-after-write
    event: PostToolUse
    if:
      all_of:
        - field: tool_name
          match: { type: exact, value: write_file }
        - field: tool_input.path
          match: { type: glob, value: "**/*.java" }
        - field: is_error
          match: { type: exact, value: "false" }
    action:
      type: shell
      command: "./gradlew -q spotless:apply -DspotlessFiles=\"$(jq -r .tool_input.path)\""
    async: true
    timeout: 30s
```

**步骤:**
- [ ] tmux 启动 mewcode
- [ ] 让 LLM 写一个故意排版不整齐的 Java 文件(如缩进错乱)
- [ ] LLM 完成写入后主对话立即进入下一轮,不停顿
- [ ] 验证文件被 spotless 格式化(可手动 `cat` 该文件)

### 场景 4:UserPromptSubmit 拦截 delete 关键字

**预置:**
```yaml
hooks:
  - name: warn-delete
    event: UserPromptSubmit
    if:
      all_of:
        - field: prompt
          match: { type: regex, value: "(?i)delete" }
    action:
      type: shell
      command: "echo \"用户消息含 delete 关键字\" >&2; exit 2"
```

**步骤:**
- [ ] tmux 启动 mewcode
- [ ] 输入"请帮我 delete 那个文件"
- [ ] 输入被拦截,scrollback 内显示 `[hook warn-delete] 用户消息含 delete 关键字`
- [ ] 输入框内容仍在(被退回用户重新编辑)
- [ ] LLM 端未收到这条 user 消息(不发起请求)

### 场景 5:Stop HTTP 通知

**预置:**
- 本地起 echo server:`python3 -m http.server 9999 --bind 127.0.0.1` 或 nc -l
```yaml
hooks:
  - name: notify-stop
    event: Stop
    action:
      type: http
      url: "http://127.0.0.1:9999/done"
      method: POST
```

**步骤:**
- [ ] tmux 启动 mewcode
- [ ] 让 LLM 简单回答一个问题后停止
- [ ] echo server 收到一次 POST,body 含 `"event":"Stop"`

### 场景 6:only_once + PreUserMessage

**预置:**
```yaml
hooks:
  - name: first-turn
    event: PreUserMessage
    only_once: true
    action:
      type: shell
      command: "echo first-turn-fired >&2"
```

**步骤:**
- [ ] tmux 启动 mewcode
- [ ] 第一轮发任意消息,stderr 出现 `first-turn-fired`
- [ ] 第二轮发消息,stderr 没有再次出现
- [ ] 执行 `/clear` 进新会话,再发消息,stderr 重新出现 `first-turn-fired`

### 场景 7:错误配置不阻断启动

**预置:** hooks.yaml 含一条非法 hook:
```yaml
hooks:
  - name: bad-async
    event: PreToolUse
    async: true
    action:
      type: shell
      command: "echo x"
  - name: good-hook
    event: SessionStart
    action:
      type: shell
      command: "echo ok"
```

**步骤:**
- [ ] tmux 启动 mewcode
- [ ] mewcode 启动期 stderr 打印 `hook "bad-async": async not allowed for blocking events, skipped`
- [ ] mewcode 仍然成功进入 idle 状态
- [ ] `/hooks` 命令仅列出 good-hook、未列 bad-async

### 场景 8:/hooks 命令

**预置:** 一份包含 3 条合法 hook 的 hooks.yaml(任意 event 组合)

**步骤:**
- [ ] tmux 启动 mewcode
- [ ] 输入 `/hooks` 回车
- [ ] 输出按 event 分组,每条一行 `  <name>  <event>  <action.type>  [flags]`
- [ ] 末尾显示 `Loaded from: .../hooks.yaml`

### 场景 9:端到端组合(AC17)

**预置:** hooks.yaml 包含场景 1、2、3、4 全部 hook

**步骤:**
- [ ] tmux 启动 mewcode
- [ ] 首轮:SessionStart 注入 zh-CN(场景 2),Agent 准备就绪
- [ ] 输入"帮我创建 hello.java,然后用 spotless 格式化一下"
- [ ] LLM 调 write_file 创建文件 → 被场景 1 的 hook 拦截 → LLM 重试(可能换 edit_file)或换 bash 调 spotless
- [ ] 整个过程不卡顿、无异常堆栈
- [ ] `/hooks` 命令仍可工作显示 4 条 hook