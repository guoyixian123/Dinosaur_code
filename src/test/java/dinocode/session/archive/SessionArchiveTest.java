package dinocode.session.archive;

import dinocode.core.Message;
import dinocode.core.ToolCall;
import dinocode.core.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话存档单测（ch09 AC7~AC10/AC14/AC15/AC19/AC20）。
 */
class SessionArchiveTest {

    @TempDir
    Path root;

    private Path sessionsDir() throws Exception {
        Path dir = root.resolve(".dino").resolve("sessions");
        Files.createDirectories(dir);
        return dir;
    }

    private Path newSessionDir(String id) throws Exception {
        Path dir = sessionsDir().resolve(id);
        Files.createDirectories(dir);
        return dir;
    }

    private static void writeJsonl(Path dir, String... lines) throws Exception {
        Files.write(dir.resolve("conversation.jsonl"),
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- Writer（AC8） ----------

    @Test
    void appendWritesJsonlLines() throws Exception {
        Path dir = newSessionDir("20260601-143022-a1b2");
        try (Writer w = Writer.create(dir)) {
            w.append(Message.user("你好"), "gpt-x", true);
            w.append(Message.assistant("你好！"), null, false);
        }
        List<String> lines = Files.readAllLines(dir.resolve("conversation.jsonl"), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"role\":\"user\""));
        assertTrue(lines.get(0).contains("\"model\":\"gpt-x\"")); // 首条带 model
        assertTrue(lines.get(0).contains("\"ts\":"));
        assertFalse(lines.get(1).contains("model")); // 非首条不带
    }

    @Test
    void compactMarkerThenAppendAll() throws Exception {
        Path dir = newSessionDir("20260601-143022-a1b2");
        try (Writer w = Writer.create(dir)) {
            w.append(Message.user("旧消息"), null, false);
            w.writeCompactMarker();
            w.appendAll(List.of(Message.user("新历史"), Message.assistant("回复")));
        }
        List<Message> loaded = SessionArchive.load(dir); // AC9：只加载 compact 后的
        assertEquals(2, loaded.size());
        assertEquals("新历史", loaded.get(0).content());
    }

    // ---------- SessionLoader（AC14/AC15） ----------

    @Test
    void loadSkipsBadLines() throws Exception {
        Path dir = newSessionDir("20260601-150000-a1b2");
        writeJsonl(dir,
                "{\"role\":\"user\",\"content\":\"第一条\",\"ts\":1}",
                "这不是 JSON{{{",
                "{\"role\":\"assistant\",\"content\":\"第二条\",\"ts\":2}");
        List<Message> msgs = SessionArchive.load(dir);
        assertEquals(2, msgs.size());
        assertEquals("第一条", msgs.get(0).content());
    }

    @Test
    void loadTruncatesOrphanedToolCall() throws Exception {
        Path dir = newSessionDir("20260601-160000-a1b2");
        try (Writer w = Writer.create(dir)) {
            w.append(Message.user("问题"), null, false);
            w.append(Message.assistantWithTools("",
                    List.of(new ToolCall("c1", "ReadFile", "{}"))), null, false);
            // 没有 tool 消息 → 孤立
        }
        List<Message> msgs = SessionArchive.load(dir); // AC15
        assertEquals(1, msgs.size());
        assertEquals("user", roleOf(msgs.get(0)));
    }

    private static String roleOf(Message m) {
        return switch (m.role()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }

    // ---------- SessionList（AC12/N3） ----------

    @Test
    void listReturnsNewFormatSortedByMtimeDesc() throws Exception {
        Path old = newSessionDir("20260501-100000-aaaa");
        Path recent = newSessionDir("20260601-100000-bbbb");
        Path newest = newSessionDir("20260601-120000-cccc");
        writeJsonl(old, "{\"role\":\"user\",\"content\":\"旧会话\",\"ts\":1}");
        Thread.sleep(20);
        writeJsonl(recent, "{\"role\":\"user\",\"content\":\"较早\",\"ts\":1}");
        Thread.sleep(20);
        writeJsonl(newest, "{\"role\":\"user\",\"content\":\"最新\",\"ts\":1}");

        List<SessionArchive.SessionInfo> list = SessionArchive.list(sessionsDir());
        assertEquals(3, list.size());
        assertEquals("最新", list.get(0).title());
        assertEquals("较早", list.get(1).title());
        assertEquals("旧会话", list.get(2).title());
        assertTrue(list.get(0).size() > 0);
    }

    @Test
    void listSkipsOldFormatAndDirsWithoutJsonl() throws Exception {
        newSessionDir("1717000000-abc12345"); // ch08 旧格式
        newSessionDir("20260601-100000-dddd"); // 无 jsonl
        Path valid = newSessionDir("20260601-110000-eeee");
        writeJsonl(valid, "{\"role\":\"user\",\"content\":\"有效\",\"ts\":1}");

        List<SessionArchive.SessionInfo> list = SessionArchive.list(sessionsDir());
        assertEquals(1, list.size());
        assertEquals("有效", list.get(0).title());
    }

    // ---------- SessionCleaner（AC19/AC20） ----------

    @Test
    void cleanExpiredDeletesOnlyOldNewFormat() throws Exception {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
        Path expired = newSessionDir(now.minusDays(40).format(fmt) + "-ffff"); // 超 30 天
        writeJsonl(expired, "{\"role\":\"user\",\"content\":\"x\",\"ts\":1}");
        Path fresh = newSessionDir(now.minusDays(1).format(fmt) + "-7777"); // 1 天前，保留
        writeJsonl(fresh, "{\"role\":\"user\",\"content\":\"y\",\"ts\":1}");
        Path legacy = newSessionDir("1717000000-abc12345"); // 旧格式不清理
        writeJsonl(legacy, "{\"role\":\"user\",\"content\":\"z\",\"ts\":1}");

        SessionArchive.cleanExpired(sessionsDir(), Duration.ofDays(30));

        assertFalse(Files.exists(expired));    // AC19
        assertTrue(Files.exists(fresh));
        assertTrue(Files.exists(legacy));      // AC20 新格式保护
    }

    // ---------- SessionContext ID 格式（AC7） ----------

    @Test
    void newSessionIdFormat() {
        String id = dinocode.compact.state.SessionContext.newSessionIdForTest();
        assertTrue(id.matches("\\d{8}-\\d{6}-[0-9a-f]{4}"), "格式应为 YYYYMMDD-HHMMSS-xxxx，实际: " + id);
        assertTrue(dinocode.compact.state.SessionContext.isParsable(id));
        assertFalse(dinocode.compact.state.SessionContext.isParsable("1717000000-abc12345"));
    }

    // ---------- 时间跨度 ----------

    @Test
    void relativeTimeFormatting() {
        assertEquals("刚刚", SessionArchive.relativeTime(java.time.Instant.now()));
        assertEquals("2 小时前", SessionArchive.relativeTime(
                java.time.Instant.now().minus(Duration.ofHours(2))));
        assertEquals("3 天前", SessionArchive.relativeTime(
                java.time.Instant.now().minus(Duration.ofDays(3))));
    }
}
