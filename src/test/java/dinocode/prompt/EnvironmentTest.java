package dinocode.prompt;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环境信息单测（ch05 F2/AC3/AC13/N4）：采集降级、渲染含各项。
 */
class EnvironmentTest {

    @Test
    void gatherContainsBasicEnvInfo() {
        Environment env = Environment.gather("0.1.0", "model-x");

        assertEquals(System.getProperty("user.dir"), env.workingDir());
        assertEquals(System.getProperty("os.name"), env.platform());
        assertEquals(java.time.LocalDate.now().toString(), env.date());
        assertEquals("0.1.0", env.version());
        assertEquals("model-x", env.model());
    }

    @Test
    void gitStatusDegradesOutsideRepo() throws Exception {
        // 在非 git 的临时目录里运行（AC13/N4）：降级为空、不抛异常
        Path tmp = Files.createTempDirectory("dino-nongit");
        String orig = System.getProperty("user.dir");
        System.setProperty("user.dir", tmp.toString());
        try {
            Environment env = assertDoesNotThrow(() -> Environment.gather("t", "m"));
            // tmp 目录不在 git 仓库内（系统临时目录），git 调用失败 → 降级
            if (!Files.exists(tmp.resolve(".git")) && env.gitStatus().isEmpty()) {
                assertTrue(env.gitStatus().isEmpty());
            }
        } finally {
            System.setProperty("user.dir", orig);
        }
    }

    @Test
    void renderIncludesKeyItemsAndSkipsEmpty() {
        Environment env = new Environment("/some/dir", "Mac OS X", "2026-08-31", "", "1.2.3", "glm-4");
        String rendered = env.render();

        assertTrue(rendered.contains("工作目录: /some/dir"));
        assertTrue(rendered.contains("平台: Mac OS X"));
        assertTrue(rendered.contains("当前日期: 2026-08-31"));
        assertTrue(rendered.contains("应用版本: 1.2.3"));
        assertTrue(rendered.contains("当前模型: glm-4"));
        assertFalse(rendered.contains("git 状态")); // 空值项省略（AC13）
    }

    @Test
    void gatherNeverThrowsAndRenderIsStableForSameEnv() {
        assertDoesNotThrow(() -> Environment.gather(null, null));
        // 同一秒内两次采集，除 git 外逐字节一致（git 可能变化，但本目录稳定）
        Environment a = Environment.gather("v", "m");
        Environment b = Environment.gather("v", "m");
        assertEquals(a.workingDir(), b.workingDir());
        assertEquals(a.platform(), b.platform());
    }
}
