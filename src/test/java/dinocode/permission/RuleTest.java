package dinocode.permission;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则解析与匹配单测（ch06 F3/AC3/AC4）。
 */
class RuleTest {

    @Test
    void parsesPatternedAndBareRules() {
        Rule r1 = Rule.parse("Bash(git *)", true).orElseThrow();
        assertEquals("Bash", r1.tool());
        assertEquals("git *", r1.pattern());
        assertTrue(r1.allow());

        Rule r2 = Rule.parse("Read", false).orElseThrow();
        assertEquals("Read", r2.tool());
        assertEquals("", r2.pattern());
        assertFalse(r2.allow());

        // 非法输入
        assertTrue(Rule.parse("", true).isEmpty());
        assertTrue(Rule.parse("Bash(git", true).isEmpty());
        assertTrue(Rule.parse("(x)", true).isEmpty());
        assertTrue(Rule.parse(null, true).isEmpty());
    }

    @Test
    void commandGlobMatching() {
        assertTrue(Rule.matchPattern("git *", "git status"));
        assertTrue(Rule.matchPattern("git *", "git push origin main"));
        assertFalse(Rule.matchPattern("git *", "npm install"));
        assertTrue(Rule.matchPattern("git status", "git status"));
        assertFalse(Rule.matchPattern("git status", "git push"));
        assertTrue(Rule.matchPattern("", "anything"));
        // ** 在命令串中等价 *
        assertTrue(Rule.matchPattern("git **", "git push"));
    }

    @Test
    void pathGlobMatching() {
        assertTrue(Rule.matchPattern("src/**", "src/a/b.java"));
        assertTrue(Rule.matchPattern("src/**", "src/x"));
        assertFalse(Rule.matchPattern("src/**", "docs/x"));
        assertTrue(Rule.matchPattern("src/*.java", "src/A.java"));
        assertFalse(Rule.matchPattern("src/*.java", "src/sub/A.java")); // * 不跨段
        assertTrue(Rule.matchPattern("**", "any/deep/path"));
    }

    @Test
    void ruleSetDenyWinsOverAllowWithinLayer() {
        RuleSet set = new RuleSet(
                List.of(Rule.parse("Bash(git *)", true).orElseThrow()),
                List.of(Rule.parse("Bash(git push)", false).orElseThrow()));

        assertEquals(Optional.of(Decision.ALLOW), set.match("Bash", "git status"));
        assertEquals(Optional.of(Decision.DENY), set.match("Bash", "git push"));
        assertEquals(Optional.empty(), set.match("Bash", "npm install"));
        assertEquals(Optional.empty(), set.match("Read", "src/a")); // 工具名不匹配
    }

    @Test
    void friendlyNameRouting() {
        assertEquals("Bash", Mappings.friendlyName("Bash"));
        assertEquals("Read", Mappings.friendlyName("ReadFile"));
        assertEquals("Write", Mappings.friendlyName("WriteFile"));
        assertEquals("Edit", Mappings.friendlyName("EditFile"));
        assertEquals("Glob", Mappings.friendlyName("Glob"));
        assertEquals("Grep", Mappings.friendlyName("Grep"));
        assertEquals("Mystery", Mappings.friendlyName("Mystery")); // 未知原样
    }

    @Test
    void categorizeIsSafeByDefault() {
        assertEquals(Category.READ, Mappings.categorize("ReadFile", true));
        assertEquals(Category.READ, Mappings.categorize("未知工具", true)); // readOnly 优先
        assertEquals(Category.WRITE, Mappings.categorize("WriteFile", false));
        assertEquals(Category.WRITE, Mappings.categorize("EditFile", false));
        assertEquals(Category.EXEC, Mappings.categorize("Bash", false));
        assertEquals(Category.EXEC, Mappings.categorize("未知工具", false)); // N7 最严
    }
}
