package dinocode.teams;

import dinocode.core.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 队员运行时（ch15 F11~F15/T5~T6）：in-process 队员主循环 + Lead 侧通信原语。
 */
public final class TeammateRunner {

    private static final Logger LOG = Logger.getLogger(TeammateRunner.class.getName());

    public static final String LEAD_NAME = "lead";
    public static final String SHUTDOWN_PREFIX = "[shutdown]";
    public static final long IDLE_POLL_MS = 500;

    private TeammateRunner() {
    }

    /**
     * in-process 队员主循环（F11/N3/N9）。由 SpawnDispatcher 在虚拟线程里调用。
     * runner 静态委托由 TUI 注入（构造子 Agent 跑一轮的职责在 agent 层）。
     */
    public interface SingleTurnRunner {
        /** 跑一轮 agent loop；返回最终 assistant 文本。 */
        String runOneTurn(Object agent, Object conv, List<Message> seed) throws Exception;
    }

    /**
     * 队员主循环。
     *
     * @param addendum     身份 system reminder（buildTeammateAddendum 产出）
     * @param initialPrompt 初始任务
     */
    public static void runInProcessTeammate(
            TeamManager.Team team, TeamManager.Member member,
            String addendum, String initialPrompt, SingleTurnRunner runner) {

        FileMailBox mailBox = team.mailBox();
        List<Message> conv = new ArrayList<>();
        try {
            // 1) 身份 addendum（N5：必须告知结果要 SendMessage 给 Lead）
            if (addendum != null && !addendum.isBlank()) {
                conv.add(Message.user("<system-reminder>\n" + addendum + "\n</system-reminder>"));
            }
            // 2) 未读邮件注入
            injectPendingMessages(team, member.name, conv);
            // 3) 初始任务
            conv.add(Message.user(initialPrompt));

            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    break; // N3 退出路径 1
                }
                // 4) 跑一轮
                String result = runner.runOneTurn(member.agent, member.conv, conv);
                // 5) 结果发给 Lead（N5：纯文本回复队友不可见，必须显式发）
                team.sendMessage(member.name, LEAD_NAME,
                        result == null || result.isBlank() ? "(无输出)" : result);
                // 6) idle 通知
                team.sendMessage(member.name, LEAD_NAME,
                        createIdleNotification(member.name, "任务完成"));
                // 7) 等下一封消息或 shutdown
                String next = waitForNextPromptOrShutdown(team, member.name);
                if (next == null) {
                    break; // N3 退出路径 3：shutdown
                }
                conv.add(Message.user(next));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // N3 退出路径 2：中断
        } catch (Exception e) {
            LOG.warning(() -> "[team] 队员 " + member.name + " 异常退出: " + e.getMessage());
            try {
                mailBox.send(LEAD_NAME, new FileMailBox.MailMessage(member.name,
                        "[error] 队员异常退出: " + e.getMessage()));
            } catch (Exception ignored) {
                //
            }
        } finally {
            member.active = false; // N3：Lead 依赖此状态
        }
    }

    /** 轮询邮箱（F11）：500ms 间隔；命中新消息返回文本；shutdown 或中断返回 null。 */
    private static String waitForNextPromptOrShutdown(TeamManager.Team team, String memberName)
            throws InterruptedException {
        FileMailBox mailBox = team.mailBox();
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            List<FileMailBox.MailMessage> unread = mailBox.readUnread(memberName);
            if (!unread.isEmpty()) {
                mailBox.markAllRead(memberName);
                StringBuilder sb = new StringBuilder();
                for (FileMailBox.MailMessage m : unread) {
                    if (isShutdownRequest(m.text())) {
                        return null; // 收到 shutdown
                    }
                    sb.append("From ").append(m.from()).append(": ").append(m.text()).append("\n\n");
                }
                String text = sb.toString().strip();
                if (!text.isEmpty()) {
                    return text;
                }
            }
            Thread.sleep(IDLE_POLL_MS); // N9：sleep 而非自旋
        }
    }

    /** Lead 侧：抽取所有团队 Lead 邮箱的未读，包成 team-notification（F13）。 */
    public static List<String> drainLeadMailbox(TeamManager teamMgr) {
        if (teamMgr == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (TeamManager.Team team : teamMgr.listTeams()) {
            List<FileMailBox.MailMessage> unread = team.mailBox().readUnread(LEAD_NAME);
            if (unread.isEmpty()) {
                continue;
            }
            StringBuilder sb = new StringBuilder("<team-notification team=\"" + team.name() + "\">\n");
            for (FileMailBox.MailMessage m : unread) {
                sb.append("from=").append(m.from()).append(": ").append(m.text()).append("\n");
            }
            sb.append("</team-notification>");
            out.add(sb.toString());
            team.mailBox().markAllRead(LEAD_NAME);
        }
        return out;
    }

    /** 队员身份 addendum（F14/N5）：四条关键信息。 */
    public static String buildTeammateAddendum(String teamName, String memberName,
                                               List<String> otherMembers) {
        return """
                你是团队 "%s" 的队员 "%s"。
                其他队友: %s
                队友之间必须通过 SendMessage 工具沟通——纯文本回复对队友不可见。
                你的最终结果必须通过 SendMessage 发给 lead，否则 Lead 拿不到。
                你停止调用工具后会自动向 lead 发 idle 通知。"""
                .formatted(teamName, memberName,
                        otherMembers.isEmpty() ? "（暂无）" : String.join(", ", otherMembers));
    }

    /** 未读邮件注入（F15）。 */
    public static void injectPendingMessages(TeamManager.Team team, String memberName, List<Message> conv) {
        List<FileMailBox.MailMessage> unread = team.mailBox().readUnread(memberName);
        if (unread.isEmpty()) {
            return;
        }
        team.mailBox().markAllRead(memberName);
        StringBuilder sb = new StringBuilder("You have new messages:\n\n");
        for (FileMailBox.MailMessage m : unread) {
            sb.append("From ").append(m.from()).append(": \n\n").append(m.text()).append("\n\n");
        }
        conv.add(Message.user("<system-reminder>\n" + sb + "</system-reminder>"));
    }

    public static boolean isShutdownRequest(String text) {
        return text != null && text.strip().startsWith(SHUTDOWN_PREFIX);
    }

    public static String createIdleNotification(String memberName, String reason) {
        return "[idle] " + memberName + ": " + reason + " (at " + Instant.now() + ")";
    }
}
