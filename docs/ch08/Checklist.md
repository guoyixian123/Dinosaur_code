# 上下文管理 Checklist

> 每一项通过运行代码或观察行为来验证,聚焦系统行为。

## 实现完整性

### 包与目录结构

- [ ] **C1**:`src/main/java/com/mewcode/compact/` 目录存在,可被其他包引用。
  - 验证:`ls src/main/java/com/mewcode/compact/` 列出 `ContextCompactor.java` / `// (Layer 1/2 都在 ContextCompactor.java 内)` / `// (Layer 1/2 都在 ContextCompactor.java 内)` / `SummaryPrompt.java` / `Recovery.java` / `Token.java` / `CompactConstants.java` / `CompactException.java` 等核心文件,外加 `state/` 子目录下的 `ContentReplacementState.java` / `AutoCompactTrackingState.java` / `SessionContext.java`,以及 `src/test/java/dev/mewcode/compact/` 下各自的 `*Test.java`。
  - 验证:`./gradlew compileJava` 退出码 0,无未解析符号。

- [ ] **C2**:常量集中在 `CompactConstants.java`,未散落到其他文件。
  - 验证:在 `src/main/java/com/mewcode/compact/` 内执行 `grep -rn "= 50000\|= 200000\|= 20000\|= 13000\|= 3000\|= 10000"` 命中点全部在 `CompactConstants.java`。
  - 验证:`DEFAULT_ANTHROPIC_CONTEXT_WINDOW` 与 `DEFAULT_OPENAI_CONTEXT_WINDOW` 是 `public static final` 常量,定义在 `com.mewcode.config.ProtocolDefaults`,可被 config 包引用。

### 状态对象

- [ ] **C3**:会话状态构造方法全部返回非 null 对象,且自动建立落盘目录。
  - 验证:编写或运行一段最小程序构造 `SessionContext.create(workspace)`,检查返回的 sessionId 形如 `<unix_ts>-<hex>`,且 `spillDir` 物理目录存在。
  - 验证:连续调用两次 `SessionContext.create` 得到不同的 sessionId。

- [ ] **C4**:替换决策账本提供"已见"与"已替换"两本独立簿子。
  - 验证:单元测试用 `decideOnce` 配合 KEPT / REPLACED 决策组合检查:KEPT 后 seenIds 命中、replacements 不命中;REPLACED 后两者都命中且 replacements 返回值稳定。

- [ ] **C5**:熔断器有读、记录失败、记录成功、是否跳闸四个动作。
  - 验证:单元测试调 `recordFailure` 三次后 `tripped()` 返回 true;再调一次 `recordSuccess` 立即返回 false。

- [ ] **C6**:文件追踪状态线程安全,对外只暴露快照拷贝。
  - 验证:`./gradlew -q test -Dtest='*Test'` 中跑 50 个并发 virtual thread `recordFile` + `snapshot` 的用例无 ConcurrentModificationException 或脏读告警。
  - 验证:修改 snapshot 返回的列表会抛 `UnsupportedOperationException`(`List.copyOf` 返回 immutable),不会影响下次调用的结果。

### 两层压缩

- [ ] **C7**:第 1 层提供单条落盘、聚合落盘、幂等、决策冻结四种行为。
  - 验证:对 60K 字符单条结果运行一次 `offloadAndSnip`,再运行第二次,输出消息完全一致(`equals`)。
  - 验证:对 3 条 80K 字符的聚合场景,第一次调用后聚合字节回落到 200K 阈值以下。

- [ ] **C8**:第 1 层落盘失败不阻断主流程。
  - 验证:把 spillDir 改成只读路径(`Files.setPosixFilePermissions`)运行 `offloadAndSnip`,工具结果保持原文,账本中该 id 未被标记为已见。

- [ ] **C9**:预览体包含原始字节数、头部预览、落盘路径、重读提示四项。
  - 验证:抓取预览体字符串,用 `String.contains` 断言四个稳定标志子串:① 包含 `original size:` 子串;② 包含 spillDir 路径片段(`spillDir.resolve(toolUseId)` 的尾段);③ 包含 `head preview` 标记;④ 包含 `文件读取工具` 与 `不要凭头部预览猜测` 两个关键短语。
  - 验证:预览体头部内容长度同时不超过 20 行且不超过 2048 字节(用 `text.split("\n").length` + `getBytes(UTF_8).length` 检查)。
  - 验证:相同入参连续两次构造预览体得到逐字节相等的字符串。

- [ ] **C10**:第 2 层摘要按"分析草稿 + 正式摘要"两阶段输出,正式摘要包含 9 个固定小节。
  - 验证:抓一次摘要请求体的 messages,最后一条 user 内容包含 `<analysis>` 与 `<summary>` 两个标签的说明,以及 9 个小节标题。
  - 验证:解析摘要返回结果,`<summary>` 之外的内容被丢弃。
  - 验证:抓一次完整摘要返回字符串,解析出第 6 部分(用户消息原文),断言会话内每条 user 消息的 content 都能在第 6 部分中作为子串找到(逐条 `String.contains` 检查;覆盖 AC7)。

### 恢复三段

- [ ] **C11**:恢复段拼装三块内容:最近读过的文件、当前可用工具、边界提示消息。
  - 验证:调 `Recovery.buildRecoveryAttachment(snapshot, toolDefs)` 后输出文本中能搜到 `最近读过的文件` / `当前可用工具` / `边界提示` 三个分节标题。
  - 验证:超过 5 条文件记录时仅输出最近 5 条;第 6、第 7 条路径**不**出现(反向断言)。
  - 验证:单文件超过 5000 token 时**保留头部**对应的字符片段,尾部出现 `(content truncated)`(不能截掉头部)。

- [ ] **C12**:边界提示消息文案稳定,不在两次调用之间漂移。
  - 验证:连续两次 `buildRecoveryAttachment` 在相同 snapshot 与 toolDefs 入参下返回的边界提示段逐字节一致(覆盖 C12 / 验收 prompt cache 稳定)。

### Token 估算

- [ ] **C13**:估算方法支持锚点 + 字符增量两种来源;返回类型 long。
  - 验证:单元测试 `anchor=0, allMsgs=[], anchorMsgLen=0` 返回 0;`anchor=5000, allMsgs=[msg]`(msg.content 350 字节)、`anchorMsgLen=0` 返回 `5000 + ceil(350/3.5) = 5100`。
  - 验证:`usageAnchor` 返回 long,把 inputTokens/outputTokens/cacheRead/cacheWrite 四个字段加和。

- [ ] **C13a**:估算 token 远低于自动阈值时 manageContext 不进入 layer2。
  - 验证:构造 `in.estimatedToken = threshold - 1`、`in.contextWindow = 200000`,调一次 manageContext,断言 FakeProvider 的摘要请求计数 == 0(layer2 未触发);同样输入 `in.estimatedToken = threshold + 1` 时摘要请求计数 == 1(layer2 触发)。

### 手动入口与命令分发

- [ ] **C14**:TUI 输入以 `/` 开头时走命令路径,不发给 LLM。
  - 验证:注入 mockAgent,TUI 收到 `/compact` 后 mockAgent 的 stream 调用计数仍为 0、runForceCompact 调用计数为 1。
  - 验证:注入 mockAgent,TUI 收到 `/unknown` 后 stream 调用计数仍为 0,消息列表出现未知命令提示。

- [ ] **C15**:Agent 暴露 `runForceCompact` 给 TUI 调用。
  - 验证:方法签名返回 `ForceCompactResult(long before, long after, Throwable error)` record,TUI 拿到后用于拼系统消息。

### 紧急压缩与哨兵异常

- [ ] **C16**:`com.mewcode.llm.PromptTooLongException` 哨兵异常存在并被 provider 包装。
  - 验证:`grep -rn PromptTooLongException src/main/java/com/mewcode/llm/` 命中。
  - 验证:编写专门的 provider PTL 包装单元测试 `testAnthropicProviderWrapsPromptTooLong` / `testOpenAIProviderWrapsPromptTooLong`:模拟 provider 返回上下文过长的原始错误,断言 `AgentEvent.ErrorEvent.error() instanceof PromptTooLongException` 为 true;对非 PTL 错误(500 等)断言为 false(验证用 `cause` 链)。

### 配置

- [ ] **C17**:`ProviderConfig` 新增 `contextWindow` 字段并能从 YAML 解码。
  - 验证:构造一个 yaml 字符串带 `context_window: 80000` 字段加载后,对应 `providerConfig.contextWindow() == 80000`。

- [ ] **C18**:`effectiveContextWindow()` 在四种场景下返回正确值。
  - 验证:anthropic + 未配置 → 200000;openai + 配置 0 → 128000;anthropic + 配置 80000 → 80000;未知 protocol + 未配置 → 200000(保守默认)。

---

## 集成

### compact 与 conversation

- [ ] **I1**:Conversation 提供 `replaceMessages` 入口,且做深拷贝。
  - 验证:构造 2 条消息调 `replaceMessages` 后修改原列表,`messages()` 输出不被污染。
  - 验证:传 null 不抛 NPE,`messages()` 长度为 0。

- [ ] **I2**:管理上下文成功后,conversation 内存数组被替换为新序列。
  - 验证:让 FakeProvider 触发一次 layer2 摘要后,`conv.messages().size()` 等于 `1(摘要)+ 1(衔接占位,如需要)+ 近期原文条数`。

### compact 与 agent

- [ ] **I3**:Agent 本轮迭代开头按 mode 选出 `defs`,把同一份列表同时传给 // (参数直接传入 manage 方法).toolDefs 与 Stream Request.tools。
  - 验证:用 `Set` 比对工具名集合 ==(即 size 相等 + 每个名字双向包含)。
  - 验证:对每个工具,把恢复段中渲染的 JSON schema 字符串和 Request.tools 中对应工具的 `inputSchema` 字段做 `ObjectMapper.readTree` 后用 `JsonNode.equals` 比较;不允许仅靠工具名匹配。
  - 验证:若 Request.tools 含有 N 个工具,恢复段必须正好渲染 N 个工具行,多一个少一个都算失败。
  - 验证:在 Agent 内对 defs 引用做断言——同一轮迭代内 ManageContext 拿到的 toolDefs 列表与 stream 调用的 Request.tools 列表是同一引用(`==` 引用相等,而不是 `equals`)。
  - 验证:PLAN Mode 切换时 defs 是 `readOnlyDefinitions()`;DEFAULT Mode 时是 `definitions()`;恢复段与 stream 各跑一次都用同一份。

- [ ] **I4**:每轮主对话 stream 完成后用尾事件的 usage 更新锚点(替换,不是累加)。
  - 验证:FakeProvider 在尾部投递一条带 Usage 的 StreamEvent,Agent 内部 `runtime.getUsageAnchor()` 等于 `inputTokens+outputTokens+cacheRead+cacheWrite` 之和(long)。

- [ ] **I4-bis**:锚点连续被替换、不累加。
  - 验证:在 FakeProvider 上脚本化连续 3 次返回不同的 Usage(例如 1000 / 1500 / 2200),断言每次 stream 完成后 `runtime.getUsageAnchor()` 都被替换为最新 Usage 之和(依次 1000、1500、2200),而不是累加(覆盖 AC22)。
  - 验证:摘要请求(layer2 路径)结束后,`runtime.getUsageAnchor()` 不被修改(FakeProvider 让摘要请求也返回 Usage,断言 anchor 仍是主对话路径的最近值)。

- [ ] **I5**:ReadFile 工具成功后 Agent 用纯净字节写入 RecoveryState。
  - 验证:调用 ReadFile 读一个本地文件,断言 `recovery.snapshot()` 包含该文件路径,且记录内容不含行号前缀(与磁盘原文逐字节相等)。

- [ ] **I6**:管理上下文遇到 PTL 时进入紧急压缩并就地重试一次。
  - 验证:FakeProvider 第 1 次 stream 投递 `PromptTooLongException`,紧急压缩后的第 2 次 stream 正常完成 → 整个 run 成功结束。
  - 验证:紧急压缩后的重试再次投递 PTL 时 Agent 上抛错误,不再进入第三次。

### compact 与 tui

- [ ] **I7**:TUI 命令分发表注册四项(迁移现有 `/exit` / `/plan` / `/do` + 新增 `/compact`)。
  - 验证:`grep -n "/compact\|/exit\|/plan\|/do" src/main/java/com/mewcode/tui/Commands.java` 命中四项;`BUILTIN_COMMANDS.size() == 4`。
  - 验证:输入 `/anything-else` 走未知命令路径,提示包含可用命令列表。
  - 验证:迁移后 `/exit` 仍然退出;`/plan` 仍然切 plan 模式;`/do` 仍然切 default 模式并启动一轮 run。

- [ ] **I8**:`/compact` 处理完成后 TUI 输出带 token 数对比的系统消息。
  - 验证:mockAgent 返回 `new ForceCompactResult(120000, 42000, null)`,TUI 输出一条系统消息包含两个数字。
  - 验证:mockAgent 返回 `new ForceCompactResult(500, 300, null)` 也能正常显示系统消息,无任何阈值校验拦截(覆盖 AC13)。
  - 验证:mockAgent 返回 `new ForceCompactResult(0, 0, e)`,TUI 输出 `压缩失败: <err>`,不抛。

- [ ] **I12**:手动 /compact 与 run 串行执行(runLock 互斥)。
  - 验证:构造一个长跑 run(FakeProvider 慢响应),同时启动一个 virtual thread 调 runForceCompact;断言两次操作按顺序串行完成,没有 race,没有并发触发 manageContext。

### compact 与 config

- [ ] **I9**:`com.mewcode.MewCode` 启动时把 `effectiveContextWindow()` 注入到 Agent。
  - 验证:跑 `java -jar build/libs/mewcode.jar` 并配置 anthropic provider 不带 context_window → Agent 字段拿到 200000。
  - 验证:把 `context_window: 100000` 加入配置 → Agent 字段拿到 100000。

- [ ] **I10**:`.mewcode/config.yaml.example` 展示新字段用法与默认值注释。
  - 验证:打开示例文件,看到 `context_window: 200000` 之类的字段和 "可选;未配置时按 protocol 默认" 注释。

### 会话目录

- [ ] **I11**:进程启动后 `.mewcode/sessions/<id>/tool-results/` 物理目录被创建。
  - 验证:启动 mewcode 后 `ls .mewcode/sessions/` 出现新子目录;子目录名形如 `<unix_ts>-<hex>`。
  - 验证:进程退出后该目录依然保留,下次启动会再开一个新的子目录。

### Compact 状态事件路由(兑现 spec F24a / F24b)

- [ ] **I13**:自动压缩触发时 Agent emit `CompactEvent(BEFORE_AUTO)` 与 `AFTER_AUTO` 一对事件;阈值未达不 emit。
  - 验证:单测 `testAgentEmitsAutoCompactEvents`(agent 包)收集 run publisher 所有 AgentEvent,断言 `instanceof CompactEvent` 的事件正好出现 2 次,phase 序列 `[BEFORE_AUTO, AFTER_AUTO]`,且 After 的 `before > after` 与 `error == null`。
  - 验证:单测 `testAgentNoCompactEventBelowThreshold`:估算 token 远低于阈值时跑 25 轮,收集到的 Compact 事件数为 0。
- [ ] **I14**:紧急压缩触发时 Agent emit `BEFORE_EMERGENCY` + `AFTER_EMERGENCY` 一对事件。
  - 验证:单测 `testAgentEmitsEmergencyCompactEvents` 收集事件,断言出现 `[BEFORE_EMERGENCY, AFTER_EMERGENCY]` 这一对(无论后续主对话重试是否成功)。
- [ ] **I15**:TUI `AgentEvent 队列.onNext` 在 `event instanceof CompactEvent` 时优先走渲染分支,文案由 `Commands.formatCompactNotice` 统一格式化;手动 `/compact` 完成态 program.send() 回投也走同一格式化方法。
  - 验证:单测 `testTuiRendersBeforeAutoNotice` / `testTuiRendersBeforeEmergencyNotice` / `testTuiRendersAfterCompactNotice` 通过;用 `String.contains` 断言 scrollback 文本含目标短语,并断言此分支不调 `conv.addUser` / 不调 run。
  - 验证:手动 `/compact` 完成后的系统消息文本与 `Commands.formatCompactNotice(new CompactEvent(AFTER_*, ...))` 字节相同(统一格式化的体现)。

---

## 编译与测试

- [ ] **B1**:`./gradlew shadowJar` 在仓库根目录退出码 0,无未解析符号。

- [ ] **B2**:`./gradlew compileJava` 干净;`./gradlew -q spotbugs:check`(可选)无关键告警。

- [ ] **B3**:`./gradlew -q spotless:check` 输出为空(全部已格式化);（代码风格由 IDE 保证） 用 google-java-format。

- [ ] **B4**:import 分组遵循 google-java-format:JDK / 第三方 / 本地包三段,组间空行隔开。

- [ ] **B5**:`./gradlew -q test -Dtest='com.mewcode.compact.*'` 全部通过。覆盖:
  - 状态对象(决策冻结、并发安全)
  - token 估算(锚点 + 字符增量、usage 合并)
  - 第 1 层(单条 / 聚合 / 幂等 / 决策冻结 / 落盘失败降级)
  - 摘要 prompt(结构断言、`<summary>` 解析三种 case)
  - 恢复段(5 文件上限、5000 token 截断、工具列表逐项匹配)
  - 第 2 层(近期原文边界、tool_use/tool_result 配对修正、PTL 自重试、按比例丢弃)
  - 编排(自动触发阈值、熔断跳过、手动绕过)

- [ ] **B6**:`./gradlew -q test -Dtest='*Concurrent'`(JUnit 5 + virtual thread)通过,无 race。重点用例:50 个 virtual thread 并发往 RecoveryState 写入与 snapshot。

- [ ] **B7**:`./gradlew -q test -Dtest='ConversationTest'` 通过;`replaceMessages` 深拷贝与 null 输入两个用例覆盖。

- [ ] **B8**:`./gradlew -q test -Dtest='ConfigLoaderTest'` 通过;`effectiveContextWindow` 四种 case 覆盖。

- [ ] **B9**:`./gradlew -q test -Dtest='AgentTest'` 通过;新增"紧急压缩成功"与"紧急压缩后再次 PTL 上抛"两个用例。

- [ ] **B10**:`./gradlew -q test -Dtest='MewCodeModelTest'` 通过;`/compact` 走命令路径与 `/unknown` 友好提示两个用例。

- [ ] **B11**:注释不出现"参考"、"取自"、"对齐"、"mirror"、"镜像"、"Go 实现"等外部引用语。
  - 验证:`grep -rn "参考\|取自\|对齐.*实现\|mirror\|镜像\|Go 实现\|Golang 实现\|TS 实现\|TypeScript 实现\|as in\|课程实现\|README" src/main/java/com/mewcode/compact/ src/main/java/com/mewcode/agent/ src/main/java/com/mewcode/conversation/ src/main/java/com/mewcode/llm/ src/main/java/com/mewcode/tui/ src/main/java/com/mewcode/config/` 全部无命中。

- [ ] **BB1**:文档自检——spec / plan / task / checklist 本身也不出现外部引用语。
  - 验证:`grep -rnE --exclude=checklist.md "取自 ch|取自 README|参考课程|参考 Claude|参考 TS|参考 Type|对齐 ch|对齐课程|对齐.*实现|镜像实现|as in " docs/java/ch08/` 无命中。模式只匹配具体短语;`--exclude=checklist.md` 排除自身,避免本条 BB1 与 B11 把正则模式当字符串列出后构成 self-fire。

- [ ] **B12**:`.mewcode/config.yaml.example` 可被解析。
  - 验证:编写一个测试 fixture,SnakeYAML Engine 把 `.mewcode/config.yaml.example` 解析到 `Config` 不抛;`ConfigLoader.validate()` 通过;新增的 `contextWindow` 字段在解码后非零。

---

## 端到端场景

### 场景 E1:长会话不撞墙

- [ ] **触发**:构造一个 FakeProvider 脚本,30 轮迭代每轮返回一个工具调用,工具结果 30KB,配合一个较小的 contextWindow(例如 50000)。
- [ ] **预期**:30 轮完整跑完,无未捕获异常;中途至少触发一次自动 layer2 摘要;最终 `conv.messages().size()` 远小于 30。
- [ ] **观察方式**:在 Agent 主循环内打日志或在测试里数 layer2 触发次数;测试用例 assert `run` 方法正常返回。

### 场景 E2:单条大工具结果

- [ ] **触发**:FakeProvider 一轮返回一个工具调用,工具回填 80KB(80000 字节)字符串。
- [ ] **预期**:下一轮 stream 请求 messages 中该工具结果 content 已被替换为预览体,通过 4 条 `String.contains` 断言:① 包含 `original size:` 子串与字节数("80000");② 包含 `[saved to]` 与 spillDir 尾段路径片段;③ 包含 `head preview` 标记;④ 包含 `文件读取工具` 与 `不要凭头部预览猜测` 两个关键短语。`.mewcode/sessions/<id>/tool-results/<tool_use_id>` 文件存在且 `Files.size(...) == 80000` 字节。
- [ ] **观察方式**:用 FakeProvider 捕获第 N+1 次 stream 请求体,检查 content 字段;用 `Files.size` 检查落盘文件大小。

### 场景 E3:单轮聚合超标

- [ ] **触发**:一条 RoleTool 消息内的 toolResults 列表含 3 条工具结果,每条 80KB(合计 240KB)。
- [ ] **预期**:至少 2 条被替换、落盘,未被替换的工具结果保持原文;下一轮请求中该 RoleTool 消息内剩余 toolResults 的 content 字节聚合 ≤ 200000 字节。
- [ ] **观察方式**:捕获 stream 请求体,sum 该消息内所有 toolResults.content 长度;检查 spillDir 至少出现 2 个文件。

### 场景 E4:决策冻结

- [ ] **触发**:同一个 toolUseId 在第 N 轮被决定不替换;继续跑到第 N+5 轮,期间内容未变。
- [ ] **预期**:第 N+1 ~ N+5 轮的请求体中该工具结果始终保持原文,无任何替换发生。
- [ ] **触发**:另一个 toolUseId 在第 M 轮被决定替换。
- [ ] **预期**:第 M+1 ~ M+5 轮的请求体中该工具结果使用与第 M 轮逐字节相同的预览体(diff 比对应为空)。
- [ ] **观察方式**:捕获多轮 stream 请求体,对同一 toolUseId 在不同轮次的 content 字符串做 `equals` 比较。

### 场景 E5:手动 /compact

- [ ] **触发**:在 TUI 启动后输入 `/compact`,压缩前估算 token = 1000(远低于自动阈值 167000)。
- [ ] **预期**:① FakeProvider 收到一次摘要请求(Request.tools 为空 List)——证明手动路径无视阈值;② 收到结果后 conversation 被替换为"摘要 + 恢复段 + 近期原文"(首条是合并了摘要与三段恢复的单条 user 消息,第 6 部分包含本次会话所有 user 消息原文,按出现顺序逐条可定位);③ TUI 输出系统消息 `已压缩,token 从 X 降至 Y`,X、Y 都是非负整数;断言 X = 入口 estimatedToken(= 1000),Y = 替换后估算(`estimateTokens(0, newMsgs, 0)`);④ stream 普通对话路径(主对话 run)未被调用。
- [ ] **观察方式**:mockAgent 计数 runForceCompact / run 调用次数;FakeProvider 捕获摘要请求体;TUI 输出断言。

### 场景 E6:紧急压缩

- [ ] **触发**:FakeProvider 在第 K 次 stream 投递 `new AgentEvent.ErrorEvent(new PromptTooLongException(...))`(wrapped 满足 `instanceof PromptTooLongException`)。
- [ ] **预期**:① Agent 先强制跑一次 offloadAndSnip 把大工具结果挪走(断言 spillDir 多了文件);② 再调用一次摘要请求(紧急路径);③ conversation 被替换;④ `runtime.usageAnchor` 与 `anchorMsgLen` 被清零;用新消息列表重新估算 token;若估算 < contextWindow - MANUAL_SAFETY_MARGIN,**重试一次**第 K 次请求;⑤ 重试成功则整体流程继续;⑥ 重试再次投递 PTL 时上抛错误,不进入第三次。
- [ ] **观察方式**:FakeProvider 脚本化三组场景:① 摘要 + 重试成功;② 摘要 + 重试再次 PTL;③ 摘要 + 重新估算后**仍** ≥ contextWindow - MANUAL_SAFETY_MARGIN(Agent 不发起第二次 stream 请求,直接上抛错误)。三个测试用例分别 assert。

### 场景 E7:熔断

- [ ] **触发 A(连续失败跳闸)**:让 FakeProvider 对摘要请求连续 3 次投递 error(非 PTL 即可,例如 IOException)。
- [ ] **预期 A**:① 第 3 次失败后熔断器跳闸;② 第 4 次估算 token 跨越自动阈值时,manageContext 不再触发 layer2(用 FakeProvider.summarizeCalls 计数断言:第 4 次进入 manageContext 后计数不增加);③ 手动输入 `/compact` 时仍能正常执行 layer2,不被熔断器拦截。
- [ ] **触发 B(PTL 用光也计入熔断)**:让 FakeProvider 对摘要请求持续投递 PTL 直到 groups 全部丢光。
- [ ] **预期 B**:自动路径下该轮算一次失败,`autoTracking.consecutiveFailures += 1`;连续 3 次后跳闸。
- [ ] **触发 C(成功清零)**:FakeProvider 摘要响应序列为 `[err, err, ok, err, err, err]`。
- [ ] **预期 C**:6 轮后熔断器才跳闸(而不是 5 轮),证明第 3 个 ok 把计数清零了。观察方式:在每次 manageContext 后读 `autoTracking.consecutiveFailures`,断言序列为 [1, 2, 0, 1, 2, 3]。
- [ ] **观察方式**:mockAgent / FakeProvider 内查询 `autoTracking.tripped()` 状态与 consecutiveFailures;通过 FakeProvider.summarizeCalls 计数断言 layer2 是否真的被发起。

### 场景 E8:压缩后恢复

- [ ] **触发**:① 在压缩前先后读过 7 个不同文件;② 触发一次摘要。
- [ ] **预期**:压缩后下一轮 stream 请求 messages 中首条 user 消息的 content 同时包含:
  - 摘要 9 部分(标题字面匹配),且第 6 部分包含本次会话所有 user 消息原文(逐条 `String.contains` 命中)。
  - 最近读过的文件块:仅展示最近 5 个,按时间戳倒序;断言恢复段文本中**不**出现第 6、第 7 个文件的路径子串(反向断言);并断言出现的 5 个路径在文本中的位置顺序与时间戳倒序一致(每两个相邻路径用 `String.indexOf` 取位置,前者位置必小于后者)。
  - 当前可用工具块:每个工具一行;用 `Set` 比对工具名集合 == Request.tools 的工具名集合;对每个工具做 `ObjectMapper.readTree(schema)` 后 `JsonNode.equals` 比较 inputSchema 内容;工具数量正好等于 Request.tools 长度(多一个少一个都失败)。
  - 边界提示消息块:固定文案,明确告诉模型需要原文请重读。
- [ ] **观察方式**:捕获摘要后第 1 次 stream 请求 messages;按文本片段断言三段标题;用 `Set` 比对工具名集合;用反向 `String.contains` 断言被丢弃的文件路径不出现。

### 场景 E9:多 provider contextWindow

- [ ] **触发 1**:anthropic provider 不配置 context_window。
- [ ] **预期 1**:Agent 拿到的 contextWindow = 200000;自动阈值 = 200000 - 20000 - 13000 = 167000。
- [ ] **触发 2**:openai provider 不配置 context_window。
- [ ] **预期 2**:Agent 拿到的 contextWindow = 128000;自动阈值 = 128000 - 20000 - 13000 = 95000。
- [ ] **触发 3**:anthropic provider 配置 context_window=100000。
- [ ] **预期 3**:Agent 拿到的 contextWindow = 100000;自动阈值 = 67000;手动/紧急阈值 = 100000 - 20000 - 3000 = 77000。
- [ ] **观察方式**:在三种配置下分别跑一次 run,构造刚好跨越阈值的估算 token,看是否触发 layer2。

### 场景 E10:不切断 tool_use / tool_result

- [ ] **触发**:构造一段对话尾部形如 `[..., user, assistant{toolCalls=[A]}, tool{result of A}, assistant{toolCalls=[B]}, tool{result of B}]`,让 pickRecentTail 按"两个下界都满足"算出的截断点正好落在 `tool{result of A}` 单条上。
- [ ] **预期**(并列断言):
  ① 返回列表第一条 role 必为 `USER` 或 `ASSISTANT`,不可为 `TOOL`;
  ② 若第一条为 assistant 且有 toolCalls,则列表中必须包含对应的 tool 消息(即 tool_use / tool_result 配对完整);
  ③ 列表满足 `size >= 5` 且 `messageChars(列表)/3.5 >= 10000`(两个下界都满足);
  ④ 列表长度不大于原 msgs 长度(即不会把不存在的消息算进去)。
- [ ] **观察方式**:单元测试构造对话后调 pickRecentTail,按上述 4 条断言。

### 场景 E11:摘要请求自身 PTL

- [ ] **触发 A**:FakeProvider 对前 3 次摘要请求投递 `PromptTooLongException`,第 4 次返回正常摘要。
- [ ] **预期 A**:① 前 3 次每次丢最旧的一组"用户提交 + 一组 assistant/tool 往返"后重试;② 第 4 次成功;③ 整个 runSummary 返回成功;④ 失败计数清零。
- [ ] **观察方式 A**:初始 groups 数为 G。FakeProvider 记录每次摘要请求里的 groups 数(按 user role 切分),断言序列为 [G, G-1, G-2, G-3],第 4 次(G-3)返回成功。
- [ ] **触发 B(超过 3 次后按比例丢)**:FakeProvider 对前 4 次摘要请求都投递 PTL。
- [ ] **预期 B**:第 4 次重试切到按比例丢,`drop = ceil(剩余 * 0.2)` 且 `drop >= 1`;连续记录每次的 groups 数序列满足该递推。
- [ ] **触发 C(丢光仍失败)**:FakeProvider 持续投递 PTL 直到消息组全部丢光。
- [ ] **预期 C**:抛 `CompactException`;自动路径下 autoCompact 抛异常且熔断计数 +1;同等条件下 forceCompact(手动/紧急)抛异常但熔断计数不变。系统**不**发送 messages 为空的摘要请求。

### 场景 E12:tmux 真实运行

- [ ] **触发**:`./gradlew shadowJar`,在 tmux 中启动 `java -jar build/libs/mewcode.jar`,配置 anthropic provider。
- [ ] **预期**:
  - 让 Agent 读一个 80KB 的本地文件 → `.mewcode/sessions/<id>/tool-results/` 下出现该工具调用 ID 的文件;
  - 把 context_window 临时改成 80000(**不能低于 33000**,否则 80000 - 33000 = 47000 是负数会让自动压缩在每轮都触发,无法验证真实压缩信号),连续几轮对话后看到自动压缩日志;
  - 任意时刻输入 `/compact` 看到系统消息 `已压缩,token 从 X 降至 Y`;
  - 输入 `/unknown` 看到友好提示,未发 LLM;
  - 输入 `/exit` / `/plan` / `/do` 行为与本章迁移前一致;
  - 进程退出后 `.mewcode/sessions/<id>/` 仍存在,下次启动再开新子目录。
- [ ] **观察方式**:tmux 中目测;用 `ls .mewcode/sessions/` 与 `cat` 抽查落盘文件;`git status` 干净(覆盖 .gitignore)。

### 场景 E13:自动压缩 UX 状态提示(兑现 spec F24a)

- [ ] **触发**:构造 FakeProvider 脚本让某轮主对话开始前估算 token 跨越 `contextWindow - 20000 - 13000` 阈值;FakeProvider 摘要请求阻塞 200ms 后返回成功,模拟真实 LLM 摘要耗时。
- [ ] **预期**:
  - 摘要请求开始前(即 FakeProvider 的摘要 stream 还没投递任何 chunk),TUI scrollback 已经打印 `正在压缩上下文...`(`String.contains` 命中)。
  - 摘要请求完成后,TUI 接着打印 `已压缩,token 从 <before> 降至 <after>`,其中 before 和 after 都是非负整数;用正则 `^已压缩,token 从 \d+ 降至 \d+$` 匹配;before > after。
  - 收集到的 AgentEvent 序列里在主对话 streamOnce 启动之前出现 `CompactEvent(BEFORE_AUTO)`;manageContext 返回后出现 `CompactEvent(AFTER_AUTO)`;两个事件之间不出现 Text / Tool 事件(说明 TUI 显示状态时 LLM 主对话还未开始)。
- [ ] **观察方式**:测试驱动 `Agent.run`,收集 events 列表并断言 phase 顺序;mock TUI(或集成测试启动 JLine/tui.tea `TestTerminalFactory` 注入虚拟 screen)收集 noticeBlock 文本逐条断言。

### 场景 E14:紧急压缩 UX 状态提示(兑现 spec F24b)

- [ ] **触发**:FakeProvider 在第 K 次主对话 stream 投递 `new AgentEvent.ErrorEvent(new PromptTooLongException(...))`;之后再为 Agent 准备一次摘要响应 + 一次重试主对话响应。
- [ ] **预期**:
  - PTL 发生后、紧急 manageContext 启动前,TUI scrollback 出现 `上下文撞墙,自动压缩中...`(`String.contains` 命中)。
  - 紧急压缩成功后 TUI 接着出现 `已压缩,token 从 X 降至 Y`;之后主对话 streamOnce 重试一次成功,TUI 继续渲染重试后 LLM 的 Text / Tool 事件。
  - 收集到的 AgentEvent 序列:`[CompactEvent(BEFORE_EMERGENCY), CompactEvent(AFTER_EMERGENCY, error=null), Text/Tool... (重试结果)]`。
- [ ] **触发(失败分支)**:FakeProvider 在紧急压缩内部让摘要请求 PTL 全部丢光后仍失败(不可恢复),或重试主对话再次返回 PTL。
- [ ] **预期(失败分支)**:
  - TUI 显示 `压缩失败:<err>` 系统消息(`String.contains` `压缩失败` 命中);AgentEvent 序列里 AFTER_EMERGENCY 的 `error != null`。
  - 不会发起第三次 stream 请求(`FakeProvider.streamCalls <= 2`)。
- [ ] **观察方式**:同 E13;FakeProvider 维护 streamCalls / summarizeCalls 计数器并在测试结尾断言。
</content>