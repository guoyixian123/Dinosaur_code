package dinocode.teams;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 团队注册表（ch15 F1~F3/T1）：多 Agent 组队并行协作。
 * 全部顶层 CRUD synchronized（N1 同源并发约束）；成员表由 Team 内部 synchronized 保护。
 */
public final class TeamManager {

    /** 后端模式（F1）：in-process 虚拟线程 / tmux 外部进程。 */
    public enum TeamMode {IN_PROCESS, TMUX}

    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Path baseDir; // ~/.dino/teams

    public TeamManager(Path baseDir) {
        this.baseDir = baseDir;
    }

    public static Path teamsBaseDir() {
        return Path.of(System.getProperty("user.home"), ".dino", "teams");
    }

    /** 后端自动选择（F1）：TMUX env → which tmux → IN_PROCESS。 */
    public static TeamMode detectBackend() {
        if (System.getenv("TMUX") != null) {
            return TeamMode.TMUX;
        }
        try {
            Process p = new ProcessBuilder("which", "tmux").start();
            if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                return TeamMode.TMUX;
            }
        } catch (IOException | InterruptedException ignored) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
        }
        return TeamMode.IN_PROCESS;
    }

    public synchronized Team createTeam(String name) {
        return teams.computeIfAbsent(name, n -> new Team(n, detectBackend(), baseDir.resolve(n)));
    }

    public synchronized Team getTeam(String name) {
        return teams.get(name);
    }

    /** 删除团队：先 stopAll 中断所有队员（F19）。 */
    public synchronized Team deleteTeam(String name) {
        Team team = teams.remove(name);
        if (team != null) {
            team.stopAll();
        }
        return team;
    }

    public synchronized List<Team> listTeams() {
        return List.copyOf(teams.values());
    }

    /** 程序退出时停掉全部团队。 */
    public synchronized void closeAll() {
        for (Team team : teams.values()) {
            team.stopAll();
        }
        teams.clear();
    }

    /**
     * 团队聚合（F2/F3）：成员表 + 邮箱。所有写方法 synchronized 保护成员表。
     */
    public static final class Team {

        private final String name;
        private final TeamMode mode;
        private final Map<String, Member> members = new LinkedHashMap<>();
        private final FileMailBox mailBox; // Lead 的收件箱

        Team(String name, TeamMode mode, Path teamDir) {
            this.name = name;
            this.mode = mode;
            this.mailBox = new FileMailBox(teamDir.resolve("inboxes"));
        }

        public String name() {
            return name;
        }

        public TeamMode mode() {
            return mode;
        }

        public FileMailBox mailBox() {
            return mailBox;
        }

        /** 队员收件箱目录（<teamDir>/inboxes）。 */
        public Path inboxesDir() {
            return baseDirOf(this);
        }

        private static Path baseDirOf(Team t) {
            return TeamManager.teamsBaseDir().resolve(t.name).resolve("inboxes");
        }

        public synchronized void addMember(Member member) {
            members.put(member.name, member);
        }

        /** in-process 队员的运行线程登记。 */
        public synchronized void startMember(String memberName, Thread thread) {
            Member m = members.get(memberName);
            if (m != null) {
                m.thread = thread;
                m.active = true;
            }
        }

        public synchronized void stopMember(String memberName) {
            Member m = members.get(memberName);
            if (m != null) {
                m.active = false;
                if (m.thread != null) {
                    m.thread.interrupt(); // N3：虚拟线程 interrupt 退出
                }
            }
        }

        public synchronized void stopAll() {
            for (Member m : members.values()) {
                m.active = false;
                if (m.thread != null) {
                    m.thread.interrupt();
                }
            }
            // 外部后端（tmux）队员的窗口/进程清理（修复前只 interrupt in-process 线程，
            // tmux 窗口和队员 CLI 进程照跑——stopTmuxTeammate 写了没人调）
            if (mode == TeamMode.TMUX) {
                for (Member m : members.values()) {
                    if (m.thread == null) {
                        dinocode.teams.TmuxBackend.stopTmuxTeammate(name, m.name);
                    }
                }
            }
        }

        public synchronized Member getMember(String memberName) {
            return members.get(memberName);
        }

        public synchronized boolean hasMember(String memberName) {
            return members.containsKey(memberName);
        }

        public synchronized List<String> memberNames() {
            return List.copyOf(members.keySet());
        }

        /** 投递消息到收件人的 mailbox（F17/N6）。 */
        public void sendMessage(String from, String to, String text) {
            mailBox.send(to, new FileMailBox.MailMessage(from, text));
        }
    }

    /** 队员元信息（F2）：in-process 模式 agent/conv 有值；tmux 外部队员两者为 null。 */
    public static final class Member {
        public final String name;
        public final Object agent;   // in-process: Agent 实例（类型擦除避免循环依赖）
        public final Object conv;    // in-process: 对话（同上）
        public volatile boolean active;
        public volatile Thread thread;

        public Member(String name, Object agent, Object conv) {
            this.name = name;
            this.agent = agent;
            this.conv = conv;
            this.active = false;
        }

        /** 外部后端占位成员（F2）：agent 与 conv 为 null。 */
        public static Member external(String name) {
            return new Member(name, null, null);
        }

        public AtomicBoolean activeFlag() {
            return new AtomicBoolean(active);
        }
    }
}
