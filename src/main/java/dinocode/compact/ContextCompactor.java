package dinocode.compact;

import dinocode.compact.state.AutoCompactTrackingState;
import dinocode.compact.state.ContentReplacementState;
import dinocode.compact.state.SessionContext;
import dinocode.core.Message;
import dinocode.core.Role;
import dinocode.core.ToolCall;
import dinocode.core.ToolDefinition;
import dinocode.core.ToolResult;
import dinocode.provider.ChatProvider;
import dinocode.provider.ChatRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 上下文压缩编排（ch08）：两层防御 + 手动/紧急入口。
 *
 * <p>Layer1（预防）：每轮请求前对工具结果做幂等的「超阈值落盘 + 预览替换」，决策冻结保证缓存稳定。
 * Layer2（急救）：估算 token 触阈（或手动/紧急触发）时跑一次 LLM 摘要，产出 9 部分摘要
 * + 三段恢复 + 近期原文，整体替换对话历史。
 */
public final class ContextCompactor {

    /** 压缩触发来源。 */
    public enum TriggerKind {AUTO, MANUAL, EMERGENCY}

    /** 压缩执行参数（由调用方一次性组装）。 */
    public record Input(
            List<Message> messages,
            ChatProvider provider,
            int contextWindow,
            List<ToolDefinition> toolDefs,
            ContentReplacementState replacement,
            Recovery.RecoveryState recovery,
            AutoCompactTrackingState autoTracking,
            SessionContext session,
            long usageAnchor,
            int anchorMsgLen,
            long estimatedToken,
            TriggerKind trigger) {
    }

    /** 压缩结果：新消息列表（仅 Layer2 时非 null，调用方负责 replaceMessages）+ before/after 估算 token。 */
    public record Result(List<Message> newMsgs, long beforeTokens, long afterTokens) {
        public static final Result NOOP = new Result(null, 0, 0);
    }

    private ContextCompactor() {
    }

    // ==================== 主入口（F6/F21/F22/F25） ====================

    /**
     * 统一入口。
     * AUTO：先 Layer1 → 用 Layer1 后的列表重估 → 过阈值且未熔断才 Layer2。
     * MANUAL：跳过 Layer1/阈值/熔断，直接 Layer2。
     * EMERGENCY：先强制 Layer1（防摘要请求自身撞 PTL）→ Layer2。
     * 返回 (before, after)；仅 Layer1 时 after 为 Layer1 后的重估值。
     */
    public static Result manage(Input in) throws CompactException {
        return switch (in.trigger()) {
            case MANUAL -> {
                CompactResult r = forceCompact(in, in.messages());
                yield new Result(r.newMsgs(), in.estimatedToken(), r.afterTok());
            }
            case EMERGENCY -> {
                List<Message> layer1Out = offloadAndSnip(in.messages(), in.replacement(), in.session());
                CompactResult r = forceCompact(in, layer1Out);
                yield new Result(r.newMsgs(), in.estimatedToken(), r.afterTok());
            }
            case AUTO -> {
                // 1. Layer1 预防性落盘替换
                List<Message> layer1Out = offloadAndSnip(in.messages(), in.replacement(), in.session());
                // 2. 用 Layer1 后的列表重估（不能用入参估算，否则 Layer1 的节省不反映）
                long est = Token.estimateTokens(in.usageAnchor(), layer1Out, in.anchorMsgLen());
                // 3. sanity check：contextWindow 太小时跳过自动 Layer2 防死循环
                if (in.contextWindow() <= CompactConstants.SUMMARY_RESERVE + CompactConstants.AUTO_SAFETY_MARGIN) {
                    System.err.println("[compact] warn: contextWindow 过小(" + in.contextWindow()
                            + ")，跳过自动摘要");
                    yield new Result(layer1Out, in.estimatedToken(), est);
                }
                long threshold = in.contextWindow()
                        - CompactConstants.SUMMARY_RESERVE - CompactConstants.AUTO_SAFETY_MARGIN;
                if (est < threshold || in.autoTracking().tripped()) {
                    yield new Result(layer1Out, in.estimatedToken(), est); // 仅 Layer1 生效
                }
                // 4. 自动 Layer2
                CompactResult r = autoCompact(in, layer1Out);
                yield new Result(r.newMsgs(), in.estimatedToken(), r.afterTok());
            }
        };
    }

    // ==================== Layer 1（F1~F6） ====================

    /**
     * 遍历 RoleTool 消息，对超阈值工具结果做「落盘 + 预览替换」；决策冻结在账本里。
     * 纯函数风格：不修改入参，返回新列表。落盘失败降级为保留原文（N6）。
     */
    public static List<Message> offloadAndSnip(List<Message> msgs, ContentReplacementState state,
                                               SessionContext session) {
        List<Message> out = new ArrayList<>(msgs.size());
        boolean changed = false;
        for (Message m : msgs) {
            if (m.role() != Role.TOOL || m.toolResults().isEmpty()) {
                out.add(m);
                continue;
            }
            List<ToolResult> results = m.toolResults();
            List<ToolResult> resolved = new ArrayList<>(results);
            List<Integer> candidates = new ArrayList<>();
            // 第一遍：已冻结的 id 直接用账本存量结果（不重新构造预览，F5d）
            for (int i = 0; i < results.size(); i++) {
                ToolResult r = results.get(i);
                if (state.seen(r.toolCallId())) {
                    String frozen = state.decideOnce(r.toolCallId(), r.content(),
                            ContentReplacementState.DecisionResult::kept); // 已 Seen：返回存量
                    if (!frozen.equals(r.content())) {
                        resolved.set(i, new ToolResult(r.toolCallId(), frozen, r.isError()));
                        changed = true;
                    }
                } else {
                    candidates.add(i); // 未决策：进候选（此时不碰账本）
                }
            }
            // 第二遍：候选按字节倒序，先单条阈值再聚合预算（F1/F2/F2a）
            candidates.sort((a, b) -> Integer.compare(
                    SummaryPrompt.utf8Length(results.get(b).content()),
                    SummaryPrompt.utf8Length(results.get(a).content())));
            long aggregate = 0;
            for (int idx : candidates) {
                aggregate += SummaryPrompt.utf8Length(results.get(idx).content());
            }
            for (int idx : candidates) {
                ToolResult r = results.get(idx);
                int bytes = SummaryPrompt.utf8Length(r.content());
                boolean mustSpill = bytes > CompactConstants.SINGLE_RESULT_LIMIT
                        || aggregate > CompactConstants.MESSAGE_AGGREGATE_LIMIT;
                if (!mustSpill) {
                    state.decideOnce(r.toolCallId(), r.content(),
                            ContentReplacementState.DecisionResult::kept); // 冻结为 KEPT
                    continue;
                }
                String decided = state.decideOnce(r.toolCallId(), r.content(), () -> {
                    try {
                        spillSingle(session, r.toolCallId(), r.content());
                    } catch (IOException e) {
                        return ContentReplacementState.DecisionResult.skip(); // 落盘失败：不写账本，下轮重试
                    }
                    Path spillPath = session.spillDir().resolve(r.toolCallId());
                    return ContentReplacementState.DecisionResult.replaced(
                            buildPreview(bytes, headPreview(r.content()), spillPath));
                });
                if (!decided.equals(r.content())) { // 替换生效（REPLACED）
                    resolved.set(idx, new ToolResult(r.toolCallId(), decided, r.isError()));
                    changed = true;
                }
                // SKIP（落盘失败）：保持原文、不写账本；KEPT 不可能出现在 mustSpill 分支
                aggregate -= bytes; // 已决策过的项不再计入聚合预算（F5c）
            }
            out.add(new Message(m.role(), m.content(), m.toolCalls(), List.copyOf(resolved)));
        }
        return changed ? out : msgs;
    }

    /** 落盘单条工具结果到 spillDir/&lt;toolUseId&gt;；幂等（已存在不重写，AC3）。 */
    static void spillSingle(SessionContext session, String toolUseId, String content) throws IOException {
        Path path = session.spillDir().resolve(toolUseId);
        if (Files.exists(path)) {
            return;
        }
        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    /** 预览体头部：先按行截到 20 行，再按字节截到 2048（F4 择短）。 */
    static String headPreview(String content) {
        String[] lines = content.split("\n", CompactConstants.PREVIEW_HEAD_LINES + 1);
        String head = lines.length > CompactConstants.PREVIEW_HEAD_LINES
                ? String.join("\n", java.util.Arrays.copyOfRange(lines, 0, CompactConstants.PREVIEW_HEAD_LINES))
                : content;
        byte[] bytes = head.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CompactConstants.PREVIEW_HEAD_BYTES) {
            head = new String(bytes, 0, CompactConstants.PREVIEW_HEAD_BYTES, StandardCharsets.UTF_8);
        }
        return head;
    }

    /** 替换体四要素：原始字节数 + 头部预览 + 落盘路径 + 重读提示（F4/AC1）。 */
    static String buildPreview(int originalBytes, String head, Path spillPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("[content offloaded] original size: ").append(originalBytes).append(" bytes\n");
        sb.append("[saved to] ").append(spillPath).append('\n');
        sb.append("[head preview]\n");
        sb.append(head).append('\n');
        sb.append("完整内容已保存到上述路径，如需查看请用文件读取工具读取该路径，不要凭头部预览猜测全文。");
        return sb.toString();
    }

    // ==================== Layer 2（F7~F12, F25~F29） ====================

    private record CompactResult(List<Message> newMsgs, long afterTok) {
    }

    /** 自动摘要：整轮失败计熔断、成功清零（F28/AC19）。 */
    private static CompactResult autoCompact(Input in, List<Message> msgs) throws CompactException {
        try {
            CompactResult r = runSummary(in, msgs);
            in.autoTracking().recordSuccess();
            return r;
        } catch (CompactException e) {
            in.autoTracking().recordFailure();
            throw e;
        }
    }

    /** 手动/紧急路径：跳过熔断器，失败不计入（F29）。 */
    private static CompactResult forceCompact(Input in, List<Message> msgs) throws CompactException {
        return runSummary(in, msgs);
    }

    /** 摘要 + 恢复 + 近期原文拼接（F9~F12/F15~F17）。 */
    private static CompactResult runSummary(Input in, List<Message> msgs) throws CompactException {
        // 入口拍快照：整个 runSummary 生命周期只用这一份（F16 快照一致性）
        List<Recovery.FileReadRecord> snapshot = in.recovery().snapshot();
        String summaryText;
        try {
            summaryText = summarizeOnce(in, msgs);
        } catch (PromptTooLongException e) {
            summaryText = ptlRetry(in, msgs, e); // F27：摘要自身 PTL 的统一处理
        } catch (IOException e) {
            throw new CompactException("摘要请求失败: " + e.getMessage(), e);
        }
        String recoveryText = Recovery.buildRecoveryAttachment(snapshot, in.toolDefs());
        String combined = "## 历史会话摘要\n" + summaryText + "\n\n" + recoveryText;
        Message summaryAndRecovery = Message.user(combined);

        List<Message> recentTail = pickRecentTail(msgs);
        List<Message> newMsgs = joinAfterSummary(summaryAndRecovery, recentTail);
        long afterTok = Token.estimateTokens(0, newMsgs, 0);
        return new CompactResult(newMsgs, afterTok);
    }

    /** 单次摘要请求：无工具定义（F8/AC6）；返回解析后的 &lt;summary&gt; 正文。 */
    static String summarizeOnce(Input in, List<Message> msgs) throws PromptTooLongException, IOException {
        ChatRequest req = new ChatRequest(SummaryPrompt.buildSummaryPrompt(msgs),
                8192, List.of(), "", "", "");
        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder text = new StringBuilder();
        Thread worker = Thread.ofVirtual().start(() -> {
            try (var stream = in.provider().chat(req)) {
                dinocode.core.ChatEvent event;
                while ((event = stream.next()) != null) {
                    switch (event) {
                        case dinocode.core.ChatEvent.TextDelta t -> text.append(t.text());
                        case dinocode.core.ChatEvent.Failure f -> {
                            if (f.kind() == dinocode.core.ErrorKind.CONTEXT_OVERFLOW) {
                                future.completeExceptionally(new PromptTooLongException(f.message()));
                            } else {
                                future.completeExceptionally(new IOException(f.message()));
                            }
                            return;
                        }
                        default -> {
                        }
                    }
                }
                future.complete(text.toString());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        String raw;
        try {
            raw = future.get(120, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("摘要请求超时", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof PromptTooLongException p) {
                throw p;
            }
            throw new IOException(cause.getMessage(), cause);
        }
        return SummaryPrompt.extractSummary(raw);
    }

    /** F27：摘要自身 PTL 的丢消息组重试（自动/手动/紧急共用）。 */
    static String ptlRetry(Input in, List<Message> msgs, Throwable firstErr) throws CompactException {
        List<List<Message>> groups = new ArrayList<>(groupByUserTurn(msgs).stream().map(ArrayList::new).toList());
        int directRetries = 0;
        Throwable lastErr = firstErr;
        while (!groups.isEmpty()) {
            if (directRetries < CompactConstants.PTL_RETRY_LIMIT) {
                groups.remove(0); // 丢最旧 1 组
                directRetries++;
            } else {
                int drop = Math.max(1, (int) Math.ceil(groups.size() * CompactConstants.PTL_DROP_PERCENTAGE));
                for (int i = 0; i < drop && !groups.isEmpty(); i++) {
                    groups.remove(0);
                }
            }
            List<Message> remaining = flatten(groups);
            if (remaining.isEmpty()) {
                break; // 不发送空消息摘要请求（F27）
            }
            try {
                return summarizeOnce(in, remaining);
            } catch (PromptTooLongException e) {
                lastErr = e;
            } catch (IOException e) {
                throw new CompactException("摘要请求失败: " + e.getMessage(), e); // 非 PTL 立即上抛
            }
        }
        throw new CompactException("摘要请求持续超长，PTL 重试用光", lastErr);
    }

    private static List<Message> flatten(List<List<Message>> groups) {
        List<Message> out = new ArrayList<>();
        for (List<Message> g : groups) {
            out.addAll(g);
        }
        return out;
    }

    /** F27 分组：用户提交 → 一组 assistant/tool 往返。 */
    static List<List<Message>> groupByUserTurn(List<Message> msgs) {
        List<List<Message>> groups = new ArrayList<>();
        for (Message m : msgs) {
            if (m.role() == Role.USER || groups.isEmpty()) {
                groups.add(new ArrayList<>());
            }
            groups.get(groups.size() - 1).add(m);
        }
        return groups;
    }

    /**
     * 近期原文保留（F11/F12/AC8）：从尾部倒序累加，累计 token ≥ 10000 且条数 ≥ 5 后停手；
     * 截断点夹在 tool_use/tool_result 中间时前推到工具调用之前。
     */
    static List<Message> pickRecentTail(List<Message> msgs) {
        if (msgs.isEmpty()) {
            return List.of();
        }
        long tokens = 0;
        int count = 0;
        int start = msgs.size();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            tokens += (long) Math.ceil(Token.messageChars(List.of(m))
                    / CompactConstants.ESTIMATE_CHARS_PER_TOKEN);
            count++;
            start = i;
            if (tokens >= CompactConstants.RECENT_KEEP_TOKENS
                    && count >= CompactConstants.RECENT_KEEP_MESSAGES) {
                break; // 两个下界都满足才停（F11 择宽语义）
            }
        }
        // 配对修正：起点落在 TOOL 上（落单 tool_result）→ 前推到上一个 assistant
        while (start > 0 && msgs.get(start).role() == Role.TOOL) {
            start--;
        }
        return new ArrayList<>(msgs.subList(start, msgs.size()));
    }

    /** 摘要 user 消息与近期原文拼接：避免 user/user 连续（F12/Anthropic 协议约束）。 */
    static List<Message> joinAfterSummary(Message summaryAndRecovery, List<Message> recent) {
        if (recent.isEmpty()) {
            return List.of(summaryAndRecovery);
        }
        List<Message> out = new ArrayList<>();
        out.add(summaryAndRecovery);
        if (recent.get(0).role() == Role.USER) {
            out.add(Message.assistant("(已加载上下文摘要与恢复信息。请继续。)"));
        }
        out.addAll(recent);
        return out;
    }
}
