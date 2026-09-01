package dinocode.teams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentTeam 单测（ch15 T13~T16）。
 */
class TeamTest {

    @TempDir
    Path tempDir;

    // ---------- T2：FileMailBox（T13 用例） ----------

    @Test
    void sendCreatesFileWithMessage() throws Exception {
        FileMailBox box = new FileMailBox(tempDir.resolve("inboxes"));
        box.send("agent-1", new FileMailBox.MailMessage("lead", "任务内容"));
        Path inbox = tempDir.resolve("inboxes/agent-1.json");
        assertTrue(Files.exists(inbox));
        String json = Files.readString(inbox);
        assertTrue(json.contains("\"from\"") && json.contains("lead"));
        assertTrue(json.contains("任务内容"));
        assertTrue(json.contains("false") || json.contains("read"));
    }

    @Test
    void readUnreadReturnsOnlyUnread() throws Exception {
        FileMailBox box = new FileMailBox(tempDir.resolve("inboxes"));
        box.send("a", new FileMailBox.MailMessage("lead", "第一条"));
        box.send("a", new FileMailBox.MailMessage("lead", "第二条"));
        List<FileMailBox.MailMessage> unread = box.readUnread("a");
        assertEquals(2, unread.size());
    }

    @Test
    void markAllReadMakesUnreadEmpty() throws Exception {
        FileMailBox box = new FileMailBox(tempDir.resolve("inboxes"));
        box.send("a", new FileMailBox.MailMessage("lead", "msg"));
        box.markAllRead("a");
        assertEquals(0, box.readUnread("a").size()); // T13-3
    }

    @Test
    void nonexistentAgentReturnsEmpty() {
        FileMailBox box = new FileMailBox(tempDir.resolve("inboxes"));
        assertEquals(0, box.readUnread("ghost").size()); // T13-4
    }

    @Test
    void teamSendMessageIntegration() throws Exception {
        TeamManager mgr = new TeamManager(tempDir.resolve("teams"));
        TeamManager.Team team = mgr.createTeam("dev");
        team.addMember(new TeamManager.Member("worker", new Object(), new Object()));
        team.sendMessage("lead", "worker", "干活");
        var unread = team.mailBox().readUnread("worker");
        assertEquals(1, unread.size()); // T13-5
        assertEquals("干活", unread.get(0).text());
        assertEquals("lead", unread.get(0).from());
    }

    @Test
    void concurrentSendersAllDelivered() throws Exception {
        FileMailBox box = new FileMailBox(tempDir.resolve("inboxes"));
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    box.send("shared", new FileMailBox.MailMessage("sender-" + idx, "msg-" + idx));
                } catch (Exception e) {
                    // 记录失败
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        // N1：文件锁保证 10 条全部落盘
        assertEquals(threads, box.readUnread("shared").size());
    }

    // ---------- T1：TeamManager ----------

    @Test
    void teamCrudAndMembers() {
        TeamManager mgr = new TeamManager(tempDir.resolve("teams"));
        TeamManager.Team team = mgr.createTeam("dev");
        assertEquals(TeamManager.TeamMode.IN_PROCESS, team.mode()); // TempDir 环境无 tmux env（可能检测出 tmux——断言其一）
        assertTrue(mgr.getTeam("dev") != null);

        team.addMember(new TeamManager.Member("alice", new Object(), new Object()));
        assertTrue(team.hasMember("alice"));
        assertEquals(List.of("alice"), team.memberNames());

        List<String> names = mgr.deleteTeam("dev").memberNames();
        assertEquals(List.of("alice"), names);
        assertNull(mgr.getTeam("dev"));
    }

    @Test
    void detectBackendReturnsOneOfTwo() {
        TeamManager.TeamMode mode = TeamManager.detectBackend();
        assertTrue(mode == TeamManager.TeamMode.IN_PROCESS || mode == TeamManager.TeamMode.TMUX);
    }

    // ---------- T8：shellQuote ----------

    @Test
    void shellQuoteRules() {
        assertEquals("/usr/local/bin", dinocode.teams.SpawnDispatcher.shellQuote("/usr/local/bin"));
        assertEquals("'my dir'", dinocode.teams.SpawnDispatcher.shellQuote("my dir"));
        assertEquals("'it'\\''s'", dinocode.teams.SpawnDispatcher.shellQuote("it's")); // POSIX 转义
        assertEquals("mewcode", dinocode.teams.SpawnDispatcher.shellQuote("mewcode"));
    }

    @Test
    void buildTeammateCLIQuoted() {
        String cli = dinocode.teams.SpawnDispatcher.buildTeammateCLI(
                "dev team", "worker 1", "/Users/speed/My Project");
        // N7：空格路径全部被引号包裹
        assertTrue(cli.contains("'"), "含特殊字符的参数应被单引号包裹: " + cli);
        assertTrue(cli.contains("'dev team'"), cli);
        assertTrue(cli.contains("'worker 1'"), cli);
        assertTrue(cli.contains("--teammate"));
        assertTrue(cli.contains("--team-name"));
        assertTrue(cli.contains("--agent-name"));
    }

    // ---------- T6：TeammateRunner 原语 ----------

    @Test
    void drainLeadMailboxBundlesNotifications() throws Exception {
        TeamManager mgr = new TeamManager(tempDir.resolve("teams"));
        TeamManager.Team team = mgr.createTeam("dev");
        team.addMember(new TeamManager.Member("alice", new Object(), new Object()));
        team.sendMessage("alice", "lead", "我做完了");
        team.sendMessage("alice", "lead", "[idle] alice: 任务完成");

        List<String> notifications = TeammateRunner.drainLeadMailbox(mgr);
        assertEquals(1, notifications.size());
        assertTrue(notifications.get(0).contains("<team-notification team=\"dev\">"));
        assertTrue(notifications.get(0).contains("from=alice: 我做完了"));
        // drain 后清空
        assertEquals(0, TeammateRunner.drainLeadMailbox(mgr).size());
    }

    @Test
    void drainLeadMailboxNullManager() {
        assertEquals(0, TeammateRunner.drainLeadMailbox(null).size());
    }

    @Test
    void buildTeammateAddendumContainsFourKeyFacts() {
        String addendum = TeammateRunner.buildTeammateAddendum("dev", "alice", List.of("bob", "carol"));
        assertTrue(addendum.contains("alice")); // 队员名
        assertTrue(addendum.contains("bob"));   // 其他队友
        assertTrue(addendum.contains("SendMessage")); // 必须通过 SendMessage 沟通
        assertTrue(addendum.contains("idle")); // 自动 idle 通知
    }

    @Test
    void shutdownDetectionAndIdleFormat() {
        assertTrue(TeammateRunner.isShutdownRequest("[shutdown] 请退出"));
        assertFalse(TeammateRunner.isShutdownRequest("正常消息"));
        assertFalse(TeammateRunner.isShutdownRequest(null));
        String idle = TeammateRunner.createIdleNotification("alice", "任务完成");
        assertTrue(idle.startsWith("[idle] alice:"));
        assertTrue(idle.contains("(at "));
    }

    // ---------- T9：Coordinator ----------

    @Test
    void coordinatorWhitelist() {
        assertTrue(dinocode.teams.Coordinator.isCoordinatorTool("Agent"));
        assertTrue(dinocode.teams.Coordinator.isCoordinatorTool("SendMessage"));
        assertTrue(dinocode.teams.Coordinator.isCoordinatorTool("TeamCreate"));
        assertTrue(dinocode.teams.Coordinator.isCoordinatorTool("ReadFile"));
        assertFalse(dinocode.teams.Coordinator.isCoordinatorTool("WriteFile")); // 写工具排除
        assertFalse(dinocode.teams.Coordinator.isCoordinatorTool("EditFile"));
        assertEquals(12, dinocode.teams.Coordinator.ALLOWED_TOOLS.size()); // F16
    }

    // ---------- T10：Team 工具 ----------

    @Test
    void teamCreateDeduplicatesNames() {
        TeamManager mgr = new TeamManager(tempDir.resolve("teams"));
        var tool = new TeamTools.TeamCreateTool(mgr);
        var first = tool.execute(Map.of("team_name", "dev"));
        assertFalse(first.isError());
        assertTrue(first.content().contains("Team \"dev\" created"));
        var second = tool.execute(Map.of("team_name", "dev"));
        assertTrue(second.content().contains("Team \"dev-2\" created")); // F18 去重
    }

    @Test
    void teamDeleteListsStoppedMembers() {
        TeamManager mgr = new TeamManager(tempDir.resolve("teams"));
        TeamManager.Team team = mgr.createTeam("dev");
        team.addMember(new TeamManager.Member("alice", new Object(), new Object()));
        team.addMember(new TeamManager.Member("bob", new Object(), new Object()));
        var tool = new TeamTools.TeamDeleteTool(mgr);
        var result = tool.execute(Map.of("team_name", "dev"));
        assertTrue(result.content().contains("Stopped 2 member(s): alice, bob")); // F19
        var missing = tool.execute(Map.of("team_name", "dev"));
        assertTrue(missing.isError());
    }

    @Test
    void sendMessageRoutesAcrossTeams() {
        TeamManager mgr = new TeamManager(tempDir.resolve("teams"));
        TeamManager.Team dev = mgr.createTeam("dev");
        dev.addMember(new TeamManager.Member("alice", new Object(), new Object()));
        var tool = new TeamTools.SendMessageTool(mgr, "lead");
        assertFalse(tool.execute(Map.of("to", "alice", "content", "hi")).isError());
        var missing = tool.execute(Map.of("to", "ghost", "content", "hi"));
        assertTrue(missing.content().contains("not found in any team")); // F17
    }

    // ---------- T11：AgentNameRegistry ----------

    @Test
    void nameRegistryBidirectionalResolve() {
        var registry = AgentNameRegistry.getInstance();
        registry.register("worker-a", "agent-99");
        assertEquals("agent-99", registry.resolve("worker-a"));
        assertEquals("agent-99", registry.resolve("agent-99")); // 反向 id 寻址
        assertNull(registry.resolve("unknown"));
        registry.unregister("worker-a");
        assertNull(registry.resolve("worker-a"));
    }

    // ---------- T12：SharedTaskStore ----------

    @Test
    void sharedTaskCrudWithAppendSemantics() throws Exception {
        Path teamDir = tempDir.resolve("tasks");
        SharedTaskStore store = new SharedTaskStore(teamDir);
        SharedTaskStore.SharedTask task = store.create("重构模块 A", "把 A 模块拆成三个类", "lead");
        assertEquals(1, task.id());
        assertEquals("pending", task.status());

        store.update(task.id(), "in_progress", "alice", List.of(2), List.of(3));
        // 追加语义：再次 update 不替换之前的 blocks
        store.update(task.id(), null, null, List.of(5), null);

        SharedTaskStore.SharedTask updated = store.get(task.id());
        assertEquals("in_progress", updated.status());
        assertEquals("alice", updated.assignee());
        assertEquals(List.of(2, 5), updated.blocks()); // 追加不替换（F21）
        assertEquals(List.of(3), updated.blockedBy());

        // 持久化：重新 load
        SharedTaskStore reloaded = new SharedTaskStore(teamDir);
        assertEquals(1, reloaded.listTasks(null, null).size());
        assertEquals(List.of(2, 5), reloaded.get(task.id()).blocks());
    }

    @Test
    void sharedTaskFilterByStatusAndAssignee() {
        SharedTaskStore store = new SharedTaskStore(tempDir.resolve("tasks2"));
        store.create("任务一", "", "lead");
        store.create("任务二", "", "lead");
        store.update(2, "in_progress", "alice", null, null);
        assertEquals(1, store.listTasks("in_progress", null).size());
        assertEquals(1, store.listTasks(null, "alice").size());
        assertEquals(2, store.listTasks(null, null).size());
    }
}
