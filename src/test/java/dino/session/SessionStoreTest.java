package dino.session;

import dino.core.Message;
import dino.core.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断言依据：checklist §C（会话文件结构、损坏容错、恢复最新）。
 */
class SessionStoreTest {

    @TempDir
    Path tmp;

    private Session session(String id, long lastActive, List<Message> messages) {
        return new Session(id, lastActive, new java.util.ArrayList<>(messages), SessionSettings.EMPTY);
    }

    @Test
    void saveThenLoadRoundTrip() throws IOException {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session s = session("s-1", 100L, List.of(
                new Message(Role.USER, "你好"),
                new Message(Role.ASSISTANT, "你好！")));
        s.setSettings(new SessionSettings(16384));
        store.save(s);

        Path file = tmp.resolve("sessions/s-1.json");
        assertTrue(Files.exists(file));
        // checklist §C 的 grep 依据：消息以小写 role 键序列化
        String json = Files.readString(file);
        assertTrue(json.contains("\"role\""), json);
        assertTrue(json.contains("\"user\""), json);

        SessionStore.LoadResult result = store.loadLatest();
        assertNotNull(result.session());
        assertEquals("s-1", result.session().getId());
        assertEquals(2, result.session().getMessages().size());
        assertEquals(Role.USER, result.session().getMessages().get(0).role());
        assertEquals("你好", result.session().getMessages().get(0).content());
        assertEquals(Integer.valueOf(16384), result.session().getSettings().maxTokensOverride());
        assertFalse(result.hadCorrupt());
    }

    @Test
    void picksMostRecentlyActive() throws IOException {
        SessionStore store = new SessionStore(tmp);
        store.save(session("s-old", 100L, List.of(new Message(Role.USER, "旧"))));
        store.save(session("s-new", 200L, List.of(new Message(Role.USER, "新"))));

        SessionStore.LoadResult result = store.loadLatest();
        assertEquals("s-new", result.session().getId());
    }

    @Test
    void corruptFileSkippedWithFlag() throws IOException {
        SessionStore store = new SessionStore(tmp);
        store.save(session("s-good", 100L, List.of(new Message(Role.USER, "好的"))));
        Files.writeString(tmp.resolve("s-bad.json"), "{这不是合法 JSON");

        SessionStore.LoadResult result = store.loadLatest();
        assertNotNull(result.session());
        assertEquals("s-good", result.session().getId());
        assertTrue(result.hadCorrupt());
    }

    @Test
    void missingDirMeansNoSession() {
        SessionStore store = new SessionStore(tmp.resolve("不存在"));
        SessionStore.LoadResult result = store.loadLatest();
        assertNull(result.session());
        assertFalse(result.hadCorrupt());
    }

    @Test
    void saveCreatesDir() throws IOException {
        Path nested = tmp.resolve("a/b/c");
        SessionStore store = new SessionStore(nested);
        store.save(session("s-1", 1L, List.of()));
        assertTrue(Files.exists(nested.resolve("s-1.json")));
    }

    @Test
    void newSessionIdUnique() {
        String a = SessionStore.newSessionId();
        String b = SessionStore.newSessionId();
        assertTrue(a.startsWith("s-"));
        assertFalse(a.equals(b));
    }
}
