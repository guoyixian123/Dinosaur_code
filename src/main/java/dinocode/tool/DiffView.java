package dinocode.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件修改摘要（ch16 TUI 视觉优化）：Edit/Write 成功后生成带 {@code @@DIF} 机器可读标记的
 * 摘要文本，Renderer 据此渲染行级红绿 diff（改了什么、改在哪几行）。
 *
 * 标记格式（每行前缀，行号 1-based，{@code |} 后为原文内容）：
 * <pre>
 * @@DIF +2 -1          首行统计：新增 n 行、删除 m 行
 * @@DIF -  13|旧行      删除行
 * @@DIF +  13|新行      新增行
 * @@DIF =  14|上下文行  上下文行
 * </pre>
 * 放在 tool 包而非 tui：工具层产生、tui 层消费，tui 已依赖 tool，反向依赖会成环。
 */
public final class DiffView {

    /** diff 上下文行数（替换点前后各取几行）。 */
    static final int CONTEXT_LINES = 2;
    /** diff 体最大行数（统计行不计）；超出由 Renderer 截断。 */
    static final int MAX_DIFF_LINES = 14;

    private static final String MARK = "@@DIF";

    private DiffView() {
    }

    /**
     * Edit 替换摘要：对 old→new 做行对齐 diff，附替换点前后上下文。
     * 任何计算异常都回退为旧版一句话摘要，不 fail 工具（spec §4）。
     */
    public static String editSummary(String path, String oldContent,
                                     String oldString, String newString) {
        try {
            return doEditSummary(path, oldContent, oldString, newString);
        } catch (RuntimeException e) {
            return "已修改 " + path;
        }
    }

    private static String doEditSummary(String path, String oldContent,
                                        String oldString, String newString) {
        String[] before = oldContent.split("\n", -1);
        String[] oldLines = oldString.split("\n", -1);
        String[] newLines = newString.split("\n", -1);
        int replaceLine = indexOfBlock(before, oldLines); // 0-based
        if (replaceLine < 0) {
            return "已修改 " + path;
        }

        List<String> out = new ArrayList<>();
        int added = 0;
        int removed = 0;
        // oldLines/newLines 行数可能不同：对齐区间取两者较大者，缺的一侧记空行
        int span = Math.max(oldLines.length, newLines.length);
        List<String> body = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            String o = i < oldLines.length ? oldLines[i] : null;
            String nw = i < newLines.length ? newLines[i] : null;
            if (o != null && nw != null && o.equals(nw)) {
                body.add(line("=", replaceLine + i + 1, o)); // 未变化行记为上下文
            } else {
                if (o != null) {
                    body.add(line("-", replaceLine + i + 1, o));
                    removed++;
                }
                if (nw != null) {
                    body.add(line("+", replaceLine + i + 1, nw));
                    added++;
                }
            }
        }
        // 前后上下文（新文件中的行号）：整体重排避免 head-insert 乱序
        int ctxStart = Math.max(0, replaceLine - CONTEXT_LINES);
        int ctxEnd = Math.min(before.length, replaceLine + oldLines.length + CONTEXT_LINES);
        List<String> ordered = new ArrayList<>(body.size() + (ctxEnd - ctxStart));
        for (int i = ctxStart; i < replaceLine; i++) {
            ordered.add(line("=", i + 1, before[i]));
        }
        ordered.addAll(body);
        for (int i = replaceLine + oldLines.length; i < ctxEnd; i++) {
            ordered.add(line("=", i + 1, before[i]));
        }

        out.add(stat(added, removed));
        out.addAll(ordered.size() > MAX_DIFF_LINES ? headAndTail(ordered) : ordered);
        return String.join("\n", out);
    }

    /**
     * Write 摘要：文件已存在 → 新旧全文朴素 diff；不存在 → 新建统计 + 内容预览。
     */
    public static String writeSummary(String path, String oldContent, String newContent, int bytes) {
        try {
            if (oldContent == null) {
                String[] lines = newContent.split("\n", -1);
                String tag = " (新文件)";
                List<String> out = new ArrayList<>();
                out.add("@@DIF +" + lines.length + " -0" + tag);
                int preview = Math.min(lines.length, MAX_DIFF_LINES);
                for (int i = 0; i < preview; i++) {
                    out.add(line("+", i + 1, lines[i]));
                }
                if (lines.length > preview) {
                    out.add("… 还有 " + (lines.length - preview) + " 行");
                }
                return String.join("\n", out);
            }
            return doWriteSummary(path, oldContent, newContent);
        } catch (RuntimeException e) {
            return "已写入 " + path + "（" + bytes + " 字节）";
        }
    }

    private static String doWriteSummary(String path, String oldContent, String newContent) {
        String[] before = oldContent.split("\n", -1);
        String[] after = newContent.split("\n", -1);
        // 朴素 diff：找公共前缀与公共后缀，中间即变更块（对整文件覆盖足够）
        int prefix = 0;
        while (prefix < before.length && prefix < after.length
                && before[prefix].equals(after[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < before.length - prefix && suffix < after.length - prefix
                && before[before.length - 1 - suffix].equals(after[after.length - 1 - suffix])) {
            suffix++;
        }

        List<String> body = new ArrayList<>();
        int added = after.length - prefix - suffix;
        int removed = before.length - prefix - suffix;
        for (int i = prefix; i < before.length - suffix; i++) {
            body.add(line("-", i + 1, before[i]));
        }
        for (int i = prefix; i < after.length - suffix; i++) {
            body.add(line("+", i + 1, after[i]));
        }
        // 前后各保留最多 CONTEXT_LINES 行上下文
        for (int i = Math.max(0, prefix - CONTEXT_LINES); i < prefix; i++) {
            body.add(0, line("=", i + 1, before[i]));
        }
        for (int i = after.length - suffix; i < Math.min(after.length, after.length - suffix + CONTEXT_LINES); i++) {
            body.add(line("=", i + 1, after[i]));
        }

        List<String> out = new ArrayList<>();
        out.add(stat(added, removed));
        out.addAll(body.size() > MAX_DIFF_LINES ? headAndTail(body) : body);
        return String.join("\n", out);
    }

    /** 在 whole 中找 block 首次出现处的行号（0-based）；找不到返回 -1。 */
    private static int indexOfBlock(String[] whole, String[] block) {
        if (block.length == 0 || block.length > whole.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= whole.length - block.length; i++) {
            for (int j = 0; j < block.length; j++) {
                if (!whole[i + j].equals(block[j])) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** diff 体超限：保留前 MAX_DIFF_LINES-1 行 + 尾部省略行（统计行不在 body 内）。 */
    private static List<String> headAndTail(List<String> body) {
        List<String> out = new ArrayList<>(body.subList(0, MAX_DIFF_LINES - 1));
        out.add("… 还有 " + (body.size() - MAX_DIFF_LINES + 1) + " 行变更");
        return out;
    }

    private static String stat(int added, int removed) {
        return MARK + " +" + added + " -" + removed;
    }

    private static String line(String kind, int lineno, String content) {
        return MARK + " " + kind + "  " + lineno + "|" + content;
    }
}
