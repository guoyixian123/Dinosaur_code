package dinocode.tui;

import dinocode.command.Command;
import dinocode.command.CommandRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * 斜杠命令自动补全（ch10 F24~F32c）：JLine 原生 Completer 机制。
 * 输入以 "/" 开头时按命令主名做前缀匹配，展示「命令名 + 描述」两列候选；
 * Tab 触发展示与循环选择，回车确认——键位交互全部由 JLine 承载（等效于 spec 的菜单键位，
 * 且是 JLine 用户已熟悉的交互模型）。
 *
 * <p>过滤规则（F25）：仅按命令主名前缀匹配，不参与别名/描述匹配；
 * hidden 命令不在候选中（由 registry.visible() 保证，F28）。
 */
final class SlashCompleter implements Completer {

    private final CommandRegistry registry;

    SlashCompleter(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word().strip();
        if (!word.startsWith("/") || line.wordIndex() > 0) {
            return; // 仅命令首词且以 / 开头时激活（F24/F26/F32a）
        }
        List<Command> matches = registry.prefixMatch(word);
        for (Command c : matches) {
            // desc 后缀展示描述；F27 两列对齐由 JLine 渲染层处理
            candidates.add(new Candidate("/" + c.name(), "/" + c.name(), null,
                    c.description(), null, null, true));
        }
    }
}
