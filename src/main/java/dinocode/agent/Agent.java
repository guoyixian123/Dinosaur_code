package dinocode.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dinocode.core.ChatEvent;
import dinocode.core.ErrorKind;
import dinocode.core.Message;
import dinocode.core.Role;
import dinocode.core.ToolCall;
import dinocode.core.ToolDefinition;
import dinocode.core.ToolResult;
import dinocode.core.Usage;
import dinocode.permission.Mode;
import dinocode.permission.Outcome;
import dinocode.permission.PermissionEngine;
import dinocode.compact.CompactConstants;
import dinocode.compact.CompactException;
import dinocode.compact.ContextCompactor;
import dinocode.compact.Token;
import dinocode.provider.ChatProvider;
import dinocode.provider.ChatRequest;
import dinocode.provider.EventStream;
import dinocode.prompt.Environment;
import dinocode.prompt.Prompt;
import dinocode.prompt.Reminder;
import dinocode.tool.Result;
import dinocode.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ReAct 循环编排（ch04 F1/F2）：
 * for 迭代——带工具发请求 → 流式收集 → 有工具则执行并回灌进入下一轮；无工具则纯文本即最终答复。
 * 停止条件：自然完成 / 迭代上限 / 用户取消 / 连续未知工具 / 流出错（F2）。
 * 保序分批并发执行：连续只读并发、有副作用串行、保持调用序（F5）。
 * ch06：每次执行前过五层权限流水线（引擎前四层 + 人在回路第五层）；Deny 回灌不中断（F9）。
 */
public final class Agent {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** 规划模式完整提醒的重复间隔（ch05 F7）：首轮完整，之后每 4 轮重复一次完整，其余精简。 */
    static final int PLAN_REMINDER_INTERVAL = 4;

    private final ChatProvider provider;
    private final ToolRegistry registry;
    private final String version;
    private final PermissionEngine engine;
    /** ch08：上下文管理长生命周期状态；null 表示禁用压缩（测试/向后兼容）。 */
    private final CompactContext compact;
    /** ch09：记忆管理器（可空）+ 注入系统提示的指令/记忆文本。 */
    private final dinocode.memory.Memory.Manager memMgr;
    private final String instructionText;
    private final String memoryText;
    /** ch09：run 轮次计数（记忆更新触发用）。 */
    private final java.util.concurrent.atomic.AtomicLong turnCount = new java.util.concurrent.atomic.AtomicLong();
    /** 测试用：预置的人在回路决策队列；非 null 时 requestApproval 从此取决策（null=取消）。 */
    private final java.util.Deque<Outcome> scriptedOutcomes;

    public Agent(ChatProvider provider, ToolRegistry registry, String version, PermissionEngine engine) {
        this(provider, registry, version, engine, null, null, "", "");
    }

    /** 测试用：预置人在回路决策（依序消费；耗尽后阻塞等待真实回传）。 */
    public Agent(ChatProvider provider, ToolRegistry registry, String version, PermissionEngine engine,
                 java.util.Deque<Outcome> scriptedOutcomes) {
        this(provider, registry, version, engine, null, null, "", "", scriptedOutcomes);
    }

    /** 完整构造：ch08 上下文管理 + ch09 记忆。 */
    public Agent(ChatProvider provider, ToolRegistry registry, String version, PermissionEngine engine,
                 CompactContext compact, dinocode.memory.Memory.Manager memMgr,
                 String instructionText, String memoryText) {
        this(provider, registry, version, engine, compact, memMgr, instructionText, memoryText, null);
    }

    private Agent(ChatProvider provider, ToolRegistry registry, String version, PermissionEngine engine,
                  CompactContext compact, dinocode.memory.Memory.Manager memMgr,
                  String instructionText, String memoryText, java.util.Deque<Outcome> scriptedOutcomes) {
        this.provider = provider;
        this.registry = registry;
        this.version = version == null ? "" : version;
        this.engine = engine;
        this.compact = compact;
        this.memMgr = memMgr;
        this.instructionText = instructionText == null ? "" : instructionText;
        this.memoryText = memoryText == null ? "" : memoryText;
        this.scriptedOutcomes = scriptedOutcomes;
    }

    /** 测试/无权限场景的便捷构造：跳过权限判定（全放行）。 */
    public Agent(ChatProvider provider, ToolRegistry registry, String version) {
        this(provider, registry, version, PermissionEngine.allowAll());
    }

    /** 无权限引擎但有压缩上下文（兼容 ch08 调用点）。 */
    public Agent(ChatProvider provider, ToolRegistry registry, String version, PermissionEngine engine,
                 CompactContext compact) {
        this(provider, registry, version, engine, compact, null, "", "");
    }

    /**
     * @param mode   决定工具集与按轮次提醒（ch05 F7 / ch06 F5）
     * @param cancel per-turn 取消句柄（ch04 F7）
     */
    public TurnStream run(List<Message> history, int maxTokens, Mode mode, CancelToken cancel) {
        List<ToolDefinition> defs = mode == Mode.PLAN
                ? registry.readOnlyDefinitions()
                : registry.definitions();
        // 稳定系统提示跨模式一致（规划提醒已移入 reminder 通道，ch05 F7）
        // ch09：指令与记忆文本注入 custom-instructions / long-term-memory 槽位（F43）
        String stable = Prompt.buildSystemPrompt(instructionText, memoryText);
        String envText = Environment.gather(version, provider.model()).render();

        BlockingQueue<TurnEvent> queue = new LinkedBlockingQueue<>();
        AtomicReference<EventStream> currentStream = new AtomicReference<>();

        cancel.onCancel(() -> {
            EventStream s = currentStream.get();
            if (s != null) {
                s.close(); // 关闭底层连接，解除流阻塞
            }
        });

        Thread.ofVirtual().start(() -> {
            TurnEvent terminal;
            try {
                terminal = loop(history, maxTokens, defs, stable, envText, mode, cancel, queue, currentStream);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                terminal = new TurnEvent.Done(Usage.UNKNOWN);
            } catch (RuntimeException e) {
                terminal = new TurnEvent.Error("错误: " + e.getMessage());
            }
            try {
                queue.put(terminal);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        return new TurnStream() {
            private boolean ended;

            @Override
            public TurnEvent next() {
                if (ended) {
                    return null;
                }
                TurnEvent ev;
                try {
                    ev = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                if (ev instanceof TurnEvent.Done || ev instanceof TurnEvent.Error) {
                    ended = true;
                }
                return ev;
            }

            @Override
            public void close() {
                cancel.cancel();
            }
        };
    }

    /** ReAct 主循环；返回终止事件。 */
    private TurnEvent loop(List<Message> history, int maxTokens, List<ToolDefinition> defs,
                           String stable, String envText, Mode mode,
                           CancelToken cancel, BlockingQueue<TurnEvent> queue,
                           AtomicReference<EventStream> currentStream) throws InterruptedException {
        int unknownRun = 0;
        Usage lastUsage = Usage.UNKNOWN;

        for (int iter = 1; iter <= AgentConstants.MAX_ITERATIONS; iter++) {
            queue.put(new TurnEvent.Iter(iter));
            if (cancel.isCancelled()) {
                return finishCancelled(history, queue);
            }

            // 按轮次构造补充消息（ch05 F7）：规划模式首轮完整、每 4 轮重复完整、其余精简
            String reminder = "";
            if (mode == Mode.PLAN) {
                boolean full = iter == 1 || (iter - 1) % PLAN_REMINDER_INTERVAL == 0;
                reminder = Reminder.plan(full);
            }

            // ch08：每轮请求前上下文管理（Layer1 预防 + 阈值判断 + 自动 Layer2）
            boolean emergencyRetried = false;
            StreamOutcome out;
            try {
                out = streamWithCompact(history, maxTokens, defs, stable, envText, reminder,
                        mode, cancel, queue, currentStream, true);
            } catch (StreamFailure f) {
                // 紧急压缩路径（F25/F26）：PTL → EMERGENCY 压缩 → 重试一次
                if (f.overflow && compact != null && !emergencyRetried) {
                    queue.put(new TurnEvent.Notice("上下文撞墙，自动压缩中..."));
                    if (!runEmergencyCompact(history, defs, mode, queue)) {
                        return new TurnEvent.Error("紧急压缩失败");
                    }
                    emergencyRetried = true;
                    try {
                        out = streamWithCompact(history, maxTokens, defs, stable, envText, reminder,
                                mode, cancel, queue, currentStream, false);
                    } catch (StreamFailure f2) {
                        queue.put(new TurnEvent.Error(f2.message));
                        return new TurnEvent.Error(f2.message);
                    }
                } else {
                    queue.put(new TurnEvent.Error(f.message));
                    return new TurnEvent.Error(f.message);
                }
            }
            lastUsage = out.usage;
            queue.put(new TurnEvent.UsageReport(out.usage));

            // 无工具调用：纯文本即最终答复（自然完成，F2-1）
            if (out.calls().isEmpty()) {
                history.add(Message.assistant(ensureFinal(out.text())));
                triggerMemoryUpdate(history); // ch09 F35：本轮结束，条件满足时异步提取笔记
                return new TurnEvent.Done(out.usage);
            }

            history.add(Message.assistantWithTools(out.text(), out.calls()));

            // 连续未知工具计数（F2-4）
            if (allUnknown(out.calls())) {
                unknownRun++;
            } else {
                unknownRun = 0;
            }

            BatchOutcome batch = executeBatched(out.calls(), mode, cancel, queue);
            recordReadFiles(out.calls(), batch.results());
            history.add(Message.tool(batch.results())); // 无论取消都回灌，含已取消占位（F6）

            if (!batch.completed()) { // 执行中被取消：最高优先级收尾
                return finishCancelled(history, queue);
            }
            if (unknownRun >= AgentConstants.MAX_UNKNOWN_RUN) {
                queue.put(new TurnEvent.Notice(AgentConstants.NOTICE_UNKNOWN_TOOLS));
                ensureAssistantTail(history, AgentConstants.NOTICE_UNKNOWN_TOOLS);
                return new TurnEvent.Done(lastUsage);
            }
        }

        // 触达迭代上限（F2-2）
        queue.put(new TurnEvent.Notice(AgentConstants.NOTICE_MAX_ITER));
        ensureAssistantTail(history, AgentConstants.NOTICE_MAX_ITER);
        return new TurnEvent.Done(lastUsage);
    }

    /** streamOnce + 紧凑上下文管理（AUTO 路径）+ 锚点更新；updateAnchor=false 用于紧急重试（锚点已重置）。 */
    private StreamOutcome streamWithCompact(List<Message> history, int maxTokens, List<ToolDefinition> defs,
                                            String stable, String envText, String reminder, Mode mode,
                                            CancelToken cancel, BlockingQueue<TurnEvent> queue,
                                            AtomicReference<EventStream> currentStream,
                                            boolean updateAnchor) throws InterruptedException {
        // ch08：请求前上下文管理（compact == null 时跳过，兼容旧测试）
        if (compact != null) {
            boolean willSummarize = false;
            try {
                long anchor = compact.getUsageAnchor();
                int anchorLen = compact.getAnchorMsgLen();
                long est = Token.estimateTokens(anchor, history, anchorLen);
                willSummarize = est >= compact.contextWindow
                        - CompactConstants.SUMMARY_RESERVE - CompactConstants.AUTO_SAFETY_MARGIN;
                if (willSummarize) {
                    queue.put(new TurnEvent.Notice("正在压缩上下文..."));
                }
                ContextCompactor.Result r = ContextCompactor.manage(new ContextCompactor.Input(
                        history, provider, compact.contextWindow, defs,
                        compact.replacement(), compact.recovery(), compact.autoTracking(), compact.session(),
                        anchor, anchorLen, est, ContextCompactor.TriggerKind.AUTO));
                if (r.newMsgs() != null) {
                    history.clear();
                    history.addAll(r.newMsgs()); // Layer1 替换体 / Layer2 摘要历史写回（in-place，run 持有同一列表）
                }
                if (willSummarize) {
                    queue.put(new TurnEvent.Notice(String.format("已压缩，token 从 %d 降至 %d",
                            r.beforeTokens(), r.afterTokens())));
                }
            } catch (CompactException e) {
                if (willSummarize) {
                    queue.put(new TurnEvent.Notice("压缩失败: " + e.getMessage()));
                }
            }
        }

        StreamOutcome out = streamOnce(history, maxTokens, defs, stable, envText, reminder,
                cancel, queue, currentStream);
        // 锚点更新（F14/AC22）：主对话路径替换（非累加）；紧急重试路径锚点已重置不重复更新
        if (compact != null && updateAnchor && out.usage() != null
                && out.usage().inputTokens() != null) {
            compact.updateAnchor(Token.usageAnchor(out.usage()), history.size());
        }
        return out;
    }

    /** 紧急压缩（F25）：先 Layer1 落盘大结果，再强制摘要重建历史；成功返回 true。 */
    private boolean runEmergencyCompact(List<Message> history, List<ToolDefinition> defs, Mode mode,
                                        BlockingQueue<TurnEvent> queue) throws InterruptedException {
        if (compact == null) {
            return false;
        }
        try {
            ContextCompactor.Result r = ContextCompactor.manage(new ContextCompactor.Input(
                    history, provider, compact.contextWindow, defs,
                    compact.replacement(), compact.recovery(), compact.autoTracking(), compact.session(),
                    0, 0, Token.estimateTokens(0, history, 0),
                    ContextCompactor.TriggerKind.EMERGENCY));
            if (r.newMsgs() != null) {
                history.clear();
                history.addAll(r.newMsgs()); // 摘要后的新历史
            }
            compact.updateAnchor(0, 0); // 历史已重建，锚点重置（F25a）
            long est = Token.estimateTokens(0, history, 0);
            if (est >= compact.contextWindow - CompactConstants.MANUAL_SAFETY_MARGIN) {
                queue.put(new TurnEvent.Error("紧急压缩后仍超出上下文窗口，无法恢复"));
                return false;
            }
            return true;
        } catch (CompactException e) {
            queue.put(new TurnEvent.Error("紧急压缩失败: " + e.getMessage()));
            return false;
        }
    }

    /**
     * ch08 F19：ReadFile 成功后重读纯净内容记录到 recovery（恢复段数据源）。
     * 在工具结果回灌前同步执行（F19a）。
     */
    private void recordReadFiles(List<ToolCall> calls, List<ToolResult> results) {
        if (compact == null) {
            return;
        }
        for (int i = 0; i < calls.size() && i < results.size(); i++) {
            ToolCall call = calls.get(i);
            ToolResult r = results.get(i);
            if (!"ReadFile".equals(call.name()) || r.isError()) {
                continue;
            }
            try {
                Map<String, Object> args = JSON.readValue(call.arguments(),
                        new TypeReference<Map<String, Object>>() {
                        });
                Object pathObj = args.get("path");
                if (pathObj instanceof String path && !path.isBlank()) {
                    java.nio.file.Path abs = java.nio.file.Path.of(path)
                            .toAbsolutePath().normalize();
                    compact.recovery().recordFile(abs.toString(),
                            java.nio.file.Files.readString(abs));
                }
            } catch (Exception e) {
                // 读盘失败：recovery 缺一条无所谓（F19 降级）
            }
        }
    }

    /**
     * ch09 F35：本轮自然停下后按条件触发异步记忆更新——
     * 每 5 轮或本轮用户消息含显式记忆请求关键词（或关系）；失败静默不影响主会话（F42）。
     */
    private void triggerMemoryUpdate(List<Message> history) {
        if (memMgr == null) {
            return;
        }
        long turns = turnCount.incrementAndGet();
        // 最近一轮：从最后一条 user 到最终 assistant
        List<Message> recent = extractRecentTurn(history);
        boolean explicit = recent.stream()
                .anyMatch(m -> m.role() == Role.USER && dinocode.memory.Memory.Manager.hasMemorySignal(m.content()));
        if (turns % 5 == 0 || explicit) {
            memMgr.updateAsync(recent);
        }
    }

    /** 提取最近一轮（最后一条 user 起到末尾）。 */
    private static List<Message> extractRecentTurn(List<Message> history) {
        int start = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).role() == Role.USER) {
                start = i;
                break;
            }
        }
        return start < history.size() ? new ArrayList<>(history.subList(start, history.size())) : List.of();
    }

    /** 流失败内部信号：区分上下文超限（触发紧急压缩）与其他错误。 */
    private static final class StreamFailure extends RuntimeException {
        final String message;
        final boolean overflow;

        StreamFailure(String message, boolean overflow) {
            super(message, null, false, false);
            this.message = message;
            this.overflow = overflow;
        }
    }

    private TurnEvent finishCancelled(List<Message> history, BlockingQueue<TurnEvent> queue)
            throws InterruptedException {
        queue.put(new TurnEvent.Notice(AgentConstants.NOTICE_CANCELLED));
        ensureAssistantTail(history, AgentConstants.NOTICE_CANCELLED);
        return new TurnEvent.Done(Usage.UNKNOWN);
    }

    /** 一轮流式收集：转发文本/思考、收集工具调用、记录用量。 */
    private StreamOutcome streamOnce(List<Message> history, int maxTokens, List<ToolDefinition> defs,
                                     String stable, String envText, String reminder,
                                     CancelToken cancel, BlockingQueue<TurnEvent> queue,
                                     AtomicReference<EventStream> currentStream) throws InterruptedException {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        Usage usage = Usage.UNKNOWN;
        // reminder 不写入 history——只随本轮请求走（ch05 F6/N3）
        ChatRequest request = new ChatRequest(List.copyOf(history), maxTokens, defs, stable, envText, reminder);
        try (EventStream stream = provider.chat(request)) {
            currentStream.set(stream);
            ChatEvent event;
            while ((event = stream.next()) != null) {
                switch (event) {
                    case ChatEvent.TextDelta t -> {
                        queue.put(new TurnEvent.Text(t.text()));
                        text.append(t.text());
                    }
                    case ChatEvent.ThinkingDelta t -> queue.put(new TurnEvent.Thinking(t.text()));
                    case ChatEvent.ToolCallComplete tc -> calls.add(tc.call());
                    case ChatEvent.Done d -> usage = d.usage() == null ? Usage.UNKNOWN : d.usage();
                    case ChatEvent.Failure f -> {
                        // ch08：上下文超限由上层紧急压缩处理；其他错误照旧
                        if (f.kind() == ErrorKind.CONTEXT_OVERFLOW) {
                            throw new StreamFailure(f.message(), true);
                        }
                        throw new StreamFailure(f.message(), false);
                    }
                }
                if (cancel.isCancelled()) {
                    break;
                }
            }
        } finally {
            currentStream.set(null);
        }
        return new StreamOutcome(text.toString(), calls, usage, cancel.isCancelled());
    }

    /**
     * 保序分批并发执行（ch04 F5）+ 权限判定（ch06 F6/F9）：
     * 连续只读合批并发、有副作用单个串行，保持调用序。
     * 每个调用执行前过权限流水线：Allow 执行、Deny 回灌被拒结果（不中断）、Ask 落人在回路。
     */
    private BatchOutcome executeBatched(List<ToolCall> calls, Mode mode, CancelToken cancel,
                                        BlockingQueue<TurnEvent> queue) throws InterruptedException {
        ToolResult[] results = new ToolResult[calls.size()];
        boolean completed = true;
        int i = 0;

        while (i < calls.size()) {
            if (cancel.isCancelled()) {
                completed = false;
                break;
            }
            if (registry.isReadOnly(calls.get(i).name())) {
                int j = i;
                while (j < calls.size() && registry.isReadOnly(calls.get(j).name())) {
                    j++;
                }
                completed = executeReadOnlyBatch(calls, i, j, mode, results, cancel, queue);
                if (!completed) {
                    break;
                }
                i = j;
            } else {
                ToolCall call = calls.get(i);

                // ch06：执行前过权限流水线（Deny 回灌 / Ask 人在回路）
                PermissionEngine.CheckResult cr = engine.check(mode, call, false);
                if (cr.decision() == dinocode.permission.Decision.DENY) {
                    queue.put(new TurnEvent.ToolStart(call.name(), preview(call.arguments())));
                    results[i] = new ToolResult(call.id(), cr.reason(), true);
                    queue.put(new TurnEvent.ToolEnd(call.name(), cr.reason(), true));
                    i++;
                    continue;
                }
                if (cr.decision() == dinocode.permission.Decision.ASK) {
                    Outcome outcome = requestApproval(call, cr.reason(), queue);
                    if (outcome == null) { // 取消：走取消收尾
                        completed = false;
                        break;
                    }
                    switch (outcome) {
                        case DENY_ONCE -> {
                            queue.put(new TurnEvent.ToolStart(call.name(), preview(call.arguments())));
                            results[i] = new ToolResult(call.id(), "用户拒绝了本次" + call.name() + "调用", true);
                            queue.put(new TurnEvent.ToolEnd(call.name(), results[i].content(), true));
                            i++;
                            continue;
                        }
                        case ALLOW_FOREVER -> {
                            try {
                                engine.persistLocalAllow(call);
                            } catch (IOException e) {
                                queue.put(new TurnEvent.Notice("永久放行规则写入失败: " + e.getMessage()));
                            }
                        }
                        case ALLOW_ONCE -> {
                            // 不留记录，直接执行
                        }
                    }
                }

                queue.put(new TurnEvent.ToolStart(call.name(), preview(call.arguments())));
                results[i] = executeTool(call, cancel.withTimeout(ToolRegistry.DEFAULT_TIMEOUT));
                queue.put(new TurnEvent.ToolEnd(call.name(), results[i].content(), results[i].isError()));
                i++;
            }
        }

        if (!completed) {
            for (int k = 0; k < calls.size(); k++) {
                if (results[k] == null) {
                    results[k] = new ToolResult(calls.get(k).id(), AgentConstants.NOTICE_CANCELLED, true);
                }
            }
        }
        return new BatchOutcome(List.of(results), completed);
    }

    /**
     * 并发执行只读区间 [from, to)（ch04 F5）；ch06：批内逐个过权限检查，
     * 只读永不 Ask（N3）——Deny 预置被拒结果不纳入并发，其余照旧并发。
     * 返回 false 表示执行中被取消。
     */
    private boolean executeReadOnlyBatch(List<ToolCall> calls, int from, int to, Mode mode,
                                         ToolResult[] results, CancelToken cancel,
                                         BlockingQueue<TurnEvent> queue) throws InterruptedException {
        // 权限预判：Deny 预置结果，其余进入执行
        boolean[] denied = new boolean[to - from];
        for (int k = from; k < to; k++) {
            PermissionEngine.CheckResult cr = engine.check(mode, calls.get(k), true);
            if (cr.decision() == dinocode.permission.Decision.DENY) {
                denied[k - from] = true;
                results[k] = new ToolResult(calls.get(k).id(), cr.reason(), true);
            }
        }
        // Start 事件按序
        for (int k = from; k < to; k++) {
            ToolCall call = calls.get(k);
            queue.put(new TurnEvent.ToolStart(call.name(), preview(call.arguments())));
        }
        // 并发执行：每 worker 只写自己下标，无锁
        CountDownLatch latch = new CountDownLatch(to - from);
        for (int k = from; k < to; k++) {
            final int idx = k;
            if (denied[idx - from]) {
                latch.countDown(); // 被拒项不执行，直接放行 latch
                continue;
            }
            ToolCall call = calls.get(idx);
            CancelToken toolToken = cancel.withTimeout(ToolRegistry.DEFAULT_TIMEOUT);
            Thread.ofVirtual().start(() -> {
                try {
                    results[idx] = executeTool(call, toolToken);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        if (cancel.isCancelled()) {
            return false;
        }
        // End 事件按序
        for (int k = from; k < to; k++) {
            ToolCall call = calls.get(k);
            if (denied[k - from]) {
                queue.put(new TurnEvent.ToolEnd(call.name(), results[k].content(), true));
                continue;
            }
            if (results[k] == null) { // latch 已过仍未写入：超时兜底
                results[k] = new ToolResult(call.id(), "工具执行超时", true);
            }
            ToolResult r = results[k];
            queue.put(new TurnEvent.ToolEnd(call.name(), r.content(), r.isError()));
        }
        return true;
    }

    /**
     * 人在回路（ch06 F8 第五层）：发 Approval 事件并阻塞等 TUI 回传决策。
     *
     * @return 用户决策；{@code null} 表示等待中被取消（中断），由调用方走取消收尾
     */
    private Outcome requestApproval(ToolCall call, String reason, BlockingQueue<TurnEvent> queue)
            throws InterruptedException {
        ArrayBlockingQueue<Outcome> respond = new ArrayBlockingQueue<>(1);
        queue.put(new TurnEvent.Approval(new ApprovalRequest(
                call.name(), preview(call.arguments()), reason, respond)));
        // 测试脚本预置决策：直接消费，不经 TUI 回传
        if (scriptedOutcomes != null) {
            Outcome scripted = scriptedOutcomes.poll();
            if (scripted != null) {
                return scripted;
            }
        }
        try {
            Outcome outcome = respond.take();
            // 用户批准后工具行照常展示
            return outcome;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 单工具执行：30s 超时兜底（N1）。 */
    private ToolResult executeTool(ToolCall call, CancelToken toolToken) {
        Future<Result> future = EXECUTOR.submit(() -> registry.execute(call.name(), parseArgs(call.arguments())));
        try {
            Result r = future.get(ToolRegistry.DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return toolToken.isCancelled()
                    ? new ToolResult(call.id(), AgentConstants.NOTICE_CANCELLED, true)
                    : new ToolResult(call.id(), r.content(), r.isError());
        } catch (TimeoutException e) {
            future.cancel(true);
            return new ToolResult(call.id(), "工具执行超时", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult(call.id(), "工具执行被中断", true);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return new ToolResult(call.id(),
                    "工具执行异常: " + (cause == null ? e.getMessage() : cause.getMessage()), true);
        }
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 判断「整轮只产生未知工具调用」：全部未注册才 true，混入任一已注册即 false（F2-4）。 */
    private boolean allUnknown(List<ToolCall> calls) {
        return calls.stream().allMatch(c -> registry.get(c.name()).isEmpty());
    }

    private static String ensureFinal(String text) {
        return text == null || text.isBlank() ? "(模型未返回内容。)" : text;
    }

    /** 保证历史以 assistant 文本回合收尾（F6：取消/出错/上限后角色交替不破坏）。 */
    private static void ensureAssistantTail(List<Message> history, String fallback) {
        Optional<Role> last = history.isEmpty()
                ? Optional.empty()
                : Optional.of(history.get(history.size() - 1).role());
        if (last.isEmpty() || last.get() != Role.ASSISTANT) {
            history.add(Message.assistant(fallback));
        }
    }

    /** 工具行参数预览：截断 JSON 到 80 字符。 */
    private static String preview(String json) {
        String s = json == null || json.isBlank() ? "" : json;
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "…";
    }

    private record StreamOutcome(String text, List<ToolCall> calls, Usage usage, boolean failed) {
    }

    private record BatchOutcome(List<ToolResult> results, boolean completed) {
    }
}
