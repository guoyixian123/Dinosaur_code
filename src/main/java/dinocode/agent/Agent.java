package dinocode.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dinocode.core.ChatEvent;
import dinocode.core.Message;
import dinocode.core.ToolCall;
import dinocode.core.ToolResult;
import dinocode.core.Usage;
import dinocode.provider.ChatProvider;
import dinocode.provider.ChatRequest;
import dinocode.provider.EventStream;
import dinocode.tool.Result;
import dinocode.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单轮闭环编排（F5/F6）：
 * 请求#1（带工具）→ 收集工具调用 → 执行 → 结果回灌 → 请求#2（续答）→ 最终文本 → 停（AC9 单轮上限）。
 * run() 在 virtual thread 上跑整条链路，对外吐阻塞式 {@link TurnStream}。
 */
public final class Agent {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ChatProvider provider;
    private final ToolRegistry registry;

    public Agent(ChatProvider provider, ToolRegistry registry) {
        this.provider = provider;
        this.registry = registry;
    }

    public TurnStream run(List<Message> history, int maxTokens) {
        BlockingQueue<TurnEvent> queue = new LinkedBlockingQueue<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<EventStream> currentStream = new AtomicReference<>();

        Thread.ofVirtual().start(() -> {
            TurnEvent terminal;
            try {
                terminal = doTurn(history, maxTokens, queue, closed, currentStream);
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
                closed.set(true);
                EventStream s = currentStream.get();
                if (s != null) {
                    s.close(); // 关闭底层连接，解除生成循环的阻塞
                }
            }
        };
    }

    private TurnEvent doTurn(List<Message> history, int maxTokens, BlockingQueue<TurnEvent> queue,
                             AtomicBoolean closed, AtomicReference<EventStream> currentStream)
            throws InterruptedException {
        // 请求#1
        List<ToolCall> calls = new ArrayList<>();
        StringBuilder preamble = new StringBuilder();
        Usage usage = streamOnce(history, maxTokens, queue, currentStream, preamble, calls);
        if (closed.get()) {
            return new TurnEvent.Done(Usage.UNKNOWN);
        }
        if (calls.isEmpty()) {
            if (!preamble.isEmpty()) {
                history.add(Message.assistant(preamble.toString()));
            }
            return new TurnEvent.Done(usage);
        }

        // 有工具调用：回灌 + 顺序执行
        history.add(Message.assistantWithTools(preamble.toString(), calls));
        List<ToolResult> results = new ArrayList<>();
        for (ToolCall call : calls) {
            queue.put(new TurnEvent.ToolStart(call.name(), preview(call.arguments())));
            Result r = executeTool(call);
            queue.put(new TurnEvent.ToolEnd(call.name(), r.content(), r.isError()));
            results.add(new ToolResult(call.id(), r.content(), r.isError()));
        }
        if (closed.get()) {
            return new TurnEvent.Done(Usage.UNKNOWN);
        }
        history.add(Message.tool(results));

        // 请求#2（续答）；忽略再次出现的工具调用（单轮，AC9）
        StringBuilder finalText = new StringBuilder();
        List<ToolCall> ignored = new ArrayList<>();
        Usage finalUsage = streamOnce(history, maxTokens, queue, currentStream, finalText, ignored);
        if (!finalText.isEmpty()) {
            history.add(Message.assistant(finalText.toString()));
        }
        return new TurnEvent.Done(finalUsage);
    }

    /** 流式收一轮回复；文本/思考转发到 queue，工具调用收集到 calls；返回 Done 的 usage（无则 UNKNOWN）。 */
    private Usage streamOnce(List<Message> history, int maxTokens, BlockingQueue<TurnEvent> queue,
                             AtomicReference<EventStream> currentStream,
                             StringBuilder textAccumulator, List<ToolCall> calls)
            throws InterruptedException {
        Usage usage = Usage.UNKNOWN;
        ChatRequest request = new ChatRequest(List.copyOf(history), maxTokens, registry.definitions());
        try (EventStream stream = provider.chat(request)) {
            currentStream.set(stream);
            ChatEvent event;
            while ((event = stream.next()) != null) {
                switch (event) {
                    case ChatEvent.TextDelta t -> {
                        queue.put(new TurnEvent.Text(t.text()));
                        textAccumulator.append(t.text());
                    }
                    case ChatEvent.ThinkingDelta t -> queue.put(new TurnEvent.Thinking(t.text()));
                    case ChatEvent.ToolCallComplete tc -> calls.add(tc.call());
                    case ChatEvent.Done d -> usage = d.usage() == null ? Usage.UNKNOWN : d.usage();
                    case ChatEvent.Failure f -> queue.put(new TurnEvent.Error(f.message()));
                }
            }
        } finally {
            currentStream.set(null);
        }
        return usage;
    }

    private Result executeTool(ToolCall call) {
        Map<String, Object> args = parseArgs(call.arguments());
        Future<Result> future = EXECUTOR.submit(() -> registry.execute(call.name(), args));
        try {
            return future.get(ToolRegistry.DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return Result.error("工具执行超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("工具执行被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return Result.error("工具执行异常: " + (cause == null ? e.getMessage() : cause.getMessage()));
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

    /** 工具行参数预览：截断 JSON 到 80 字符。 */
    private static String preview(String json) {
        String s = json == null || json.isBlank() ? "" : json;
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "…";
    }
}
