package dinocode.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置加载降级与目标提取单测（ch06 F4/N5/AC6 + N7/AC15）。
 */
class SettingsTest {

    @TempDir
    Path dir;

    @Test
    void missingFileLoadsAsEmpty() {
        Settings s = assertDoesNotThrow(() -> Settings.load(dir.resolve("不存在.yaml")));
        assertEquals(Settings.EMPTY, s);
    }

    @Test
    void malformedYamlDegradesToEmpty() throws Exception {
        Path bad = dir.resolve("bad.yaml");
        Files.writeString(bad, "{{{{ 不是 YAML ]]]]");
        assertEquals(Settings.EMPTY, Settings.load(bad));
    }

    @Test
    void nonMappingYamlDegradesToEmpty() throws Exception {
        Path list = dir.resolve("list.yaml");
        Files.writeString(list, "- a\n- b\n");
        assertEquals(Settings.EMPTY, Settings.load(list));
    }

    @Test
    void loadsRulesAndDefaultMode() throws Exception {
        Path ok = dir.resolve("ok.yaml");
        Files.writeString(ok, """
                defaultMode: acceptEdits
                permissions:
                  allow:
                    - "Bash(git *)"
                    - "Read"
                  deny:
                    - "Bash(git push)"
                """);
        Settings s = Settings.load(ok);
        assertEquals("acceptEdits", s.defaultMode());
        assertEquals(List.of("Bash(git *)", "Read"), s.allow());
        assertEquals(List.of("Bash(git push)"), s.deny());

        RuleSet rules = s.toRuleSet();
        assertEquals(java.util.Optional.of(Decision.ALLOW), rules.match("Bash", "git status"));
        assertEquals(java.util.Optional.of(Decision.DENY), rules.match("Bash", "git push"));
    }

    @Test
    void toRuleSetSkipsIllegalEntries() {
        Settings s = new Settings(null, List.of("Bash(git status)", "非法(((", ""), List.of());
        RuleSet rules = s.toRuleSet();
        assertEquals(java.util.Optional.of(Decision.ALLOW), rules.match("Bash", "git status"));
        assertEquals(java.util.Optional.empty(), rules.match("非法(((", ""));
    }

    @Test
    void extractTargetPerTool() {
        assertEquals(new Mappings.TargetInfo("src/A.java", true, true),
                Mappings.extractTarget(new dinocode.core.ToolCall("t", "WriteFile", "{\"path\":\"src/A.java\"}")));
        assertEquals(new Mappings.TargetInfo("cmd", false, true),
                Mappings.extractTarget(new dinocode.core.ToolCall("t", "Bash", "{\"command\":\"cmd\"}")));
        // glob/grep：搜索根 path，空 → "."
        assertEquals(new Mappings.TargetInfo(".", true, true),
                Mappings.extractTarget(new dinocode.core.ToolCall("t", "Glob", "{\"pattern\":\"**\"}")));
        // 解析失败 → ok=false
        assertFalse(Mappings.extractTarget(new dinocode.core.ToolCall("t", "WriteFile", "{bad json")).ok());
        assertFalse(Mappings.extractTarget(new dinocode.core.ToolCall("t", "WriteFile", "{}")).ok());
        assertFalse(Mappings.extractTarget(new dinocode.core.ToolCall("t", "Mystery", "{}")).ok());
        // bash 缺 command → 不命中黑名单但落 Ask（N7）
        assertEquals(new Mappings.TargetInfo("", false, false),
                Mappings.extractTarget(new dinocode.core.ToolCall("t", "Bash", "{}")));
    }
}
