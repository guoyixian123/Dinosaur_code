package dinocode.command;

/**
 * 输入解析（ch10 F3/F5/F7）：纯字符串操作、无副作用。
 * 非 "/" 开头 → 交 Agent；"/" 开头但带参数尾巴或空 name → 退化为 miss（走未命中提示）。
 */
public final class Dispatch {

    /** 解析结果：name 为小写命令名（可能为空串），isSlash 标记是否为斜杠输入。 */
    public record Parsed(String name, boolean isSlash) {
    }

    private Dispatch() {
    }

    /**
     * 解析输入：空白早返回；非 "/" 开头 {@code Parsed("", false)}；
     * 仅有 "/" 或 name 后还有尾随参数 {@code Parsed("", true)}（lookup 必 miss，F7）；
     * 正常 {@code Parsed(lowercaseName, true)}。
     */
    public static Parsed parse(String input) {
        String text = input == null ? "" : input.strip();
        if (text.isEmpty() || !text.startsWith("/")) {
            return new Parsed("", false);
        }
        String body = text.substring(1).strip();
        if (body.isEmpty()) {
            return new Parsed("", true); // 仅 "/"
        }
        int ws = body.indexOf(' ');
        String name = ws < 0 ? body : body.substring(0, ws);
        if (name.isEmpty()) {
            return new Parsed("", true);
        }
        if (ws >= 0 && !body.substring(ws).isBlank()) {
            return new Parsed("", true); // 携带参数尾巴 → 本期全部按未命中处理（F7）
        }
        return new Parsed(name.toLowerCase(), true);
    }
}
