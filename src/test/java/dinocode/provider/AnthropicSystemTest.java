package dinocode.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dinocode.core.Message;
import dinocode.core.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anthropic 系统通道序列化单测（ch05 F3/AC4）：稳定块带 cache_control 断点、环境块不带。
 */
class AnthropicSystemTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 反射调用包内私有 toSystem，直接断言序列化结果。 */
    private static JsonNode toSystemJson(ChatRequest request) throws Exception {
        Method m = AnthropicProvider.class.getDeclaredMethod("toSystem", ChatRequest.class);
        m.setAccessible(true);
        return JSON.readTree(m.invoke(null, request).toString());
    }

    @Test
    void stableBlockHasCacheControlEnvironmentBlockDoesNot() throws Exception {
        ChatRequest req = new ChatRequest(
                List.of(Message.user("你好")), 4096,
                List.of(new ToolDefinition("ReadFile", "读", Map.of("type", "object"))),
                "稳定系统提示",
                "环境信息：\n工作目录: /tmp",
                "");

        JsonNode system = toSystemJson(req);

        assertEquals(2, system.size());
        JsonNode stable = system.get(0);
        assertEquals("稳定系统提示", stable.path("text").asText());
        assertEquals("ephemeral", stable.path("cache_control").path("type").asText()); // 断点挂在稳定块
        JsonNode env = system.get(1);
        assertEquals("环境信息：\n工作目录: /tmp", env.path("text").asText());
        assertTrue(env.path("cache_control").isMissingNode(), "环境块不应有 cache_control"); // AC4 回归守护
    }

    @Test
    void emptySegmentsAreOmitted() throws Exception {
        ChatRequest req = new ChatRequest(
                List.of(Message.user("你好")), 4096, List.of(), "", "只有环境段", "");

        JsonNode system = toSystemJson(req);

        assertEquals(1, system.size());
        assertEquals("只有环境段", system.get(0).path("text").asText());
        assertTrue(system.get(0).path("cache_control").isMissingNode());
    }

    @Test
    void reminderAppendedToLastUserMessageWithoutConsecutiveUsers() throws Exception {
        // 历史：user → assistant(tool_use) → tool_result(user)；reminder 应并入末条 user content
        ChatRequest req = new ChatRequest(
                List.of(
                        Message.user("你好"),
                        Message.assistantWithTools("", List.of(new dinocode.core.ToolCall("t1", "ReadFile", "{}"))),
                        Message.tool(List.of(new dinocode.core.ToolResult("t1", "内容", false)))),
                4096, List.of(), "稳定", "", "补充提醒");

        Method m = AnthropicProvider.class.getDeclaredMethod("toMessages", List.class, String.class);
        m.setAccessible(true);
        JsonNode messages = JSON.readTree(m.invoke(null, req.history(), req.reminder()).toString());

        // 不产生连续 user（N3）：末条 user 消息含两个 content 块（tool_result + reminder 文本）
        JsonNode last = messages.get(messages.size() - 1);
        assertEquals("user", last.path("role").asText());
        assertEquals(2, last.path("content").size());
        assertEquals("tool_result", last.path("content").get(0).path("type").asText());
        assertEquals("text", last.path("content").get(1).path("type").asText());
        assertEquals("补充提醒", last.path("content").get(1).path("text").asText());
        for (int i = 1; i < messages.size(); i++) {
            assertFalse(messages.get(i - 1).path("role").asText().equals("user")
                    && messages.get(i).path("role").asText().equals("user"), "出现连续 user 消息");
        }
    }

    @Test
    void reminderAfterAssistantTailStartsNewUserMessage() throws Exception {
        // 历史以 assistant 收尾 → 新起一条 user（N3）
        ChatRequest req = new ChatRequest(
                List.of(Message.user("你好"), Message.assistant("先前答复")),
                4096, List.of(), "稳定", "", "补充提醒");

        Method m = AnthropicProvider.class.getDeclaredMethod("toMessages", List.class, String.class);
        m.setAccessible(true);
        JsonNode messages = JSON.readTree(m.invoke(null, req.history(), req.reminder()).toString());

        JsonNode last = messages.get(messages.size() - 1);
        assertEquals("user", last.path("role").asText());
        assertEquals("补充提醒", last.path("content").asText());
    }

    @Test
    void emptyReminderInjectsNothing() throws Exception {
        ChatRequest req = new ChatRequest(
                List.of(Message.user("你好")), 4096, List.of(), "稳定", "", "");

        Method m = AnthropicProvider.class.getDeclaredMethod("toMessages", List.class, String.class);
        m.setAccessible(true);
        JsonNode messages = JSON.readTree(m.invoke(null, req.history(), req.reminder()).toString());

        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("你好", messages.get(0).path("content").asText());
    }

    @Test
    void reminderOnPlainTextUserTailUpgradesStringContentToArray() throws Exception {
        // 修复回归：PLAN 模式首轮——末条是字符串 content 的 user 消息（真实最高频形态）。
        // 修复前直接 withArray 抛 UnsupportedOperationException，用户看到「错误: null」。
        ChatRequest req = new ChatRequest(
                List.of(Message.user("帮我重构这个模块")),
                4096, List.of(), "稳定", "", "PLAN REMINDER");

        Method m = AnthropicProvider.class.getDeclaredMethod("toMessages", List.class, String.class);
        m.setAccessible(true);
        JsonNode messages = JSON.readTree(m.invoke(null, req.history(), req.reminder()).toString());

        assertEquals(1, messages.size(), "不应新起消息，应并入末条 user");
        JsonNode last = messages.get(0);
        assertEquals("user", last.path("role").asText());
        assertTrue(last.path("content").isArray(), "字符串 content 应被升为数组");
        assertEquals(2, last.path("content").size());
        assertEquals("text", last.path("content").get(0).path("type").asText());
        assertEquals("帮我重构这个模块", last.path("content").get(0).path("text").asText());
        assertEquals("PLAN REMINDER", last.path("content").get(1).path("text").asText());
    }
}
