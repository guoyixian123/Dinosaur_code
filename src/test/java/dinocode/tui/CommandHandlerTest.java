package dinocode.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行为断言依据：checklist §D。
 */
class CommandHandlerTest {

    private CommandHandler.Result handle(String line) {
        return CommandHandler.handle(line, 4096);
    }

    @Test
    void tokensWithoutArgumentShowsCurrentValue() {
        CommandHandler.Result result = handle("/tokens");
        assertEquals(CommandHandler.Action.PRINT, result.action());
        assertEquals("当前最大输出: 4096", result.message());
    }

    @Test
    void tokensPresets() {
        assertEquals(1024, handle("/tokens low").tokens());
        assertEquals(4096, handle("/tokens medium").tokens());
        assertEquals(16384, handle("/tokens high").tokens());
        assertEquals(64000, handle("/tokens max").tokens());
        assertEquals(CommandHandler.Action.TOKENS_SET, handle("/tokens low").action());
    }

    @Test
    void tokensCustomNumber() {
        CommandHandler.Result result = handle("/tokens 2000");
        assertEquals(CommandHandler.Action.TOKENS_SET, result.action());
        assertEquals(2000, result.tokens());
    }

    @Test
    void tokensInvalidInputKeepsValue() {
        CommandHandler.Result result = handle("/tokens abc");
        assertEquals(CommandHandler.Action.PRINT, result.action());
        assertTrue(result.message().contains("用法"), result.message());
        assertEquals(null, result.tokens());
    }

    @Test
    void tokensNonPositiveRejected() {
        assertEquals(CommandHandler.Action.PRINT, handle("/tokens -5").action());
        assertEquals(CommandHandler.Action.PRINT, handle("/tokens 0").action());
    }

    @Test
    void helpListsExactlyFourCommands() {
        CommandHandler.Result result = handle("/help");
        assertTrue(result.message().contains("/exit"));
        assertTrue(result.message().contains("/new"));
        assertTrue(result.message().contains("/help"));
        assertTrue(result.message().contains("/tokens"));
    }

    @Test
    void unknownCommandShowsNameAndHint() {
        CommandHandler.Result result = handle("/foo");
        assertTrue(result.message().contains("未知命令: /foo"), result.message());
        assertTrue(result.message().contains("/help"));
    }

    @Test
    void exitAndNew() {
        assertEquals(CommandHandler.Action.EXIT, handle("/exit").action());
        assertEquals(CommandHandler.Action.NEW_SESSION, handle("/new").action());
    }
}
