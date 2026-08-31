package dinocode.permission;

import dinocode.permission.Matchers.MatcherCompileException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匹配器扩展单测（ch12 F1~F5/AC1~AC3/N7）：exact/regex/not/glob 四种类型与嵌套。
 */
class MatchersTest {

    private static Matcher compile(String p) throws Exception {
        return Matchers.compile(p, true);
    }

    @Test
    void exactMatchesWholeStringOnly() throws Exception {
        Matcher m = compile("=git status");
        assertTrue(m.match("git status")); // AC1
        assertFalse(m.match("git status -s"));
        assertFalse(m.match("git  status"));
        assertEquals("=git status", m.describe());
    }

    @Test
    void regexMatchesWithFind() throws Exception {
        Matcher m = compile("~^npm (install|test)$");
        assertTrue(m.match("npm install")); // AC2
        assertTrue(m.match("npm test"));
        assertFalse(m.match("npm run dev"));
    }

    @Test
    void invalidRegexFailsCompilation() {
        Matchers.MatcherCompileException e = assertThrows(MatcherCompileException.class,
                () -> Matchers.compile("~^npm (install", true)); // AC2 未闭合
        assertTrue(e.getMessage().contains("regex 编译失败"));
    }

    @Test
    void notNegatesInner() throws Exception {
        Matcher m = compile("!~^rm");
        assertFalse(m.match("rm -rf .")); // AC3：rm 起头不命中
        assertTrue(m.match("ls -lh"));    // AC3：非 rm 命中
    }

    @Test
    void notNestsAllTypes() throws Exception {
        assertTrue(compile("!=git status").match("git push")); // !exact
        assertFalse(compile("!=git status").match("git status"));
        assertTrue(compile("!git *").match("npm i")); // !glob
        assertFalse(compile("!git *").match("git push"));
    }

    @Test
    void notRequiresInner() {
        assertThrows(MatcherCompileException.class, () -> Matchers.compile("!", true));
    }

    @Test
    void barePatternIsGlobDefault() throws Exception {
        Matcher m = compile("git *");
        assertTrue(m.match("git status")); // F5 向后兼容
        assertFalse(m.match("npm i"));
        assertTrue(m.match("git ")); // * 匹配空序列
    }

    @Test
    void emptyPatternMatchesAll() throws Exception {
        assertTrue(compile("").match("anything"));
    }

    @Test
    void nullTargetSafe() throws Exception {
        assertFalse(compile("=x").match(null));
        assertFalse(compile("~a").match(null));
    }
}
