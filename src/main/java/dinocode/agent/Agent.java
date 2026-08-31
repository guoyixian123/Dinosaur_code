package dinocode.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dinocode.core.ChatEvent;
import dinocode.core.Message;
import dinocode.core.Role;
import dinocode.core.ToolCall;
import dinocode.core.ToolDefinition;
import dinocode.core.ToolResult;
import dinocode.core.Usage;
import dinocode.provider.ChatProvider;
import dinocode.provider.ChatRequest;
import dinocode.provider.EventStream;
import dinocode.prompt.Environment;
import dinocode.prompt.Prompt;
import dinocode.prompt.Reminder;
import dinocode.tool.Result;
import dinocode.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 */
public final class Agent {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** 规划模式完整提醒的重复间隔（ch05 F7）：首轮完整，之后每 4 轮重复一次完整，其余精简。 */
    static final int PLAN_REMINDER_INTERVAL = 4;

    private final ChatProvider provider;
    private final ToolRegistry registry;
    private final String version;

    public Agent(ChatProvider provider, ToolRegistry registry, String version) {
        this.provider = provider;
        this.registry = registry;
        this.version = version == null ? "" : version;
    }

    /**
     * @param mode   决定工具集与按轮次提醒（F7/F10）
     * @param cancel per-turn 取消句柄（F7）
     */
    public TurnStream run(List<Message> history, int maxTokens, Mode mode, CancelToken cancel) {
        List<ToolDefinition> defs = mode == Mode.PLAN
                ? registry.readOnlyDefinitions()
                : registry.definitions();
        // 稳定系统提示跨模式一致（规划提醒已移入 reminder 通道，ch05 F7）
        String stable = Prompt.buildSystemPrompt();
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

            StreamOutcome out = streamOnce(history, maxTokens, defs, stable, envText, reminder,
                    cancel, queue, currentStream);
            if (out.failed) {
                if (cancel.isCancelled()) {
                    return finishCancelled(history, queue);
                }
                queue.put(new TurnEvent.Notice(AgentConstants.NOTICE_STREAM_ERR));
                ensureAssistantTail(history, AgentConstants.NOTICE_STREAM_ERR);
                return new TurnEvent.Error(AgentConstants.NOTICE_STREAM_ERR);
            }
            lastUsage = out.usage;
            queue.put(new TurnEvent.UsageReport(out.usage));

            // 无工具调用：纯文本即最终答复（自然完成，F2-1）
            if (out.calls().isEmpty()) {
                history.add(Message.assistant(ensureFinal(out.text())));
                return new TurnEvent.Done(out.usage);
            }

            history.add(Message.assistantWithTools(out.text(), out.calls()));

            // 连续未知工具计数（F2-4）
            if (allUnknown(out.calls())) {
                unknownRun++;
            } else {
                unknownRun = 0;
            }

            BatchOutcome batch = executeBatched(out.calls(), cancel, queue);
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
                    case ChatEvent.Failure f -> queue.put(new TurnEvent.Error(f.message()));
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
     * 保序分批并发执行（F5）：连续只读合批并发，有副作用单个串行，保持调用序。
     * 事件顺序：Start 按序、End 按序，并发只发生在执行环节（N3）。
     */
    private BatchOutcome executeBatched(List<ToolCall> calls, CancelToken cancel, BlockingQueue<TurnEvent> queue)
            throws InterruptedException {
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
                completed = executeReadOnlyBatch(calls, i, j, results, cancel, queue);
                if (!completed) {
                    break;
                }
                i = j;
            } else {
                ToolCall call = calls.get(i);
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

    /** 并发执行只读区间 [from, to)；返回 false 表示执行中被取消。 */
    private boolean executeReadOnlyBatch(List<ToolCall> calls, int from, int to, ToolResult[] results,
                                         CancelToken cancel, BlockingQueue<TurnEvent> queue)
            throws InterruptedException {
        // Start 事件按序
        for (int k = from; k < to; k++) {
            ToolCall call = calls.get(k);
            queue.put(new TurnEvent.ToolStart(call.name(), preview(call.arguments())));
        }
        // 并发执行：每 worker 只写自己下标，无锁
        CountDownLatch latch = new CountDownLatch(to - from);
        for (int k = from; k < to; k++) {
            final int idx = k;
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
            if (results[k] == null) { // latch 已过仍未写入：超时兜底
                results[k] = new ToolResult(call.id(), "工具执行超时", true);
            }
            ToolResult r = results[k];
            queue.put(new TurnEvent.ToolEnd(call.name(), r.content(), r.isError()));
        }
        return true;
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
