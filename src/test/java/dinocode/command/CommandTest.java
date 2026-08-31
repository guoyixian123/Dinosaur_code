package dinocode.command;

import dinocode.permission.Mode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令注册中心与内置命令单测（ch10 AC1~AC4/AC16 + F2/F4/F7/F25）。
 */
class CommandTest {

    /** 可观测桩：记录调用。 */
    private static final class RecordingUi implements Ui {
        final List<String> printed = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<String> injected = new ArrayList<>();
        final List<Mode> modeSets = new ArrayList<>();
        Mode mode = Mode.DEFAULT;
        boolean quitCalled;
        boolean compactCalled;
        boolean resumeCalled;
        boolean clearCalled;
        boolean busy;

        @Override
        public void println(String msg) {
            printed.add(msg);
        }

        @Override
        public void error(String msg) {
            errors.add(msg);
        }

        @Override
        public Mode mode() {
            return mode;
        }

        @Override
        public void setMode(Mode m) {
            mode = m;
            modeSets.add(m);
        }

        @Override
        public void injectAndSend(String displayLabel, String presetPrompt) {
            injected.add(displayLabel + "|" + presetPrompt);
        }

        @Override
        public long usageIn() {
            return 100;
        }

        @Override
        public long usageOut() {
            return 50;
        }

        @Override
        public String modelName() {
            return "test-model";
        }

        @Override
        public String cwd() {
            return "/tmp/proj";
        }

        @Override
        public int toolCount() {
            return 6;
        }

        @Override
        public List<String> memoryFiles() {
            return List.of("MEMORY.md");
        }

        @Override
        public String sessionPath() {
            return "/tmp/sessions/x/conversation.jsonl";
        }

        @Override
        public String sessionId() {
            return "20260831-120000-aabb";
        }

        @Override
        public void quit() {
            quitCalled = true;
        }

        @Override
        public void forceCompact() {
            compactCalled = true;
        }

        @Override
        public void openResumeMenu() {
            resumeCalled = true;
        }

        @Override
        public void clearAndNewSession() {
            clearCalled = true;
        }

        @Override
        public boolean idle() {
            return !busy;
        }
    }

    private static Command dummy(String name) {
        return Command.of(name, "测试命令 " + name, Kind.LOCAL, (c, ui) -> {
        });
    }

    // ---------- Registry（F2/F4/AC16） ----------

    @Test
    void registerAndLookupCaseInsensitive() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(dummy("help"));
        assertTrue(reg.lookup("help").isPresent());
        assertTrue(reg.lookup("HELP").isPresent()); // F4 大小写不敏感
        assertTrue(reg.lookup("Help").isPresent());
        assertTrue(reg.lookup("nope").isEmpty());
    }

    @Test
    void duplicateNameThrowsWithKeyName() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(dummy("exit"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> reg.register(dummy("exit")));
        assertTrue(e.getMessage().contains("exit")); // N4：报错含冲突名
    }

    @Test
    void duplicateAliasThrows() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(new Command("a", List.of("b"), "", Kind.LOCAL, false, (c, ui) -> {
        }));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> reg.register(new Command("c", List.of("b"), "", Kind.LOCAL, false, (cc, ui) -> {
                })));
        assertTrue(e.getMessage().contains("b"));
    }

    @Test
    void visibleSortedAndExcludesHidden() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(dummy("zeta"));
        reg.register(dummy("alpha"));
        reg.register(new Command("secret", List.of(), "隐藏", Kind.LOCAL, true, (c, ui) -> {
        }));
        assertEquals(List.of("alpha", "zeta"), reg.visible().stream().map(Command::name).toList());
        assertTrue(reg.lookup("secret").isPresent()); // hidden 仍可命中（F28）
    }

    @Test
    void prefixMatchFiltersByNameOnly() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(dummy("status"));
        reg.register(dummy("session"));
        reg.register(dummy("clear"));
        assertEquals(2, reg.prefixMatch("/s").size()); // F25：/s → status + session
        assertEquals(1, reg.prefixMatch("/st").size());
        assertEquals("status", reg.prefixMatch("/st").get(0).name());
        assertEquals(3, reg.prefixMatch("").size()); // 空 = 全部
    }

    // ---------- Dispatch（F3/F5/F7） ----------

    @Test
    void parseCoversAllShapes() {
        assertFalse(Dispatch.parse("hello world").isSlash());
        assertFalse(Dispatch.parse("").isSlash());
        assertFalse(Dispatch.parse("   ").isSlash()); // F5 空白早返回
        assertTrue(Dispatch.parse("/").isSlash());
        assertEquals("", Dispatch.parse("/").name());
        assertEquals("help", Dispatch.parse("/help").name());
        assertEquals("help", Dispatch.parse("  /HELP  ").name()); // F4
        assertEquals("", Dispatch.parse("/help xx").name()); // F7 带参数按未命中
        assertTrue(Dispatch.parse("/help xx").isSlash());
        assertEquals("help", Dispatch.parse("/help   ").name()); // 尾随空白不算参数
        // //double：name 含 "/"，lookup 必 miss → 走未命中提示（行为正确）
        assertTrue(Dispatch.parse("//double").isSlash());
        assertFalse(reg_lookupMissOnDoubleSlash());
    }

    private static boolean reg_lookupMissOnDoubleSlash() {
        CommandRegistry reg = new CommandRegistry();
        Builtins.registerAll(reg);
        return reg.lookup(Dispatch.parse("//double").name()).isPresent(); // 应为 false
    }

    // ---------- Builtins（F12~F23） ----------

    @Test
    void registerAllTwelveCommandsNoCollision() {
        CommandRegistry reg = new CommandRegistry();
        Builtins.registerAll(reg); // 不抛 = 无冲突（F2）
        assertEquals(12, reg.visible().size());
        for (String name : new String[]{"clear", "compact", "do", "exit", "help", "memory",
                "permission", "plan", "resume", "review", "session", "status"}) {
            assertTrue(reg.lookup(name).isPresent(), "缺少命令: " + name);
        }
    }

    @Test
    void helpOutputsSortedTwelveLines() throws Exception {
        CommandRegistry reg = new CommandRegistry();
        Builtins.registerAll(reg);
        RecordingUi ui = new RecordingUi();
        reg.lookup("help").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        assertEquals(1, ui.printed.size());
        String text = ui.printed.get(0);
        int clearIdx = text.indexOf("/clear");
        int statusIdx = text.indexOf("/status");
        assertTrue(clearIdx >= 0 && statusIdx > clearIdx); // AC1 字典序
        for (String name : new String[]{"/do", "/memory", "/review", "/session"}) {
            assertTrue(text.contains(name), "缺 " + name);
        }
    }

    @Test
    void statusPrintsSixFieldsInOrder() throws Exception {
        CommandRegistry reg = new CommandRegistry();
        Builtins.registerAll(reg);
        RecordingUi ui = new RecordingUi();
        reg.lookup("status").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        assertEquals(1, ui.printed.size());
        String text = ui.printed.get(0);
        // AC4：六项按 F19 顺序
        int p1 = text.indexOf("权限模式");
        int p2 = text.indexOf("累计 token");
        int p3 = text.indexOf("可用工具");
        int p4 = text.indexOf("已加载记忆");
        int p5 = text.indexOf("当前模型");
        int p6 = text.indexOf("工作目录");
        assertTrue(p1 < p2 && p2 < p3 && p3 < p4 && p4 < p5 && p5 < p6);
        assertTrue(text.contains("100")); // usageIn
        assertTrue(text.contains("test-model"));
    }

    @Test
    void permissionMemorySessionLocalHandlers() throws Exception {
        CommandRegistry reg = new CommandRegistry();
        Builtins.registerAll(reg);
        RecordingUi ui = new RecordingUi();

        reg.lookup("permission").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        assertTrue(ui.printed.get(ui.printed.size() - 1).contains("default")); // AC6

        reg.lookup("memory").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        assertTrue(ui.printed.get(ui.printed.size() - 1).contains("MEMORY.md")); // AC5

        reg.lookup("session").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        String last = ui.printed.get(ui.printed.size() - 1);
        assertTrue(last.contains("20260831-120000-aabb") && last.contains("conversation.jsonl")); // AC7
    }

    @Test
    void uiKindHandlersDriveState() throws Exception {
        CommandRegistry reg = new CommandRegistry();
        Builtins.registerAll(reg);
        RecordingUi ui = new RecordingUi();

        reg.lookup("plan").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        assertEquals(Mode.PLAN, ui.mode); // F13

        reg.lookup("exit").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        assertTrue(ui.quitCalled); // F12

        reg.lookup("compact").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        assertTrue(ui.compactCalled); // F15

        reg.lookup("resume").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        assertTrue(ui.resumeCalled); // F16

        reg.lookup("clear").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        assertTrue(ui.clearCalled); // F17
    }

    @Test
    void promptKindHandlersInjectMessages() throws Exception {
        CommandRegistry reg = new CommandRegistry();
        Builtins.registerAll(reg);
        RecordingUi ui = new RecordingUi();

        ui.mode = Mode.PLAN;
        reg.lookup("do").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        assertEquals(Mode.DEFAULT, ui.mode); // F14：切回默认
        assertEquals(1, ui.injected.size());
        assertTrue(ui.injected.get(0).startsWith("/do|")); // 注入执行指令

        reg.lookup("review").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        assertEquals(2, ui.injected.size());
        assertTrue(ui.injected.get(1).startsWith("/review|")); // F23
        assertTrue(ui.injected.get(1).contains("审查"));
    }

    @Test
    void unknownLookupMiss() {
        CommandRegistry reg = new CommandRegistry();
        Builtins.registerAll(reg);
        assertTrue(reg.lookup("foobar").isEmpty()); // AC2 前提
        assertTrue(reg.lookup("").isEmpty());
    }

    @Test
    void idleGuardIsDispatcherConcernNotHandler() throws Exception {
        // N3a：LOCAL 命令 handler 自身不做 idle 检查——busy 状态下 /status 仍正常输出
        CommandRegistry reg = new CommandRegistry();
        Builtins.registerAll(reg);
        RecordingUi ui = new RecordingUi();
        ui.busy = true;
        reg.lookup("status").orElseThrow().handler()
                .handle(new AtomicBoolean(false), ui); // handler 声明 throws Exception，测试方法已声明
        assertEquals(1, ui.printed.size()); // busy 下仍执行（N3a）
        assertFalse(ui.errors.size() > 0);
    }
}
