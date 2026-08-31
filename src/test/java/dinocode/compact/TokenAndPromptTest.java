package dinocode.compact;

import dinocode.core.Message;
import dinocode.core.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Token 估算与摘要 Prompt 单测（ch08 AC7/AC22）。
 */
class TokenAndPromptTest {

    // ---------- Token（AC22） ----------

    @Test
    void estimateTokensPureCharsWhenNoAnchor() {
        Message m = Message.user("a".repeat(350)); // 350 字节 / 3.5 = 100 token
        assertEquals(100, Token.estimateTokens(0, List.of(m), 0));
        assertEquals(0, Token.estimateTokens(0, List.of(), 0));
    }

    @Test
    void estimateTokensCountsOnlyTailAfterAnchor() {
        Message m1 = Message.user("a".repeat(350));  // 100 token
        Message m2 = Message.user("b".repeat(700)); // 200 token
        // anchor=1000 已涵盖 m1 → 只算 m2
        assertEquals(1000 + 200, Token.estimateTokens(1000, List.of(m1, m2), 1));
    }

    @Test
    void estimateTokensAnchorMsgLenOutOfBoundsSafe() {
        Message m = Message.user("x");
        // start 钳制到 size → tail 空 → 只剩 anchor
        assertEquals(500, Token.estimateTokens(500, List.of(m), 5));
        // 负数钳制到 0 → 整条消息计增量，ceil(1/3.5)=1
        assertEquals(501, Token.estimateTokens(500, List.of(m), -3));
    }

    @Test
    void usageAnchorSumsAllFields() {
        assertEquals(10 + 20 + 30 + 40, Token.usageAnchor(new dinocode.core.Usage(10, 20, 30, 40)));
        assertEquals(0, Token.usageAnchor(null));
        assertEquals(7, Token.usageAnchor(new dinocode.core.Usage(7, null, null, null)));
    }

    @Test
    void toolResultBytesCounted() {
        Message tool = Message.tool(List.of(new ToolResult("t", "z".repeat(3500), false))); // 1000 token
        assertEquals(1000, Token.estimateTokens(0, List.of(tool), 0));
    }

    // ---------- SummaryPrompt（AC7） ----------

    @Test
    void buildSummaryPromptIsSingleUserMessageWithNineSections() {
        List<Message> prompt = SummaryPrompt.buildSummaryPrompt(
                List.of(Message.user("你好"), Message.assistant("好的")));
        assertEquals(1, prompt.size());
        assertEquals(dinocode.core.Role.USER, prompt.get(0).role());
        String text = prompt.get(0).content();
        assertTrue(SummaryPrompt.hasNineSections(text));
        assertTrue(text.contains("<analysis>"));
        assertTrue(text.contains("<summary>"));
        assertTrue(text.contains("不要调用任何工具"));
        assertTrue(text.contains("你好")); // 对话内容被嵌入
    }

    @Test
    void serializeConversationIsDeterministic() {
        List<Message> msgs = List.of(
                Message.user("问题"),
                Message.assistantWithTools("我来查", List.of(new dinocode.core.ToolCall("c1", "ReadFile", "{\"path\":\"a\"}"))),
                Message.tool(List.of(new ToolResult("c1", "内容", false))));
        String s1 = SummaryPrompt.serializeConversation(msgs);
        String s2 = SummaryPrompt.serializeConversation(msgs);
        assertEquals(s1, s2);
        assertTrue(s1.contains("user: 问题"));
        assertTrue(s1.contains("[call ReadFile id=c1"));
        assertTrue(s1.contains("[result id=c1 isError=false] 内容"));
    }

    @Test
    void extractSummaryHandlesStandardMissingAndNested() {
        assertEquals("正文", SummaryPrompt.extractSummary("草稿<summary>正文</summary>尾巴"));
        assertEquals("没有标签", SummaryPrompt.extractSummary("没有标签"));
        // 嵌套时取最后一对
        assertEquals("第二", SummaryPrompt.extractSummary(
                "<summary>第一</summary>中间<summary>第二</summary>"));
        assertEquals("", SummaryPrompt.extractSummary(null));
    }

    @Test
    void pickRecentTailRequiresBothBounds() {
        // 6 条短消息（各 ~1 token）：条数到 5 但 token 不够 10000 → 全部保留（两个下界都要满足）
        List<Message> shortMsgs = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            shortMsgs.add(Message.user("m" + i));
        }
        List<Message> tail = ContextCompactor.pickRecentTail(shortMsgs);
        assertEquals(6, tail.size()); // 永远达不到 10000 token → 全保留

        // 一条超大消息（35000 字节 = 10000 token）+ 后续 4 条：token 满足但条数不足 → 继续累加
        List<Message> mixed = new java.util.ArrayList<>();
        mixed.add(Message.user("x".repeat(35000)));
        for (int i = 0; i < 4; i++) {
            mixed.add(Message.user("m" + i));
        }
        // 尾部 4 条只有 4 条 < 5 条下界 → 全保留
        assertEquals(5, ContextCompactor.pickRecentTail(mixed).size());
    }

    @Test
    void pickRecentTailDoesNotSplitToolPair() {
        // 尾部构造：user(大) → assistant(tool_call) → tool(大)，让截断恰好落在 tool 上
        List<Message> msgs = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            msgs.add(Message.user("u" + i));
        }
        msgs.add(Message.assistantWithTools("", List.of(new dinocode.core.ToolCall("c1", "ReadFile", "{}"))));
        msgs.add(Message.tool(List.of(new ToolResult("c1", "x".repeat(35000), false)))); // 10000 token
        List<Message> tail = ContextCompactor.pickRecentTail(msgs);
        // 起点若落单 tool_result → 前推到 assistant（AC8）
        assertFalse(tail.get(0).role() == dinocode.core.Role.TOOL);
    }

    @Test
    void groupByUserTurnGroupsCorrectly() {
        List<Message> msgs = List.of(
                Message.user("u1"),
                Message.assistant("a1"),
                Message.tool(List.of(new ToolResult("c", "r", false))),
                Message.user("u2"),
                Message.assistant("a2"));
        List<List<Message>> groups = ContextCompactor.groupByUserTurn(msgs);
        assertEquals(2, groups.size());
        assertEquals(3, groups.get(0).size());
        assertEquals(2, groups.get(1).size());
    }

    @Test
    void joinAfterSummaryAvoidsConsecutiveUsers() {
        Message summary = Message.user("摘要");
        // recent 首条 user → 插 assistant 占位
        List<Message> out = ContextCompactor.joinAfterSummary(summary,
                List.of(Message.user("新消息")));
        assertEquals(3, out.size());
        assertEquals(dinocode.core.Role.USER, out.get(0).role());
        assertEquals(dinocode.core.Role.ASSISTANT, out.get(1).role());
        assertEquals(dinocode.core.Role.USER, out.get(2).role());

        // recent 空 → 只有摘要
        assertEquals(1, ContextCompactor.joinAfterSummary(summary, List.of()).size());

        // recent 首条 assistant → 正常拼接
        assertEquals(2, ContextCompactor.joinAfterSummary(summary,
                List.of(Message.assistant("答"))).size());
    }
}
