# 上下文管理 Plan

## 架构概览

ch08 引入一个新的本地包 `com.dinocode.compact`,作为上下文管理的唯一权威入口。包内承担三块职责:

1. **第 1 层预防性压缩**:在每一轮 LLM 请求发出之前,对 `ConversationManager` 中的工具结果做幂等的"超阈值落盘 + 字符串替换",并把替换决策冻结在一个会话级账本里,保证 prompt cache 前缀逐字节稳定。
2. **第 2 层 LLM 摘要 + 恢复**:在估算 token 触达阈值(或被手动 / 紧急触发)时,调用 provider 跑一次结构化摘要请求,生成 9 部分摘要 + 三段恢复 + 近期原文,构造一个新的 `List<Message>` 替换掉旧的对话历史。
3. **辅助子模块**:token 估算(锚定真实 usage + 字符增量)、最近读过文件的并发安全追踪、会话目录管理、PTL 自重试与熔断器。

`com.mewcode.compact` 不直接持有 `Agent`,也不直接管理 `Provider`。它通过一组窄接口与外部模块交互:

| 外部模块 | 交互方向 | 形式 |
|----------|----------|------|
| `com.mewcode.agent` | Agent 调 compact | 主循环每轮请求前调 `manageContext`;ReadFile 成功后调 `RecoveryState.recordFile`;捕获 `PromptTooLongException` 后调 `forceCompact` 重试一次 |
| `com.mewcode.conversation` | compact 改 conversation | compact 拿到 `List<Message>` 后做字符串替换 / 摘要重建,再用一个新方法 `replaceMessages` 整体替换内存数组 |
| `com.mewcode.llm` | compact 调 provider | 摘要请求复用同一份 `Provider.stream`,但 `Request.tools` 留空;从 `StreamEvent` 尾部拿 usage 锚定 token 估算 |
| `com.mewcode.tui` | TUI 调 compact | TUI 拿到以 `/` 开头的输入走命令分发;`/compact` 命令调 compact 的 `forceCompact` 并展示 token 变化系统消息 |
| `com.mewcode.config` | config 喂 compact | `ProviderConfig` 新增 `contextWindow` 字段,未配置时按协议给默认值;compact 通过参数拿到当前 provider 的 contextWindow |

**Agent 生命周期与状态归属调整**:现状的 TUI 在 `beginTurn` 里每轮 `Agent.builder().build().run(...)` 重新构造一次 Agent,意味着把 compact 的长生命周期状态(替换决策账本、文件追踪、自动摘要熔断计数、usageAnchor、本轮工具列表缓存)放成 Agent 字段会被每轮重置——决策冻结与熔断器立刻失效。

本章引入 `SessionRuntime` 作为 TUI Model 跨 run 持有的长生命周期状态容器:

```java
// com.mewcode.agent.SessionRuntime(新建)
public final class SessionRuntime {
    public final ContentReplacementState replacement;
    public final RecoveryState recovery;
    public final AutoCompactTrackingState autoTracking;
    public final SessionContext session;
    public volatile int contextWindow;
    public final ReentrantLock anchorLock = new ReentrantLock();
    private long usageAnchor;     // 上一次主对话路径 Stream 真实 usage 之和;摘要请求不更新此字段
    private int  anchorMsgLen;    // anchor 当时 conversation.size(),下次估算只算这之后的字符增量

    public SessionRuntime(...) { ... }

    public long getUsageAnchor();
    public int  getAnchorMsgLen();
    public void updateAnchor(long anchor, int msgLen);
}
```

`Agent` 构造期接受 `SessionRuntime` 注入;TUI Model 持有同一份 `SessionRuntime` 跨轮复用。状态所有权关系:TUI Model 拥有 SessionRuntime;每轮把 SessionRuntime 与 Conversation 一并交给 Agent。compact 是逻辑层,对状态零持有、可重入。

**依赖方向无环**:
- `compact` 不依赖 `agent` / `config` / `tui`。
- `config` 仅在 `effectiveContextWindow()` 中读自身常量(`DefaultAnthropicContextWindow` / `DefaultOpenAIContextWindow` 定义在 `com.mewcode.config.ProtocolDefaults`,不放 compact 包)。
- `agent` 依赖 `compact` + `conversation` + `llm` + `tool` + `permission`,**不**依赖 `config`。
- `MewCode` 是唯一同时引用 `config` 与 `agent` 的位置,负责把 `providerCfg.effectiveContextWindow()` 注入 SessionRuntime。
- `tui` 持有 `SessionRuntime` 与 `Agent`(或在每轮构造 Agent 时把 runtime 传入)。

## 核心数据结构

```java
// com.mewcode.compact.state — 决策账本、熔断器、文件追踪、会话上下文

// ContentReplacementState 是会话级的"工具结果替换决策账本"。
// seenIds 记录已经决策过的 toolUseId,无论决策是替换还是保留原文。
// replacements 只保存"决定替换"那一支的预览字符串,键是 toolUseId。
// 同一个 toolUseId 一旦进入 seenIds 就再也不会被重新评估,保证 prompt cache 稳定。
//
// 并发安全约束:offloadAndSnip 在执行期间持有 lock 全程加锁(读账本 → 决策 → 落盘 →
// 写账本必须在同一临界区内原子完成),避免出现"已 Seen 但 replacement 未写"的中间态。
// 对外只暴露一个高层方法 decideOnce 让调用方传入决策回调,由本类型内部统一加锁。
// 一旦预览字符串写入 replacements[id],本会话内不允许修改(包括其中内嵌的 originalBytes
// 字段)。offloadAndSnip 永远不重新调用 buildPreview,已 Seen 的 id 直接复用现存字符串。
public final class ContentReplacementState {
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> seenIds = new HashSet<>();
    private final Map<String, String> replacements = new HashMap<>();

    public enum Decision { KEPT, REPLACED, SKIP }

    public record DecisionResult(Decision decision, String preview) {}

    // decideOnce 一次性完成"查账本 → 决策 → 写账本"。
    // decide 回调在持锁状态下被调用,返回 (Decision, 预览字符串)。
    // 若 id 已 Seen:直接返回现存 replacement(若是 KEPT 则返回原 content)。
    // 若 decide 返回 KEPT:写 seenIds,不写 replacements;返回原 content。
    // 若 decide 返回 REPLACED:写 seenIds + replacements;返回 preview。
    // 若 decide 返回 SKIP:既不写 seenIds 也不写 replacements;返回原 content(下一轮重试)。
    public String decideOnce(String id, String originalContent,
                             Supplier<DecisionResult> decide);
}

// AutoCompactTrackingState 跟踪自动摘要连续失败次数,用于熔断。
// 手动 / 紧急压缩路径不读这个字段。
public final class AutoCompactTrackingState {
    private final ReentrantLock lock = new ReentrantLock();
    private int consecutiveFailures = 0;

    public void recordSuccess();
    public void recordFailure();
    public boolean tripped();
}

// RecoveryState 是 Agent 主循环写、compact 摘要时读的文件追踪状态。
// files 的键是文件绝对路径,避免相对路径在不同 cwd 下错乱。
public final class RecoveryState {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, FileReadRecord> files = new HashMap<>();

    public void recordFile(String path, String content);
    public List<FileReadRecord> snapshot(); // 已按时间戳倒序
}

public record FileReadRecord(
        String path,
        String content,         // 不带行号前缀的纯净字节
        Instant timestamp       // 最后一次成功读取的时间
) {}

// SessionContext 是会话生命周期信息。sessionId 进程启动时一次性生成。
// spillDir 是落盘目录,固定指向 .mewcode/sessions/<session_id>/tool-results/。
public record SessionContext(String sessionId, Path spillDir) {
    public static SessionContext create(Path workspace) throws IOException { ... }
}
```

```java
// com.mewcode.compact.CompactConstants — 全部硬编码常量

public final class CompactConstants {
    public static final int    SINGLE_RESULT_LIMIT                    = 50000;   // 单条工具结果落盘阈值(字节)
    public static final int    MESSAGE_AGGREGATE_LIMIT                = 200000;  // 单条 RoleTool 消息内工具结果聚合阈值(字节)
    public static final int    SUMMARY_RESERVE                        = 20000;   // 给摘要 LLM 输出预留的 token 空间
    public static final int    AUTO_SAFETY_MARGIN                     = 13000;   // 自动触发的额外安全余量:防估算误差与单轮波动
    public static final int    MANUAL_SAFETY_MARGIN                   = 3000;    // 手动触发的安全余量:只用来判断摘要请求本身能不能塞下
    public static final int    RECOVERY_FILE_LIMIT                    = 5;       // 恢复段最多展示几个文件
    public static final int    RECOVERY_TOKENS_PER_FILE               = 5000;    // 单个文件快照的 token 上限,超出时保留头部、截掉尾部
    public static final int    RECENT_KEEP_TOKENS                     = 10000;   // 摘要后保留近期原文的 token 下界
    public static final int    RECENT_KEEP_MESSAGES                   = 5;       // 摘要后保留近期原文的条数下界
    public static final int    MAX_CONSECUTIVE_AUTO_COMPACT_FAILURES  = 3;       // 熔断阈值
    public static final int    PTL_RETRY_LIMIT                        = 3;       // 摘要请求自身 PTL 的"直接重试"次数
    public static final double PTL_DROP_PERCENTAGE                    = 0.2;     // 3 次后每次再丢的比例
    public static final double ESTIMATE_CHARS_PER_TOKEN               = 3.5;     // 增量估算的字符/token 比
    public static final int    PREVIEW_HEAD_BYTES                     = 2048;    // 预览体头部字节数上限
    public static final int    PREVIEW_HEAD_LINES                     = 20;      // 预览体头部行数上限

    private CompactConstants() {}
}
```

```java
// com.mewcode.config.ProviderConfig 改动(仅追加字段,不动现有字段顺序)

public record ProviderConfig(
        String name,
        String protocol,
        String baseUrl,
        String apiKey,
        String model,
        boolean thinking,        // 仅 anthropic 生效
        int contextWindow        // 新增字段,单位 token,0 表示走协议默认
) {

    // 派生方法,给 compact / Main 用
    public int effectiveContextWindow() {
        if (contextWindow > 0) return contextWindow;
        return switch (protocol) {
            case "anthropic" -> ProtocolDefaults.DEFAULT_ANTHROPIC_CONTEXT_WINDOW;
            case "openai"    -> ProtocolDefaults.DEFAULT_OPENAI_CONTEXT_WINDOW;
            default          -> ProtocolDefaults.DEFAULT_ANTHROPIC_CONTEXT_WINDOW;
        };
    }
}

// com.mewcode.config.ProtocolDefaults(新文件)
public final class ProtocolDefaults {
    public static final int DEFAULT_ANTHROPIC_CONTEXT_WINDOW = 200000;
    public static final int DEFAULT_OPENAI_CONTEXT_WINDOW    = 128000;

    private ProtocolDefaults() {}
}
```

> **依赖方向说明**:协议默认值常量定义在 `com.mewcode.config.ProtocolDefaults`,由 `config` 自身使用;`compact` 包不持有协议默认值常量。`config` 与 `compact` 单向无环。

## 模块设计

### compact 包

#### `ContextCompactor.java` — manageContext 主入口

```java
public final class ContextCompactor {

    public record // (参数直接传入 manage 方法)(
            ConversationManager conv,
            LlmClient client,
            String model,
            int contextWindow,
            List<Map<String, Object>/*tool schema*/> toolDefs,  // 主循环本轮迭代开头按当前 mode 选好的工具定义列表,恢复段与 Stream 共用此列表(== 引用)
            ContentReplacementState replacement,
            RecoveryState recovery,
            AutoCompactTrackingState autoTracking,
            SessionContext session,
            long usageAnchor,                // 上一次主对话路径 Stream 真实 usage 之和
            int  anchorMsgLen,               // anchor 当时 conversation.size(),用于"只算锚点之后的字符增量"
            long estimatedToken,             // 调用方算好的本轮估算 token (= anchor + chars/3.5)
            TriggerKind trigger              // AUTO / MANUAL / EMERGENCY
    ) {}

    public record String/*compactMsg*/(long beforeTokens, long afterTokens) {}

    public enum TriggerKind { AUTO, MANUAL, EMERGENCY }

    // manageContext 是 Agent 每轮请求前必调的唯一入口。
    // 步骤:
    //   1. 若 trigger == MANUAL:跳过第 1 层、阈值、熔断;直接 forceCompact。
    //      若 trigger == EMERGENCY:先强制跑一次 offloadAndSnip(layer1),
    //      再无条件 forceCompact——避免摘要请求本身因为大工具结果直接撞 PTL。
    //   2. 否则(AUTO 路径):
    //      a. 先执行第 1 层 offloadAndSnip 得到 updatedMsgs;
    //      b. 用 Token.estimateTokens(in.usageAnchor, updatedMsgs, in.anchorMsgLen) 重算估算 token
    //         (**必须用 layer1 之后的 updatedMsgs**,否则估算会偏高、过早触发 layer2);
    //      c. 若估算 < (contextWindow - SUMMARY_RESERVE - AUTO_SAFETY_MARGIN) 或 autoTracking.tripped():
    //         直接返回,仅 layer1 生效;
    //      d. 否则 autoCompact,成功后 replaceMessages。
    //
    // beforeTokens / afterTokens 口径:
    //   - beforeTokens = manageContext 入口处的 in.estimatedToken(已包含调用方算好的 anchor + 增量);
    //   - afterTokens = layer2 替换 conversation 后用 estimateTokens(0, newMsgs, 0) 重新算的值;
    //     若只跑了 layer1,afterTokens = estimateTokens(in.usageAnchor, layer1Out, in.anchorMsgLen)。
    public static String/*compactMsg*/ manage(// (参数直接传入 manage 方法) in) throws CompactException;
}
```

职责:编排两层调用顺序、决定走自动 / 手动 / 紧急路径、把替换/摘要后的消息写回 `ConversationManager`、更新熔断器计数。

依赖:`Layer1.offloadAndSnip`、`Layer2.autoCompact`、`Layer2.forceCompact`、`Token.estimateTokens`。

#### `// (Layer 1/2 都在 ContextCompactor.java 内)` — 单结果与聚合落盘 + 决策冻结

```java
public final // Layer1 逻辑在 ContextCompactor 内部 {

    // offloadAndSnip 遍历 conv.messages(),针对每一条 RoleTool 消息上的 toolResults
    // 列表做处理(mewcode 在 Conversation.addToolResults 把一轮工具结果挂在一条 RoleTool
    // 消息上,工具结果不在 assistant 消息里)。规则:
    //   1. 已经在 replacement.seenIds 中的工具结果,通过 decideOnce 拿到现存决策结果
    //      (KEPT → 返回原文;REPLACED → 复用 replacements[id],**不重新构造** preview);
    //   2. 未决策的项进入候选列表,按字节倒序处理:
    //      a. 单条 > SINGLE_RESULT_LIMIT:spillSingle 成功 → 改写 content → REPLACED,
    //         同时把该项从聚合预算里扣除;
    //      b. 然后看剩余项的聚合字节是否 > MESSAGE_AGGREGATE_LIMIT;继续按倒序逐项落盘,
    //         直至剩余聚合 ≤ MESSAGE_AGGREGATE_LIMIT;
    //      c. 未落盘的项 KEPT。
    //   3. 落盘失败时降级为不替换、不写账本(decideOnce 通过 SKIP 信号实现),下次重试。
    //   4. 落盘 → 改写 content → 写账本 三个动作通过 decideOnce 在持锁状态下顺序执行,
    //      任一步失败回退到 KEPT;保证 content 与账本永远一致。
    // 返回新的 List<Message>,纯函数风格,不修改入参。
    public static List<Message> offloadAndSnip(
            List<Message> msgs,
            ContentReplacementState state,
            SessionContext session);

    // spillSingle 把单条 toolResult 内容写入 spillDir/<toolUseId>。
    // 幂等:文件已存在则不重写、不报错。
    static void spillSingle(SessionContext session, String toolUseId, String content) throws IOException;

    // buildPreview 构造替换体字符串,包含原始字节数、头部预览、落盘路径、重读提示。
    // 头部预览策略:取 min(前 PREVIEW_HEAD_LINES 行, 前 PREVIEW_HEAD_BYTES 字节)。
    // 调用时机:只在 offloadAndSnip 内首次决策为替换的瞬间调用一次;之后所有轮次都必须
    // 通过 state.decideOnce 复用 replacements[id] 里存好的字符串,不允许重新调用。
    static String buildPreview(int originalBytes, String head, Path spillPath);
}
```

职责:单条 / 聚合判断、落盘 I/O、预览体格式化、账本写入。

依赖:`SessionContext`、`ContentReplacementState`。

#### `// (Layer 1/2 都在 ContextCompactor.java 内)` — 摘要、PTL 重试、熔断

```java
public final // Layer2 逻辑在 ContextCompactor 内部 {

    public record CompactResult(List<Message> newMsgs, long beforeTok, long afterTok) {}

    // autoCompact 在熔断器未触发时执行,整轮(含 PTL 自重试)失败累加 consecutiveFailures,成功清零。
    // beforeTok = in.estimatedToken;afterTok = estimateTokens(0, newMsgs, 0)。
    public static CompactResult autoCompact(ContextCompactor.// (参数直接传入 manage 方法) in) throws CompactException;

    // forceCompact 手动 / 紧急路径专用:跳过熔断器。
    // beforeTok / afterTok 口径同 autoCompact。失败也不计入熔断。
    public static CompactResult forceCompact(ContextCompactor.// (参数直接传入 manage 方法) in) throws CompactException;

    // runSummary 是两条路径的共同核心:构造摘要 prompt、发请求、解析 <summary>、
    // 拼接恢复段、追加近期原文边界裁剪。
    // 调用入口必须先拍一次 recoverySnapshot = in.recovery().snapshot(),整个 runSummary
    // 生命周期内只使用这一份快照,避免恢复段渲染期间 recordFile 写入造成"声明的工具/文件"
    // 与"Stream 调用时刻状态"漂移。
    static List<Message> runSummary(ContextCompactor.// (参数直接传入 manage 方法) in) throws CompactException;

    // summarizeOnce 发一次摘要请求。
    // 实现要点:订阅 provider.stream(req) 返回的 BlockingQueue;text 累加;Usage 捕获;
    // onError 非 nil 时立即终止;PTL 由调用方通过 errors-style 比较 cause instanceof
    // PromptTooLongException 识别并切到 ptlRetry。
    // **重要**:摘要请求结束后不更新 SessionRuntime.usageAnchor;usageAnchor 只由主对话路径维护。
    static String summarizeOnce(ContextCompactor.// (参数直接传入 manage 方法) in, List<Message> msgs)
            throws PromptTooLongException, IOException;

    // ptlRetry 实现 F27 的丢消息组策略:
    //   - 前 PTL_RETRY_LIMIT 次:每次丢最旧的若干"用户提交 + 一组 assistant/tool 往返"分组;
    //   - 之后:每次按当前剩余消息组数 × PTL_DROP_PERCENTAGE 丢;
    //   - 直到摘要请求能塞下,或全部丢光返回错误。
    static String ptlRetry(ContextCompactor.// (参数直接传入 manage 方法) in, List<Message> msgs, Throwable firstErr)
            throws CompactException, IOException;

    // pickRecentTail 从 msgs 尾部累加,满足以下条件后停止:
    //   - 累计估算 token ≥ RECENT_KEEP_TOKENS;且
    //   - 累计消息数 ≥ RECENT_KEEP_MESSAGES;
    //   - 二者择宽(同时满足两个下界,覆盖范围更大)。
    // 之后再做"tool_use/tool_result 配对修正":若截断点夹在配对中间,向前推到 tool_use 之前。
    static List<Message> pickRecentTail(List<Message> msgs);

    // groupByUserTurn 按 F27 的"用户提交 → 一组 assistant/tool 往返"分组,给 ptlRetry 用。
    static List<List<Message>> groupByUserTurn(List<Message> msgs);
}
```

职责:摘要 LLM 请求构造、PTL 自重试、熔断计数维护、近期原文边界推算。

依赖:`Provider`、`SummaryPrompt`、`Recovery`、`Token`、`AutoCompactTrackingState`。

#### `SummaryPrompt.java` — 摘要 Prompt 模板

```java
public final class SummaryPrompt {

    // buildSummaryPrompt 把对话 msgs 嵌入到固定模板里。
    // 返回长度为 1 的列表,仅一条 user 消息,其 content 形如:
    //
    //   You are summarizing a coding agent conversation. Output in two phases.
    //
    //   <analysis>
    //   (在这里写分析草稿,会被丢弃)
    //   </analysis>
    //
    //   <summary>
    //   ## 1 主要请求和意图
    //   ## 2 关键技术概念
    //   ## 3 文件和代码段
    //   ## 4 错误和修复
    //   ## 5 问题解决过程
    //   ## 6 所有用户消息原文
    //   ## 7 待办任务
    //   ## 8 当前工作(最详细)
    //   ## 9 可能的下一步
    //   </summary>
    //
    //   不要调用任何工具,输出纯文本。
    //
    //   [conversation]
    //   <serializeConversation(msgs) 的输出>
    //
    // 9 个小节标题在 prompt 中是固定字面字符串,便于 extractSummary 解析与测试匹配。
    public static List<Message> buildSummaryPrompt(List<Message> msgs);

    // serializeConversation 把对话扁平化成可读文本(不暴露 ToolCall.input 原 JSON):
    //   - 每条 user/assistant 消息:role: <content>
    //   - assistant 工具调用:[call <name> id=<id> args=<json string>]
    //   - tool 消息内的每条 result:[result id=<id> isError=<bool>] <content>
    // 行间用 \n 隔开;本函数纯函数,不依赖外部状态,便于单测固定预期文本。
    static String serializeConversation(List<Message> msgs);

    // extractSummary 从模型返回的整段文本里抠出 <summary>...</summary> 之间的正文。
    // <analysis> 部分直接丢弃。提取失败时返回原文 + 一个 warning,避免硬失败。
    public static String extractSummary(String raw);
}
```

职责:维护摘要 prompt 的全文文案、解析模型输出。

依赖:无(纯模板 + 字符串解析)。

#### `Recovery.java` — 三段恢复

```java
public final class Recovery {

    // buildRecoveryAttachment 构造摘要后的"恢复三段"内容。
    // 调用方必须先在 runSummary 入口拍一次快照 snapshot = recovery.snapshot(),
    // 把快照而非 RecoveryState 传入本函数,避免恢复段渲染期间另一个线程
    // 通过 recordFile 改变状态导致漂移。
    // 三段:
    //   1. 最近读过的文件快照:取 snapshot 前 RECOVERY_FILE_LIMIT 个(已按时间戳倒序),
    //      单文件 > RECOVERY_TOKENS_PER_FILE token 时保留头部对应字符片段,
    //      截掉尾部多余内容,并在尾部追加 (content truncated);
    //   2. 当前可用工具列表:直接来自入参 toolDefs(与 Stream 调用同一列表引用),
    //      保证恢复段宣称的工具集与 Request.tools 完全一致;
    //   3. 边界提示消息:固定文案。
    // 返回 String。摘要消息与恢复消息合并到同一条 user 消息上输出(见 Layer2.runSummary
    // 拼装规则),避免 user/user 连续;本函数只负责返回"恢复三段"的内容片段,Layer2 会
    // 与摘要文本拼到同一条 user 消息上。
    public static String buildRecoveryAttachment(
            List<FileReadRecord> snapshot,
            List<Map<String, Object>/*tool schema*/> toolDefs);

    // renderFileBlock 渲染单个文件快照:路径 / 时间戳 / 内容片段(必要时截断)。
    static String renderFileBlock(FileReadRecord rec);

    // renderToolsBlock 渲染工具列表:每行一个工具名 + 用途 + 参数 schema 摘要。
    static String renderToolsBlock(List<Map<String, Object>/*tool schema*/> defs);

    // BOUNDARY_NOTICE 是边界提示消息的固定文案常量。
    public static final String BOUNDARY_NOTICE =
            "...固定文案:需要文件原文/错误原文/用户原话时,请使用文件读取工具重新读取对应路径,不要依据摘要内容做猜测...";
}
```

职责:把 RecoveryState 快照 + toolDefs 组合成一段稳定文本。

依赖:`FileReadRecord`、`Map<String, Object>/*tool schema*/`。

#### `Token.java` — Token 估算

```java
public final class Token {

    // estimateTokens 锚定最近一次 provider usage + 之后新增消息的字符增量。
    // 入参语义:
    //   - anchor:上一次主对话路径 Stream 真实 usage 之和(long);
    //   - allMsgs:当前 conversation.messages() 完整列表;
    //   - anchorMsgLen:当 anchor 被记录时 conversation.size() 的值,
    //     表示锚点之前已被这份 usage 算进的消息条数;
    //   - 函数只把 allMsgs.subList(anchorMsgLen, size) 这部分的字符累加,避免把已含在 anchor 里的历史
    //     重复计算。
    //   - 入参 allMsgs 必须是已经经过 offloadAndSnip 处理(layer1 之后)的消息列表;
    //     否则估算偏高,会过早触发 layer2。
    //   - 返回 anchor + ceil(sum(chars(msg)) / ESTIMATE_CHARS_PER_TOKEN)。
    // 锚点为 0、anchorMsgLen 为 0(首轮 / 摘要后)时退化为纯字符估算。
    public static long estimateTokens(long anchor, List<Message> allMsgs, int anchorMsgLen);

    // usageAnchor 把 Stream 尾事件中的 usage 合并成单一锚点值。
    // 等价于 u.inputTokens() + u.outputTokens() + u.cacheRead() + u.cacheWrite()。
    public static long usageAnchor(Usage u);

    // messageChars 计算单段消息列表的字符总量(UTF-8 字节)。
    // 累加 content.getBytes(UTF_8).length + 每个 toolCalls.get(i).input 长度
    //     + 每个 toolResults.get(i).content 长度。
    static int messageChars(List<Message> msgs);
}
```

职责:纯函数估算。

依赖:无。

### Agent 主循环改造(`com.mewcode.agent.Agent`)

Agent 通过 `SessionRuntime` 拿到所有长生命周期状态;Agent 自身只新增轻量字段:

```java
public final class Agent {
    // ... 原有字段(provider / registry / version / eng)
    private final SessionRuntime runtime;  // 由构造函数注入,跨 run 复用
    private final ReentrantLock runLock = new ReentrantLock();  // 保证 run 与 runForceCompact 不并发

    // 构造函数签名扩展为 Builder 模式,避免破坏现有调用点:
    //   Agent.builder()
    //       .provider(p)
    //       .registry(r)
    //       .version(version)
    //       .engine(eng)
    //       .runtime(sessionRuntime)   // 新增,可选;不传则用默认空 runtime
    //       .build();
    // 当不传 runtime 时构造一个空 runtime 让现有测试不爆,但本章的 mewcode / smoke 入口
    // 必须显式传入。
}
```

主循环关键改动:

1. **本轮迭代开头**:按当前 `PermissionMode` 选 `defs = registry.getAllSchemas(protocol)` 或 `readOnlyDefinitions()`,把同一份 `defs` 列表同时作为 `// (参数直接传入 manage 方法).toolDefs` 和 `streamOnce` 的 `Request.tools`,保证恢复段宣称的工具与请求 tools 来自同一引用。`defs` 不缓存到 Agent 字段(避免 mode 切换时复用旧列表),但同一轮迭代内被 manageContext 与 streamOnce 共用。
2. **每轮 streamOnce 之前**:构造 `// (参数直接传入 manage 方法)`:`usageAnchor = runtime.getUsageAnchor()`、`anchorMsgLen = runtime.getAnchorMsgLen()`、`estimatedToken = Token.estimateTokens(usageAnchor, conv.messages(), anchorMsgLen)`、`trigger = AUTO`。调 `ContextCompactor.manageContext`,错误走错误流程;manageContext 内部已经把消息列表写回 conversation。
3. **streamOnce 签名扩展为抛 checked exception**:把现有 `StreamResult streamOnce(...)`(text / calls / usage / ok)改成 `StreamResult streamOnce(...) throws StreamException`,错误来源是 `AgentEvent.ErrorEvent`(mewcode 的 `Provider.stream` 接口返回 `BlockingQueue`,错误通过 `onError` 投递)。streamOnce 内部用 `CompletableFuture` 桥接订阅,在收到 Failed 时累加的 text 不写回 Conversation(保证 Conversation 状态与 Stream 调用前一致,紧急压缩可以安全地 replaceMessages)。
4. **Stream 完成后**(仅主对话路径):从尾事件中读 `usage`,调 `Token.usageAnchor(usage)` 更新 `runtime.usageAnchor`,同时 `runtime.anchorMsgLen = conv.size()`。摘要请求结束后**不**更新这两个字段。
5. **ReadFile 工具调用成功后**:在 `executeBatched` 内、工具 worker virtual thread 内同步执行:检测 `toolName.equals("ReadFile")` 且 `tool.Result.isError() == false`(注意 isError 来自 `tool.Result`,不是 `llm.ToolResult`),把 `ToolCall.input`(`JsonNode`)反序列化成 `Map<String, Object>`,取出 `path` 字段(与 `tool/ReadFileTool` 定义的参数名一致),`Path.of(path).toAbsolutePath().normalize()` 后用 `Files.readString(absPath)` 拿纯净字节,调 `runtime.recovery.recordFile(absPath.toString(), content)`。读盘失败吞掉。调用必须在 `conv.addToolResults(results)` **之前**完成(同 virtual thread 顺序),保证下一次 manageContext 能看到本轮 ReadFile 的记录。
6. **错误捕获 / 紧急压缩**:在主循环内捕获 `streamOnce` 抛出的 `StreamException`,用 `ex.getCause() instanceof PromptTooLongException` 判断。命中时:
   - 用一个迭代级局部变量 `emergencyRetried = false` 锁定一次性重试。若已为 true 则按正常错误上抛。
   - 调 `ContextCompactor.manage(in)` with `trigger = EMERGENCY`:内部先做一次 offloadAndSnip 再 forceCompact。
   - 紧急压缩成功后把 `runtime.usageAnchor = 0`、`runtime.anchorMsgLen = 0`(conversation 已重建),用 `estimateTokens(0, conv.messages(), 0)` 重新算估算 token;若估算已低于 `contextWindow - MANUAL_SAFETY_MARGIN` 则置 `emergencyRetried = true` 后重试本轮 streamOnce;否则视为不可恢复,按错误上抛,不做第二次紧急压缩。
   - 紧急路径里 forceCompact 内部若遇 PTL 走 ptlRetry,全程不调 autoTracking 任何方法。

> **run 与 runForceCompact 互斥**:Agent 在 `run` 入口先 `runLock.lock()`,结束时 `unlock()`;`runForceCompact` 入口也先 `lock()`。保证手动 /compact 不与正在进行的 run 并发触发 manageContext。

> **run 期间 registry 不可变**:本章承诺主循环开头算出的 toolDefs 在一次 run 调用内保持稳定;MCP 工具的注册/注销只允许发生在 run 之间。如果未来需要 run 中动态增删工具,必须重新约定 toolDefs 重算时机并同步刷新恢复段缓存。

> **压缩状态事件 emit**(兑现 spec F24a / F24b):`StreamEvent` sealed 接口新增 `Compact` 子类型。主循环在以下两个时机向订阅者 emit 状态事件,让 TUI 在 LLM 摘要请求还在跑的时候就能立刻显示"压缩中"前缀,避免用户以为程序卡死:
>
> - **自动路径**(主循环步骤 2 内):若本轮 `estimateTokens` 已超 `contextWindow - SUMMARY_RESERVE - AUTO_SAFETY_MARGIN`(即必然要走 layer 2),在调 `ContextCompactor.manageContext` **之前** emit `new CompactEvent(CompactPhase.BEFORE_AUTO, 0, 0, null)`;manageContext 返回后 emit `new CompactEvent(CompactPhase.AFTER_AUTO, before, after, err)`。如果本轮估算未超阈值(只跑 layer 1 / 什么都不做),**不发任何 Compact 事件**——layer 1 是静默操作。
> - **紧急路径**(主循环步骤 6 内):在 `trigger = EMERGENCY` 调 manageContext **之前** emit `new CompactEvent(BEFORE_EMERGENCY)`;manageContext 返回后 emit `AFTER_EMERGENCY`。
> - **手动路径**(`/compact` / runForceCompact):不走 Compact 事件路径,由 TUI handleCompact 直接拿到 `(before, after, err)` 三元组通过 `JLine/tui.tea.program.send()` 回投,文案统一格式见后文 TUI 渲染段。
>
> ```java
> // com.mewcode.agent.events
>
> public enum CompactPhase {
>     BEFORE_AUTO,
>     AFTER_AUTO,
>     BEFORE_EMERGENCY,
>     AFTER_EMERGENCY
> }
>
> public record CompactEvent(
>         CompactPhase phase,
>         long before,    // After 状态有意义;Before 状态置 0
>         long after,     // 同上
>         Throwable error // After 状态可能非 null(PTL 重试全部失败或熔断生效)
> ) implements AgentEvent {}
>
> public sealed interface AgentEvent
>         permits TextEvent, ToolEvent, DoneEvent, FailedEvent, CompactEvent {}
> ```

### Conversation 改造(`com.mewcode.conversation.Conversation`)

新增一个整体替换方法,并补充内部 lock 保护:

```java
public final class Conversation {
    // 内部新增 ReentrantLock lock(messages / replaceMessages / addXxx 都加锁),
    // 防止 replaceMessages 与 messages 并发时拿到部分写入的列表。
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Message> messages = new ArrayList<>();

    // replaceMessages 把内存数组整体替换为传入的 msgs。
    // compact 摘要后用这个方法一次性丢弃旧历史并装入"摘要 + 恢复 + 近期原文"。
    // 不暴露列表引用,做深拷贝(含 toolCalls / toolResults 列表)以免外部继续持有旧列表。
    public void replaceMessages(List<Message> msgs);
}
```

> **性能评估**:每轮 manageContext 都会调 replaceMessages(layer1-only 时也要写回,否则 offloadAndSnip 的字符串替换不会作用于下一轮)。25 轮 × 数十条消息 × 数百 KB 字符串的深拷贝在毫秒级完成,与摘要 LLM 请求几十秒耗时相比可忽略;不做对象池。

### TUI 命令分发(`com.mewcode.tui`)

`com.mewcode.tui.MewCodeModel` 现有 `submit()` 内部已经有针对 `/exit` / `/plan` / `/do` 的 switch 分支。本章把这三个命令一并迁移到统一注册表,并新增 `/compact`:

```java
// com.mewcode.tui.Commands(新文件)

@FunctionalInterface
public interface CommandHandler {
    void handle(MewCodeModel app);
}

public final class Commands {

    // BUILTIN_COMMANDS 注册表初始填四项:迁移现有 /exit / /plan / /do,新增 /compact。
    public static final Map<String, CommandHandler> BUILTIN_COMMANDS = Map.of(
            "/exit",    Commands::handleExit,
            "/plan",    Commands::handlePlan,
            "/do",      Commands::handleDo,
            "/compact", Commands::handleCompact);

    // dispatchCommand 检查输入是否以 "/" 开头;命中则返回对应命令处理器;
    // 未以 "/" 开头返回 Optional.empty();以 "/" 开头但未注册则返回 unknownCommand handler。
    public static Optional<CommandHandler> dispatchCommand(String input);

    // handleCompact 在 virtual thread 里调 app.getAgent().runForceCompact(ctx, conv, defs);
    // 完成后通过 program.send(new AgentEventMessage(...) 把 (before, after, err) 投回 UI 线程,
    // 由 UI 决定追加系统消息:成功 "已压缩,token 从 X 降至 Y",失败 "压缩失败:<err>"。
    // 命令路径不调 conv.addUser,不写入对话历史。
    static void handleCompact(MewCodeModel app);
}
```

MewCodeModel 字段调整:

```java
public final class MewCodeModel {
    // ... 原有字段
    private final SessionRuntime runtime;  // 新增:跨 run 持有的长生命周期状态
    private final Agent agent;             // 新增:常驻 Agent 实例(在 beginTurn 内复用,不再每轮 build)
}
```

`beginTurn` 不再每轮 `Agent.builder().build()`:构造期一次性 `this.agent = Agent.builder().provider(...).registry(...).version(...).engine(...).runtime(runtime).build()`;`beginTurn` 只调 `agent.run(turnCtx, conv, mode)`。

Agent 层新增 `runForceCompact(ctx, conv, defs)` 给 TUI 调用:内部先 `runLock.lock()` 等待主循环空闲,再构造 // (参数直接传入 manage 方法) with `trigger = MANUAL`,调 `ContextCompactor.manageContext`,从 Output 取 beforeTokens / afterTokens 返回。

**TUI 渲染 Compact 事件**(兑现 spec F24a / F24b):`com.mewcode.tui.AgentEvent 队列` 的 `onNext` 在分派 `AgentEvent` 时新增 `event instanceof CompactEvent c` 分支,按 `phase` 渲染系统消息后继续订阅下一帧,**不写入 conversation**:

| Phase | 渲染文案 |
|-|-|
| `BEFORE_AUTO` | `"正在压缩上下文..."` |
| `BEFORE_EMERGENCY` | `"上下文撞墙,自动压缩中..."` |
| `AFTER_AUTO` / `AFTER_EMERGENCY`(error == null) | `"已压缩,token 从 <before> 降至 <after>"` |
| `AFTER_AUTO` / `AFTER_EMERGENCY`(error != null) | `"压缩失败:<error.getMessage()>"` |

格式化逻辑抽出一个内部方法 `formatCompactNotice(CompactEvent ev) → String`,让 `handleCompact` 的 program.send() 回投路径(手动 `/compact`)也复用同一个方法渲染完成态文案,确保自动 / 紧急 / 手动三条路径的文案风格一致。

### config 改造(`com.mewcode.config`)

- `ProviderConfig` 增加 `int contextWindow` 字段并支持从 YAML 解码。
- 新增 `effectiveContextWindow()` 方法:配置 > 0 返回配置值;否则按 protocol 给默认值(anthropic→200000,openai→128000,其他 protocol→200000 作为保守默认)。
- `com.mewcode.config.ConfigLoaderTest` 增加:未配置 / 配置为 0 / 配置为正数 / 未知 protocol 四种情况的断言。

### `.mewcode/config.yaml.example` 更新

在 providers 数组里给每个 provider 加上 `context_window` 示例值与注释:

```yaml
providers:
  - name: claude
    protocol: anthropic
    api_key: sk-ant-xxx
    model: claude-sonnet-4-5
    context_window: 200000   # 可选,未配置时按 protocol 默认(anthropic 200000、openai 128000)
```

## 模块交互

**正常路径(自动触发):**

```
用户输入 (TUI)
    │ 非 / 开头
    ▼
Agent.run() virtual thread
    │
    ├─[迭代 N 开头]→ registry.getAllSchemas(protocol) / readOnlyDefinitions() ──→ defs(本轮复用)
    │
    │   ┌─────────────────────────────────────────────────┐
    │   │            ContextCompactor.manageContext                │
    │   │  ┌──────────────────────────────────────────┐   │
    │   │  │ 1. Layer1.offloadAndSnip                 │   │
    │   │  │    - 查 ContentReplacementState 账本     │   │
    │   │  │    - 新 id:判断 single / aggregate       │   │
    │   │  │    - 落盘到 spillDir/<toolUseId>         │   │
    │   │  │    - 写入账本(冻结决策)                  │   │
    │   │  └──────────────────────────────────────────┘   │
    │   │              │                                   │
    │   │              ▼                                   │
    │   │  ┌──────────────────────────────────────────┐   │
    │   │  │ 2. Token.estimateTokens                  │   │
    │   │  │    = anchor + chars / 3.5                │   │
    │   │  └──────────────────────────────────────────┘   │
    │   │              │                                   │
    │   │              ▼                                   │
    │   │  estimated >= window-20000-13000 且未熔断?      │
    │   │              │ 是                                │
    │   │              ▼                                   │
    │   │  ┌──────────────────────────────────────────┐   │
    │   │  │ 3. Layer2.autoCompact                    │   │
    │   │  │    a. buildSummaryPrompt(无工具)         │   │
    │   │  │    b. Provider.stream → <summary> 解析   │   │
    │   │  │    c. buildRecoveryAttachment (3 段)     │   │
    │   │  │    d. pickRecentTail + 配对修正          │   │
    │   │  │    e. 拼接成 newMsgs                     │   │
    │   │  │    f. Conversation.replaceMessages       │   │
    │   │  │    g. 成功→失败计数清零;失败→+1,熔断    │   │
    │   │  └──────────────────────────────────────────┘   │
    │   └─────────────────────────────────────────────────┘
    │
    ├─→ streamOnce: Provider.stream(Request{ messages, tools=defs })
    │       │
    │       ├─正常完成 → 读尾事件 usage → usageAnchor 更新
    │       │
    │       └─PromptTooLongException → 紧急压缩路径(见下)
    │
    └─→ executeBatched 工具调用
            │
            └─ReadFile 成功 → Files.readString 纯净字节 → RecoveryState.recordFile
```

**紧急压缩路径(provider 撞墙):**

```
Provider.stream 投递 onError(PromptTooLongException)
    │
    ▼
streamOnce 抛 StreamException(已累加的 text 不写入 Conversation,保证状态原子)
    │
    ▼
主循环:emergencyRetried 已为 true? → 是:按错误上抛
    │ 否
    ▼
ContextCompactor.manage(trigger=EMERGENCY)
    │   - 跳过阈值检查、跳过熔断器
    │   - 先强制跑一次 offloadAndSnip(layer1)把大工具结果挪走
    │   - 再 forceCompact → runSummary → replaceMessages
    │     若 runSummary 内部撞 PTL → 走 F27 的 ptlRetry(不调 autoTracking)
    ▼
重置锚点:runtime.usageAnchor=0、runtime.anchorMsgLen=0
重新估算:est = estimateTokens(0, conv.messages(), 0)
    │
    ▼
est < contextWindow - MANUAL_SAFETY_MARGIN?
    ├─是 → emergencyRetried=true → 重试本轮 streamOnce
    │       ├─成功 → 继续主循环
    │       └─再次 PTL → 按错误上抛,不再做第二次紧急压缩
    └─否 → 视为不可恢复,按错误上抛
```

**手动压缩路径:**

```
TUI 输入 "/compact"
    │
    ▼
dispatchCommand 命中 → 不发 LLM
    │
    ▼
Agent.runForceCompact
    │
    ▼
ContextCompactor.manage(trigger=MANUAL)
    │   - 同 EMERGENCY 路径
    ▼
返回 (before, after)
    │
    ▼
TUI push 系统消息 "已压缩,token 从 X 降至 Y"
```

**摘要请求自身 PTL:**

```
summarizeOnce 收到 PromptTooLongException
    │
    ▼
groupByUserTurn(msgs) → groups
    │
    ├─第 1~3 次:每次丢最旧的 1 组 → 重试 summarizeOnce
    │
    └─第 4 次起:丢 ceil(剩余 * 0.2) 组 → 重试
        │
        ├─成功 → 返回
        └─groups 全空 → 返回错误(上层熔断计数 + 1)
```

## 文件组织

```
src/main/java/com/mewcode/compact/
├── ContextCompactor.java                  — manageContext 主入口、TriggerKind 枚举、编排两层调用
├── // (Layer 1/2 都在 ContextCompactor.java 内)                   — offloadAndSnip / spillSingle / buildPreview
├── // (Layer 1/2 都在 ContextCompactor.java 内)                   — autoCompact / forceCompact / runSummary / summarizeOnce / ptlRetry / pickRecentTail / groupByUserTurn
├── SummaryPrompt.java            — buildSummaryPrompt 模板 + serializeConversation + extractSummary 解析
├── Recovery.java                 — RecoveryState / FileReadRecord / buildRecoveryAttachment / BOUNDARY_NOTICE
├── Token.java                    — estimateTokens / usageAnchor / messageChars
├── state/
│   ├── ContentReplacementState.java  — decideOnce、Decision 枚举
│   ├── AutoCompactTrackingState.java
│   └── SessionContext.java
├── CompactConstants.java         — 全部硬编码常量
├── CompactException.java         — checked exception 基类
└── PromptTooLongException.java   — PTL 哨兵

src/test/java/dev/mewcode/compact/
├── CompactTest.java              — manageContext 集成单测(FakeProvider 驱动)
├── Layer1Test.java               — 单条 / 聚合 / 幂等 / 决策冻结 / 落盘失败降级
├── Layer2Test.java               — 摘要流程 / PTL 重试 / 熔断计数 / 近期原文边界 / 配对修正
├── SummaryPromptTest.java        — Prompt 文本断言 + <summary> 解析
├── RecoveryTest.java             — 文件快照排序 / 截断 / 工具集合一致性 / 并发写 / BOUNDARY_NOTICE 稳定
├── TokenTest.java                — 锚点 + 字符增量 / usage 合并
└── support/FakeCompactProvider.java
```

`com.mewcode.agent.Agent` 改动:
- 新增 `SessionRuntime runtime` 注入字段与 `ReentrantLock runLock`;`Agent.builder()` 增加 `.runtime(SessionRuntime)` 方法以便保留对现有测试的兼容。
- 把 `streamOnce` 签名改成抛 `StreamException`;错误由内部从 `AgentEvent.ErrorEvent` 捕获。
- 主循环本轮迭代开头按 mode 选 `defs = registry.getAllSchemas(protocol)` 或 `readOnlyDefinitions()`,同一份列表传给 // (参数直接传入 manage 方法).toolDefs 与 Request.tools。
- 每轮 `streamOnce` 前调 `ContextCompactor.manage(trigger=AUTO)`。
- 主对话路径 `streamOnce` 完成后更新 `runtime.usageAnchor` 与 `runtime.anchorMsgLen`;摘要路径不更新。
- 在工具结果回填阶段对 ReadFile 调用 `Files.readString` 纯净字节并写入 `recovery`(同 virtual thread、`addToolResults` 之前)。
- 捕获 `PromptTooLongException` → `manage(trigger=EMERGENCY)` → 重新估算后同迭代重试一次。
- 新增 `runForceCompact(ctx, conv, defs) → (before, after, err)` 给 TUI 调;入口先 `runLock.lock()`。

`com.mewcode.agent.SessionRuntime`(新文件):定义 `SessionRuntime` 类与构造函数;Builder 中的 `.runtime(...)` 方法。

`com.mewcode.agent.AgentTest` 改动:
- 已有 `FakeProvider` 扩展能力:① 在脚本最后一帧之前发送 `StreamEvent.Usage(...)`;② 支持按调用次数序列化错误投递(包括包装好的 `PromptTooLongException`)。
- 新增"撞墙后紧急压缩成功"与"紧急压缩后再次撞墙上抛"两个用例。

`com.mewcode.conversation.Conversation` 改动:新增 `ReentrantLock lock`;新增 `replaceMessages(List<Message> msgs)`,做深拷贝;已有 `addXxx` / `messages` / `size` / `lastRole` 全部加锁。

`com.mewcode.conversation.ConversationTest` 改动:新增 `replaceMessages` 的直接断言用例。

`com.mewcode.llm.Provider` 改动:
- 新增 `PromptTooLongException extends LlmException` 哨兵异常。
- `Map<String, Object>/*tool schema*/` 已是公开 record,无需改动。

`com.mewcode.llm.AnthropicProvider` / `OpenAIProvider` 改动:捕获 provider 返回的"上下文过长"错误码或消息片段,包装成 `new PromptTooLongException(origErr)` 并通过 `publisher.submit(new AgentEvent.ErrorEvent(wrapped))` 投递(接口签名只有 `BlockingQueue` 返回值,错误走 `onError`)。

`com.mewcode.llm.AnthropicProviderTest` / `OpenAIProviderTest`:注入构造好的错误返回,断言:① 典型 `prompt_too_long` / `context_length_exceeded` 被 stream 转换成 wrapped error 投递到 `AgentEvent.ErrorEvent`;② `wrapped instanceof PromptTooLongException` 命中;③ 其他 4xx/5xx 错误不被错误地包装为 PTL。

`com.mewcode.tui.Commands`(新文件):`dispatchCommand` + `handleExit` / `handlePlan` / `handleDo` / `handleCompact` + 未知命令兜底。
`com.mewcode.tui.MewCodeModel`:`submit()` 内原 switch 分支改用 `dispatchCommand` 调用;命令路径不调 `conv.addUser`,不写入对话历史。
`com.mewcode.tui.MewCodeModel` 字段:新增 `SessionRuntime runtime` 与 `Agent agent`;构造期一次性构造 Agent 并保存。
`com.mewcode.tui.MewCodeModelTest`:① `/compact` 走命令路径不发 LLM;② `/unknown` 友好提示;③ 迁移后 `/exit` / `/plan` / `/do` 行为不回归三个用例。

`com.mewcode.config.ProviderConfig`:追加 `int contextWindow`,加 `effectiveContextWindow()`;现有字段顺序与 yaml 映射不变。
`com.mewcode.config.ProtocolDefaults`(新文件):`DEFAULT_ANTHROPIC_CONTEXT_WINDOW = 200000`、`DEFAULT_OPENAI_CONTEXT_WINDOW = 128000`。
`com.mewcode.config.ConfigLoaderTest`:新增四种情况断言。

`com.mewcode.MewCode`:启动阶段调 `SessionContext.create(workspace)`、`new ContentReplacementState()`、`new RecoveryState()`、`new AutoCompactTrackingState()`,组装为 `SessionRuntime`;待 provider 选定后注入 `effectiveContextWindow()`;把 `SessionRuntime` 交给 MewCodeModel。

`com.mewcode.smoke.SmokeMain`:同样按新签名构造 Agent;smoke 场景的 contextWindow 可固定 200000。

`.gitignore`:追加 `.mewcode/sessions/`,避免开发者跑一次 mewcode 后 `git status` 出现一大坨 session 子目录。

`.mewcode/config.yaml.example`:新增 `context_window` 字段示例与注释。

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 包结构与命名 | `com.mewcode.compact` 单包,子文件按职责拆分(Layer1 / Layer2 / Recovery / Token) | 上下文管理逻辑高度内聚,对外只暴露 `ContextCompactor.manageContext` 等少量静态方法。单包简化导入,多文件保证可读性。子包拆分会引入循环引用风险(Layer2 既要 Token 又要 Recovery)。 |
| ContentReplacementState 临界区 | offloadAndSnip 持 lock 全程;账本读写不暴露给外部,杜绝 TOCTOU 翻转 | 账本对外只通过 decideOnce 这一个高层方法操作(持锁 + 回调内决策 + 同临界区写入),消除"读账本→落盘→写账本"之间的并发翻转窗口。 |
| AutoCompactTrackingState 独立于 ContentReplacementState | 拆成两个类 | 熔断只用于自动路径,手动 / 紧急完全绕过;放一起会让"是否应该读熔断字段"在调用点变得模糊。两个类都内嵌 `ReentrantLock` 保证并发安全。 |
| 9 部分 + 两阶段摘要 prompt 内嵌 | 直接写在 `SummaryPrompt.java` 的 text block(Java 15+ 三引号字符串)常量里 | Prompt 是产品规范的一部分,不需要从外部加载;放代码里方便 review 与版本回滚。也避免在测试里读文件。9 个小节标题用固定字面字符串,便于 extractSummary 与单测匹配。 |
| 摘要请求不传 tools | Request.tools 留空(`List.of()`) | 摘要本身是"压缩历史"的语义动作,模型不应该在摘要阶段发起新工具调用。保留 tools 会让模型混淆任务,且消耗额外 token。 |
| ReadFile 后用 Files.readString 重读纯净字节 | 工具 worker virtual thread 内同步重读 | 工具返回字符串带行号前缀(mewcode 现有实现),直接拿来做恢复段会让模型把行号当成代码的一部分。重读一次磁盘成本可忽略;同步顺序保证下一次 manageContext 能观察到本轮记录。 |
| 主循环本轮迭代开头算 toolDefs | 局部变量复用,不缓存到 Agent 字段 | F17 要求恢复段声明的工具集合和 Stream 调用的 tools 严格一致。同一轮迭代按 mode 选好后,把同一份列表同时传给 // (参数直接传入 manage 方法).toolDefs 与 Request.tools,引用一致即逐项一致。 |
| estimateTokens 用 3.5 字符/token | 硬编码 `ESTIMATE_CHARS_PER_TOKEN = 3.5` | 锚定真实 usage 已经是主力,字符比例只用于两次真实请求之间的近似。3.5 是英文+代码混合场景下的常用经验值,过细的差异会被锚点纠正。 |
| 紧急压缩只重试一次 | 同迭代内 emergencyRetried 锁定一次性重试 | 紧急压缩已经丢掉了一大段历史,如果重试还失败说明问题不是 token 而是其他(如单条 user 消息就超长)。多次重试只会让用户等更久。重试前必须重估 token 低于 `contextWindow - MANUAL_SAFETY_MARGIN`,否则视为不可恢复。 |
| sessionId 不持久化 | 进程启动生成 `<unix_ts>-<short_random>` | 单进程会话边界等于进程边界,不需要恢复。`.mewcode/sessions/` 留作调试副产物,外部脚本/用户决定清理时机。 |
| 阈值硬编码 + 仅 context_window 走 config | 单项 config 暴露 | context_window 由 provider 决定,跨 provider 必须可配。其余阈值若开放为配置会指数级放大测试矩阵,且没有跨用户的差异化需求。本章不开放为配置项;调整属于代码变更。 |
| Layer 1 落盘失败降级为不替换 | 不进 seenIds,下次重试 | 磁盘问题是瞬时的可恢复故障,不应该让对话因此中断。N6 错误隔离的直接体现。 |
| Layer 2 PTL 重试中按"用户提交 + 一组往返"分组 | groupByUserTurn 抽成独立方法 | F27 的语义保证最早被丢的是最旧的一整轮交互,不会把同一轮的 user/assistant/tool 拆成半截。独立方法便于单元测试。 |
| Conversation 内部 lock + replaceMessages 深拷贝 | 加锁 + 拷贝整列表 | 摘要后 compact 把 newMsgs 交出去就不应该再被外部改动;Conversation 也不应该暴露内部列表引用。深拷贝在 25 轮 × 数百 KB 量级下耗时毫秒级,与摘要 LLM 请求耗时相比可忽略;不再做对象池。 |
| TUI 命令分发用 Map<String, CommandHandler> | 极简注册表(4 项:/exit、/plan、/do、/compact) | 本章只有这几个内置命令,O(1) 查找已经够用;分支/参数解析框架不在本章范围。 |
| 命令路径不写入 conversation | UI 层 push 系统消息,不调 conv.addUser | `/compact` 等命令不属于对话语义,进入 conversation 会污染下一轮 LLM 输入。系统消息只在 TUI 视图层展示。 |
| 摘要 + 恢复段合并为一条 user 消息 | runSummary 输出新对话首条是单条 user,content 内嵌 9 部分摘要 + 三段恢复 | Anthropic 协议禁止 user/user 连续;分两条会导致 400 错误。合并后续接近期原文(无论首条是 user / assistant / tool 都不破坏交替)。摘要写在前、三段恢复紧随其后,全部装在一个 user.content 字符串里。 |
| pickRecentTail 配对修正与 role 衔接 | 截断点前推 + 必要时插入 assistant 占位 | 截断点夹在 tool_use/tool_result 中间时,向前推到 tool_use 之前;若拼接后导致 summary(user) 紧接近期原文首条 user,则在 recovery 段后、近期原文前插入一条 assistant 衔接占位,保证 Anthropic user/assistant 交替约束。 |
| ProviderConfig 新增方法 effectiveContextWindow | 派生方法而非构造时折算 | 配置加载时不知道 protocol 默认值表,把默认值表收敛到方法里,让 config 加载逻辑保持纯字段映射。也便于后续追加新 protocol 默认值。 |
| PromptTooLongException 作为 llm 包哨兵异常 | `instanceof` / `getCause()` 判断 | 不同 provider 返回的具体错误结构差异大(HTTP 400 vs structured error),统一成哨兵异常后 agent 主循环只需要一处判断。AnthropicProvider / OpenAIProvider 通过 `publisher.submit(new AgentEvent.ErrorEvent(wrapped))` 把 PTL 错误投递到事件流,主循环从 `AgentEvent.ErrorEvent.error()` 用 `instanceof` 检测。 |
| context_window 注入时机 | provider 选定后由 Main 注入 SessionRuntime,本会话内不变 | mewcode 启动期 TUI 可能选 provider,等用户选定后才能确定 context_window;本章不支持运行期切 provider;切换 provider 等同于重新启动进程。 |
| context_window 下界检查 | 必须 > `SUMMARY_RESERVE + AUTO_SAFETY_MARGIN`(即 > 33000) | 低于此值时 `contextWindow - 33000` 为非正数,自动阈值判断永远成立,每轮都会触发摘要导致死循环;manageContext 在入口对 contextWindow 做 sanity check,过小时跳过自动 layer2 并写一条警告日志。 |