package dinocode.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统提示装配单测（ch05 AC1/AC2/AC5/AC7）：优先级排序、空槽跳过、缓存确定性、双重强化。
 */
class PromptTest {

    @Test
    void assemblesInPriorityOrderSeparatedByBlankLine() {
        String prompt = Prompt.buildSystemPrompt();

        // 身份段在工具使用段之前（AC1）
        assertTrue(prompt.indexOf("Dino Code") < prompt.indexOf("ReadFile/Glob/Grep"));
        // 模块间以空行分隔、无连续空行（AC1/AC2）
        assertFalse(prompt.contains("\n\n\n"));
        // 七个固定模块正文全部出现
        for (String marker : new String[]{
                "Dino Code", "api_key", "不要每步都停下", "有副作用", "ReadFile/Glob/Grep",
                "不奉承", "Markdown"}) {
            assertTrue(prompt.contains(marker), "缺少模块标记: " + marker);
        }
    }

    @Test
    void mountNewModuleWithoutChangingAssemblyLogic() {
        Module extra = new Module("测试模块", 45, "插入的动作执行补充");
        String withExtra = Prompt.assembleSystem(List.of(
                new Module("甲", 40, "甲内容"),
                extra,
                new Module("乙", 50, "乙内容")));

        // 新模块按优先级插入中间——挂载即扩展（AC1）
        assertTrue(withExtra.indexOf("甲内容") < withExtra.indexOf("插入的动作执行补充"));
        assertTrue(withExtra.indexOf("插入的动作执行补充") < withExtra.indexOf("乙内容"));
    }

    @Test
    void skipsEmptyContentModules() {
        String prompt = Prompt.assembleSystem(List.of(
                new Module("有内容", 10, "正文"),
                new Module("空槽", 20, ""),
                new Module("null", 30, null),
                new Module("有内容2", 40, "正文2")));

        assertEquals("正文\n\n正文2", prompt); // 空槽跳过、不留多余空行（AC2）
    }

    @Test
    void buildSystemPromptIsByteIdenticalAcrossCalls() {
        // N1 缓存确定性：连续两次构造逐字节相等
        assertEquals(Prompt.buildSystemPrompt(), Prompt.buildSystemPrompt());
        // 稳定块不含任何随轮次/环境变化的成分（AC5）——用环境段的渲染格式精确匹配
        String prompt = Prompt.buildSystemPrompt();
        for (String envMarker : new String[]{
                System.getProperty("user.dir"), "工作目录:", "当前日期:", "git 状态:", "平台:", "环境信息"}) {
            assertFalse(prompt.contains(envMarker), "稳定块混入环境内容: " + envMarker);
        }
    }

    @Test
    void doubleReinforcementPresentInSystemPromptAndToolDescriptions() {
        String prompt = Prompt.buildSystemPrompt();

        // F5/AC7：优先专用工具 + 编辑前必先读，系统提示双重表述
        assertTrue(prompt.contains("优先使用 ReadFile/Glob/Grep"));
        assertTrue(prompt.contains("不要用 Bash 拼凑"));
        assertTrue(prompt.contains("必须先用 ReadFile 读取目标文件"));

        // 工具描述侧同义强化
        assertTrue(new dinocode.tool.BashTool().description().contains("优先用 ReadFile/Glob/Grep"));
        assertTrue(new dinocode.tool.EditFileTool().description().contains("先用 ReadFile 读取目标文件"));
    }
}
