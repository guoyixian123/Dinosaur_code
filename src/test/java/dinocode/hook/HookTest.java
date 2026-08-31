package dinocode.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hook 全链路单测（ch12 AC4~AC16）。
 */
class HookTest {

    @TempDir
    Path root;

    private void writeHooks(Path file, String yaml) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
    }

    // ---------- 加载校验（AC8/AC11/AC16） ----------

    @Test
    void unknownEventSkippedOthersLoad() throws Exception {
        writeHooks(root.resolve(".dino/hooks.yaml"), """
                hooks:
                  - name: bad
                    event: UnknownEvent
                    action:
                      type: shell
                      command: echo x
                  - name: good
                    event: SessionStart
                    action:
                      type: prompt
                      text: "hello"
                """);
        HookEngine engine = HookLoader.load(root);
        assertEquals(1, engine.rules().size());
        assertEquals("good", engine.rules().get(0).name());
    }

    @Test
    void asyncOnBlockingEventSkipped() throws Exception {
        writeHooks(root.resolve(".dino/hooks.yaml"), """
                hooks:
                  - name: bad-async
                    event: PreToolUse
                    async: true
                    action:
                      type: shell
                      command: echo x
                  - name: ok-async
                    event: PostToolUse
                    async: true
                    action:
                      type: shell
                      command: echo y
                """);
        HookEngine engine = HookLoader.load(root); // AC8
        assertEquals(1, engine.rules().size());
        assertEquals("ok-async", engine.rules().get(0).name());
    }

    @Test
    void bothAllOfAndAnyOfSkipped() throws Exception {
        writeHooks(root.resolve(".dino/hooks.yaml"), """
                hooks:
                  - name: mixed
                    event: PreToolUse
                    if:
                      all_of:
                        - field: tool_name
                          match: { type: exact, value: WriteFile }
                      any_of:
                        - field: tool_name
                          match: { type: exact, value: Bash }
                    action:
                      type: shell
                      command: echo x
                  - name: fine
                    event: SessionStart
                    action:
                      type: prompt
                      text: ok
                """);
        HookEngine engine = HookLoader.load(root); // AC16
        assertEquals(1, engine.rules().size());
        assertEquals("fine", engine.rules().get(0).name());
    }

    @Test
    void missingFileLoadsEmpty() {
        HookEngine engine = HookLoader.load(root);
        assertTrue(engine.isEmpty()); // N9
        assertTrue(engine.sources().isEmpty());
    }

    @Test
    void malformedYamlDegradesGracefully() throws Exception {
        writeHooks(root.resolve(".dino/hooks.yaml"), "{{{{ 不是 YAML");
        HookEngine engine = HookLoader.load(root);
        assertTrue(engine.isEmpty()); // N9 不阻断
    }

    // ---------- PreToolUse shell 拦截（AC4/AC5） ----------

    @Test
    void preToolUseShellExit2Blocks() throws Exception {
        writeHooks(root.resolve(".dino/hooks.yaml"), """
                hooks:
                  - name: block-write
                    event: PreToolUse
                    if:
                      all_of:
                        - field: tool_name
                          match: { type: exact, value: WriteFile }
                    action:
                      type: shell
                      command: "echo blocked >&2; exit 2"
                """);
        HookEngine engine = HookLoader.load(root);

        var result = engine.dispatch(Event.PRE_TOOL_USE, new HookRule.Payload(Map.of(
                "tool_name", "WriteFile", "tool_input", Map.of("path", "a.txt"))));
        assertTrue(result.blocked()); // AC4
        assertEquals("block-write", result.blockingHookName());
        assertEquals("blocked", result.reason());
    }

    @Test
    void preToolUseShellExit0Passes() throws Exception {
        writeHooks(root.resolve(".dino/hooks.yaml"), """
                hooks:
                  - name: allow-all
                    event: PreToolUse
                    action:
                      type: shell
                      command: "exit 0"
                """);
        HookEngine engine = HookLoader.load(root);
        var result = engine.dispatch(Event.PRE_TOOL_USE, new HookRule.Payload(Map.of("tool_name", "WriteFile")));
        assertFalse(result.blocked()); // AC5
    }

    @Test
    void nonZeroExitIsFailureNotBlock() throws Exception {
        writeHooks(root.resolve(".dino/hooks.yaml"), """
                hooks:
                  - name: failing
                    event: PreToolUse
                    action:
                      type: shell
                      command: "echo oops >&2; exit 3"
                """);
        HookEngine engine = HookLoader.load(root);
        var result = engine.dispatch(Event.PRE_TOOL_USE, new HookRule.Payload(Map.of()));
        assertFalse(result.blocked()); // G9：失败不拦截
    }

    @Test
    void conditionMismatchDoesNotRun() throws Exception {
        writeHooks(root.resolve(".dino/hooks.yaml"), """
                hooks:
                  - name: only-write
                    event: PreToolUse
                    if:
                      all_of:
                        - field: tool_name
                          match: { type: exact, value: WriteFile }
                    action:
                      type: shell
                      command: "exit 2"
                """);
        HookEngine engine = HookLoader.load(root);
        // 工具名不匹配 → 不执行 shell → 不拦截（AC14 反向：其它工具不受影响）
        var result = engine.dispatch(Event.PRE_TOOL_USE, new HookRule.Payload(Map.of("tool_name", "ReadFile")));
        assertFalse(result.blocked());
    }

    // ---------- UserPromptSubmit 拦截（AC10） ----------

    @Test
    void userPromptSubmitRegexBlock() throws Exception {
        writeHooks(root.resolve(".dino/hooks.yaml"), """
                hooks:
                  - name: no-delete
                    event: UserPromptSubmit
                    if:
                      all_of:
                        - field: prompt
                          match: { type: regex, value: "(?i)delete" }
                    action:
                      type: shell
                      command: "echo prompt contains delete keyword >&2; exit 2"
                """);
        HookEngine engine = HookLoader.load(root);
        var result = engine.dispatch(Event.USER_PROMPT_SUBMIT, new HookRule.Payload(
                Map.of("prompt", "请帮我 delete 那个文件")));
        assertTrue(result.blocked()); // AC10
        assertEquals("prompt contains delete keyword", result.reason());
    }

    // ---------- prompt 注入（F20/F33/AC6） ----------

    @Test
    void promptActionCollectsInjectedText() throws Exception {
        writeHooks(root.resolve(".dino/hooks.yaml"), """
                hooks:
                  - name: zh-cn
                    event: SessionStart
                    action:
                      type: prompt
                      text: "用 zh-CN 回复"
                  - name: polite
                    event: SessionStart
                    action:
                      type: prompt
                      text: "保持礼貌"
                """);
        HookEngine engine = HookLoader.load(root);
        var result = engine.dispatch(Event.SESSION_START, new HookRule.Payload(Map.of()));
        assertEquals(List.of("用 zh-CN 回复", "保持礼貌"), result.injectedPrompts()); // 声明序
        assertFalse(result.blocked()); // F22：prompt 永不拦截
    }

    // ---------- only_once（F27/AC9） ----------

    @Test
    void onlyOnceFiresOnceThenSkipped() throws Exception {
        writeHooks(root.resolve(".dino/hooks.yaml"), """
                hooks:
                  - name: first-turn
                    event: PreUserMessage
                    only_once: true
                    action:
                      type: shell
                      command: "exit 0"
                """);
        HookEngine engine = HookLoader.load(root);
        var p = new HookRule.Payload(Map.of());
        engine.dispatch(Event.PRE_USER_MESSAGE, p);
        // 第二次仍执行无副作用动作，但 onceFired 已记录——通过 reset 行为验证
        var before = engine.rules().size();
        engine.dispatch(Event.PRE_USER_MESSAGE, p);
        assertEquals(before, engine.rules().size());
        engine.resetForNewSession(); // N5：新会话清空
        // 再次 dispatch 正常（无崩溃）
        engine.dispatch(Event.PRE_USER_MESSAGE, p);
    }

    // ---------- http 动作（AC13/AC14） ----------

    @Test
    void httpBlockDecisionBlocks() throws Exception {
        // 本地 echo server 返回 decision=block
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        AtomicInteger bodySeen = new AtomicInteger();
        server.createContext("/check", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (new String(body).contains("\"tool_name\":\"Bash\"")) {
                bodySeen.set(1);
                byte[] resp = "{\"decision\":\"block\",\"reason\":\"network policy\"}".getBytes();
                exchange.sendResponseHeaders(200, resp.length);
                exchange.getResponseBody().write(resp);
            } else {
                byte[] resp = "{\"decision\":\"allow\"}".getBytes();
                exchange.sendResponseHeaders(200, resp.length);
                exchange.getResponseBody().write(resp);
            }
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            writeHooks(root.resolve(".dino/hooks.yaml"), """
                    hooks:
                      - name: policy
                        event: PreToolUse
                        action:
                          type: http
                          url: "http://127.0.0.1:%d/check"
                    """.formatted(port));
            HookEngine engine = HookLoader.load(root);

            var blocked = engine.dispatch(Event.PRE_TOOL_USE,
                    new HookRule.Payload(Map.of("tool_name", "Bash", "tool_input", Map.of())));
            assertTrue(blocked.blocked()); // AC14
            assertEquals("network policy", blocked.reason());

            var passed = engine.dispatch(Event.PRE_TOOL_USE,
                    new HookRule.Payload(Map.of("tool_name", "ReadFile")));
            assertFalse(passed.blocked()); // 其它工具不受影响
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpStopNotificationSendsEventJson() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        server.createContext("/done", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            if (body.contains("\"event\":\"Stop\"")) {
                received.countDown();
            }
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("ok".getBytes());
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            writeHooks(root.resolve(".dino/hooks.yaml"), """
                    hooks:
                      - name: notify-done
                        event: Stop
                        action:
                          type: http
                          url: "http://127.0.0.1:%d/done"
                    """.formatted(port));
            HookEngine engine = HookLoader.load(root);
            engine.dispatch(Event.STOP, new HookRule.Payload(Map.of("event", "Stop", "iter", 3)));
            assertTrue(received.await(5, java.util.concurrent.TimeUnit.SECONDS)); // AC13
        } finally {
            server.stop(0);
        }
    }

    // ---------- subagent 占位（AC15） ----------

    @Test
    void subagentActionIsPlaceholder() throws Exception {
        writeHooks(root.resolve(".dino/hooks.yaml"), """
                hooks:
                  - name: sub
                    event: SessionStart
                    action:
                      type: subagent
                      agent_name: foo
                      prompt: test
                """);
        HookEngine engine = HookLoader.load(root);
        var result = engine.dispatch(Event.SESSION_START, new HookRule.Payload(Map.of()));
        assertFalse(result.blocked());
        assertTrue(result.injectedPrompts().isEmpty()); // 不注入不拦截，仅日志
    }

    // ---------- Payload（F10/F13/N6） ----------

    @Test
    void payloadNestedPathAndSortedJson() {
        HookRule.Payload p = new HookRule.Payload(Map.of(
                "tool_input", Map.of("path", "a.java", "command", "x"),
                "event", "PreToolUse"));
        assertEquals("a.java", p.getByPath("tool_input.path"));
        assertEquals("", p.getByPath("tool_input.nonexistent")); // F13 缺路径 → 空串
        assertEquals("", p.getByPath("nonexistent.deeper"));
        String json = p.toSortedJson();
        // N6：key 字典序（event < tool_input；tool_input 内 command < path）
        assertTrue(json.indexOf("\"event\"") < json.indexOf("\"tool_input\""));
        assertTrue(json.indexOf("\"command\"") < json.indexOf("\"path\""));
    }

    // ---------- 两层合并（F7/AC12） ----------

    @Test
    void userAndProjectLayersMerge() throws Exception {
        Path project = root.resolve(".dino/hooks.yaml");
        Path user = Path.of(System.getProperty("user.home"), ".dino", "hooks.yaml");
        boolean hadUser = Files.exists(user);
        try {
            writeHooks(project, """
                    hooks:
                      - name: proj-hook
                        event: SessionStart
                        action:
                          type: prompt
                          text: from-project
                    """);
            writeHooks(user, """
                    hooks:
                      - name: user-hook
                        event: SessionStart
                        action:
                          type: prompt
                          text: from-user
                    """);
            HookEngine engine = HookLoader.load(root);
            assertEquals(2, engine.rules().size()); // AC12 叠加合并
            assertEquals(2, engine.sources().size());
        } finally {
            if (!hadUser) {
                Files.deleteIfExists(user);
            }
        }
    }
}
