package dinocode.compact;

import dinocode.core.Message;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Token 估算（ch08 F13/F14/AC22）：锚定最近一次真实 usage，之后按字符/3.5 增量估算。
 * 纯函数，不依赖 provider。
 */
public final class Token {

    private Token() {
    }

    /** 把 provider 返回的 usage 合并成单一锚点值（input + output + cacheRead + cacheWrite）。 */
    public static long usageAnchor(dinocode.core.Usage u) {
        if (u == null) {
            return 0;
        }
        return n(u.inputTokens()) + n(u.outputTokens()) + n(u.cacheRead()) + n(u.cacheWrite());
    }

    private static long n(Integer v) {
        return v == null ? 0 : v;
    }

    /**
     * 估算当前对话的 token 总量 = anchor + 锚点之后新增消息的字符增量。
     *
     * @param anchor      上一次主对话 Stream 的真实 usage 之和
     * @param allMsgs     当前完整消息列表（应为 layer1 处理后的列表，否则估算偏高）
     * @param anchorMsgLen anchor 记录时的消息条数（锚点已涵盖前缀，只算其后增量）
     */
    public static long estimateTokens(long anchor, List<Message> allMsgs, int anchorMsgLen) {
        int safeStart = Math.min(Math.max(anchorMsgLen, 0), allMsgs.size());
        List<Message> tail = allMsgs.subList(safeStart, allMsgs.size());
        long chars = messageChars(tail);
        return anchor + (long) Math.ceil(chars / CompactConstants.ESTIMATE_CHARS_PER_TOKEN);
    }

    /** 消息列表字节总量：content + 工具调用参数 + 工具结果内容。 */
    static long messageChars(List<Message> msgs) {
        long total = 0;
        for (Message m : msgs) {
            total += bytes(m.content());
            for (dinocode.core.ToolCall c : m.toolCalls()) {
                total += bytes(c.arguments());
            }
            for (dinocode.core.ToolResult r : m.toolResults()) {
                total += bytes(r.content());
            }
        }
        return total;
    }

    private static long bytes(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }
}
