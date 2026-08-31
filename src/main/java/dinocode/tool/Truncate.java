package dinocode.tool;

import java.nio.charset.StandardCharsets;

/**
 * 工具函数：按行数 + 字节数截断，超出尾部加 [truncated] 标注（N5/AC13）。
 */
public final class Truncate {

    private Truncate() {
    }

    public static String byLinesAndBytes(String s, int maxLines, int maxBytes) {
        String out = s;
        boolean truncated = false;

        if (utf8Length(out) > maxBytes) {
            out = truncateUtf8(out, maxBytes);
            truncated = true;
        }

        String[] lines = out.split("\n", -1);
        if (lines.length > maxLines) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < maxLines; i++) {
                sb.append(lines[i]).append('\n');
            }
            out = sb.toString();
            truncated = true;
        }

        return truncated ? out + "[truncated]" : out;
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 按 UTF-8 字节截断，不切断多字节字符。 */
    private static String truncateUtf8(String s, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cpBytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + cpBytes > maxBytes) {
                break;
            }
            sb.appendCodePoint(cp);
            bytes += cpBytes;
            i += Character.charCount(cp);
        }
        return sb.toString();
    }
}
