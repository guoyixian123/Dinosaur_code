package dino.tui;

import java.util.Map;

/**
 * 斜杠命令解析。命令集与行为见 checklist §D。
 */
final class CommandHandler {

    enum Action {
        /** 退出程序 */
        EXIT,
        /** 开启新会话 */
        NEW_SESSION,
        /** 设置最大输出（携带 tokens 值） */
        TOKENS_SET,
        /** 仅打印 message */
        PRINT
    }

    record Result(Action action, String message, Integer tokens) {
    }

    /** 档位预设值（checklist §D）。 */
    static final Map<String, Integer> PRESETS = Map.of(
            "low", 1024,
            "medium", 4096,
            "high", 16384,
            "max", 64000);

    private static final String TOKENS_USAGE =
            "用法: /tokens 查看当前值，或 /tokens low|medium|high|max|<正整数> 设置";

    private static final String HELP_TEXT = """
            可用命令:
              /exit     保存并退出
              /new      开启新会话
              /help     显示本帮助
              /tokens   查看或设置最大输出 (/tokens low|medium|high|max|<数值>)""";

    private CommandHandler() {
    }

    static Result handle(String line, int currentTokens) {
        String[] parts = line.strip().split("\\s+", 2);
        String command = parts[0];
        String argument = parts.length > 1 ? parts[1].strip() : null;
        return switch (command) {
            case "/exit" -> new Result(Action.EXIT, null, null);
            case "/new" -> new Result(Action.NEW_SESSION, null, null);
            case "/help" -> new Result(Action.PRINT, HELP_TEXT, null);
            case "/tokens" -> parseTokens(argument, currentTokens);
            default -> new Result(Action.PRINT,
                    "未知命令: " + command + "\n用 /help 查看可用命令", null);
        };
    }

    private static Result parseTokens(String argument, int currentTokens) {
        if (argument == null || argument.isEmpty()) {
            return new Result(Action.PRINT, "当前最大输出: " + currentTokens, null);
        }
        Integer preset = PRESETS.get(argument.toLowerCase());
        if (preset != null) {
            return new Result(Action.TOKENS_SET, null, preset);
        }
        try {
            int value = Integer.parseInt(argument);
            if (value <= 0) {
                return new Result(Action.PRINT, TOKENS_USAGE, null);
            }
            return new Result(Action.TOKENS_SET, null, value);
        } catch (NumberFormatException e) {
            return new Result(Action.PRINT, TOKENS_USAGE, null);
        }
    }
}
