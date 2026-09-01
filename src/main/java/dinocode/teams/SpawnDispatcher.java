package dinocode.teams;

import java.util.List;

/**
 * 队员启动统一入口（ch15 F6~F8/T7~T8）：按 TeamMode 分发 in-process / tmux。
 */
public final class SpawnDispatcher {

    private SpawnDispatcher() {
    }

    /** 启动入参。 */
    public record SpawnConfig(
            TeamManager.Team team,
            String memberName,
            String task,
            String workdir,
            String addendum) {
    }

    /** 启动结果。 */
    public record SpawnResult(TeamManager.TeamMode mode, String paneId) {
    }

    /**
     * 统一启动入口（F6）：IN_PROCESS 走虚拟线程；TMUX 先写 mailbox 再起进程（N2 先写后启）。
     */
    public static SpawnResult spawnTeammate(SpawnConfig config,
                                            TeammateRunner.SingleTurnRunner runner) {
        TeamManager.Team team = config.team();
        String memberName = config.memberName();
        switch (team.mode()) {
            case IN_PROCESS -> {
                // agent 槽放队员的 SubAgentSpec（Main 的 runner 据此构造子 Agent）
                dinocode.subagent.SubAgentSpec spec =
                        new dinocode.subagent.SubAgentSpec(
                                memberName, "团队队员", List.of(), List.of(), null, 200, "");
                TeamManager.Member member = new TeamManager.Member(memberName, spec, null);
                team.addMember(member);
                member.active = true;
                Thread.startVirtualThread(() -> TeammateRunner.runInProcessTeammate(
                        team, member, config.addendum(), config.task(), runner));
                return new SpawnResult(TeamManager.TeamMode.IN_PROCESS, null);
            }
            case TMUX -> {
                // N2：先写 mailbox 再 spawn——tmux 进程第一次 poll 必命中
                team.sendMessage(TeammateRunner.LEAD_NAME, memberName, config.task());
                String cli = buildTeammateCLI(team.name(), memberName, config.workdir());
                String paneId = TmuxBackend.spawnTmuxTeammate(team.name(), memberName, cli);
                recordExternalMember(team, memberName);
                return new SpawnResult(TeamManager.TeamMode.TMUX, paneId);
            }
            default -> throw new IllegalStateException("未知 team mode: " + team.mode());
        }
    }

    /** 外部后端队员注册（F2）：agent/conv 均为 null 的占位。 */
    public static void recordExternalMember(TeamManager.Team team, String memberName) {
        team.addMember(TeamManager.Member.external(memberName));
    }

    /** 队员 CLI 命令（F7/N7）：全部经 shellQuote。 */
    public static String buildTeammateCLI(String teamName, String memberName, String workdir) {
        String wd = workdir == null || workdir.isBlank()
                ? System.getProperty("user.dir") : workdir;
        String exe = ProcessHandle.current().info().command().orElse("mewcode");
        return "cd " + shellQuote(wd) + " && " + shellQuote(exe)
                + " --teammate --team-name " + shellQuote(teamName)
                + " --agent-name " + shellQuote(memberName);
    }

    /** POSIX shellQuote（F8/N7）：简单字符直返；特殊字符单引号包裹 + '\'' 转义。 */
    static String shellQuote(String s) {
        if (s == null || s.matches("[a-zA-Z0-9_./-]+")) {
            return s == null ? "" : s;
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
