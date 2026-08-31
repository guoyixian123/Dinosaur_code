package dinocode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动笔记单测（ch09 AC21/AC22/AC26 + F35 关键词）。
 */
class MemoryTest {

    @TempDir
    Path projectDir;

    @TempDir
    Path userDir;

    // ---------- Store CRUD（F40/AC21/AC22） ----------

    @Test
    void createNoteWritesFileAndIndex() throws Exception {
        Memory.Store store = new Memory.Store(projectDir);
        store.apply("project", List.of(new Memory.UpdateAction(
                "create", "project", "project_knowledge", "API 约定", "api_conventions",
                "本项目 REST 接口统一 /api/v1 前缀。", null)));

        Path note = projectDir.resolve("project_knowledge_api_conventions.md");
        assertTrue(Files.exists(note)); // AC21：文件存在
        String body = Files.readString(note, StandardCharsets.UTF_8);
        assertTrue(body.contains("type: project_knowledge")); // frontmatter
        assertTrue(body.contains("title: API 约定"));
        assertTrue(body.contains("created:"));
        assertTrue(body.contains("/api/v1"));

        String index = store.loadIndex();
        assertTrue(index.contains("[project_knowledge] API 约定")); // AC22：索引行
    }

    @Test
    void updateNoteRewritesFileAndIndex() throws Exception {
        Memory.Store store = new Memory.Store(projectDir);
        store.apply("project", List.of(new Memory.UpdateAction(
                "create", "project", "project_knowledge", "旧标题", "api",
                "旧内容", null)));
        store.apply("project", List.of(new Memory.UpdateAction(
                "update", "project", null, "新标题", null, "新内容",
                "project_knowledge_api.md")));

        String body = Files.readString(projectDir.resolve("project_knowledge_api.md"));
        assertTrue(body.contains("新内容"));
        assertTrue(body.contains("新标题"));
        assertFalse(body.contains("旧内容"));
        assertTrue(store.loadIndex().contains("新标题"));
    }

    @Test
    void deleteNoteRemovesFileAndIndexLine() throws Exception {
        Memory.Store store = new Memory.Store(projectDir);
        store.apply("project", List.of(new Memory.UpdateAction(
                "create", "project", "reference_material", "参考资料", "docs",
                "链接", null)));
        String file = "reference_material_docs.md";
        assertTrue(Files.exists(projectDir.resolve(file)));

        store.apply("project", List.of(new Memory.UpdateAction(
                "delete", "project", null, null, null, null, file)));

        assertFalse(Files.exists(projectDir.resolve(file)));
        assertFalse(store.loadIndex().contains(file));
    }

    @Test
    void createIgnoresWrongLevel() {
        Memory.Store store = new Memory.Store(projectDir);
        store.apply("project", List.of(new Memory.UpdateAction(
                "create", "user", "user_preference", "标题", "slug", "内容", null)));
        // level=user 的操作不落到 project store
        assertTrue(store.loadIndex().isEmpty());
    }

    @Test
    void duplicateSlugNotOverwritten() {
        Memory.Store store = new Memory.Store(projectDir);
        store.apply("project", List.of(new Memory.UpdateAction(
                "create", "project", "project_knowledge", "第一版", "x", "内容一", null)));
        store.apply("project", List.of(new Memory.UpdateAction(
                "create", "project", "project_knowledge", "第二版", "x", "内容二", null)));
        // 同名已存在不覆盖（F41 语义：去重交 LLM，此处防御）
        try {
            String body = Files.readString(projectDir.resolve("project_knowledge_x.md"));
            assertTrue(body.contains("内容一"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ---------- Manager（F32~F34/AC23/AC26） ----------

    @Test
    void loadIndexMergesProjectFirst() {
        Memory.Store p = new Memory.Store(projectDir);
        Memory.Store u = new Memory.Store(userDir);
        p.apply("project", List.of(new Memory.UpdateAction(
                "create", "project", "project_knowledge", "项目知识", "k", "内容", null)));
        u.apply("user", List.of(new Memory.UpdateAction(
                "create", "user", "user_preference", "用户偏好", "pref", "内容", null)));

        Memory.Manager mgr = new Memory.Manager(projectDir, userDir);
        String index = mgr.loadIndex();
        assertTrue(index.indexOf("项目知识") < index.indexOf("用户偏好")); // 项目级在前（F32）
    }

    @Test
    void loadIndexTruncatesOver25KB() throws Exception {
        StringBuilder big = new StringBuilder();
        while (big.length() < 30 * 1024) {
            big.append("- [user_preference] 条目 — 描述\n");
        }
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("MEMORY.md"), big.toString(), StandardCharsets.UTF_8);

        Memory.Manager mgr = new Memory.Manager(projectDir, userDir);
        String index = mgr.loadIndex(); // AC26
        assertTrue(index.getBytes(StandardCharsets.UTF_8).length <= 25 * 1024 + 32);
        assertTrue(index.endsWith("(index truncated)"));
    }

    @Test
    void emptyDirsGiveEmptyIndex() {
        Memory.Manager mgr = new Memory.Manager(projectDir, userDir);
        assertTrue(mgr.loadIndex().isEmpty());
    }

    // ---------- 关键词检测（F35-②） ----------

    @Test
    void memorySignalDetection() {
        assertTrue(Memory.Manager.hasMemorySignal("请记住我喜欢简洁回复"));
        assertTrue(Memory.Manager.hasMemorySignal("remember this"));
        assertTrue(Memory.Manager.hasMemorySignal("别忘了我不用 tab"));
        assertFalse(Memory.Manager.hasMemorySignal("今天天气怎么样"));
        assertFalse(Memory.Manager.hasMemorySignal(null));
    }

    @Test
    void updateAsyncWithNullProviderIsNoop() {
        Memory.Manager mgr = new Memory.Manager(projectDir, userDir);
        mgr.updateAsync(List.of(dinocode.core.Message.user("记住什么"))); // provider 未设置：静默返回
        assertTrue(mgr.loadIndex().isEmpty());
    }
}
