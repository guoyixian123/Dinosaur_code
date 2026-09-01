package dinocode.teams;

import dinocode.tool.Result;
import dinocode.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队三件套工具（ch15 F17~F19/T10）：SendMessage / TeamCreate / TeamDelete。
 */
public final class TeamTools {

    private TeamTools() {
    }

    /** 队员互发消息（F17）：遍历所有团队找收件人。 */
    public static final class SendMessageTool implements Tool {

        private final TeamManager teamMgr;
        private final String senderName;

        public SendMessageTool(TeamManager teamMgr, String senderName) {
            this.teamMgr = teamMgr;
            this.senderName = senderName;
        }

        @Override
        public String name() {
            return "SendMessage";
        }

        @Override
        public String description() {
            return "给团队里的其他队员发消息。";
        }

        @Override
        public Map<String, Object> schema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("to", Map.of("type", "string", "description", "收件队员名或 lead"));
            props.put("content", Map.of("type", "string", "description", "消息内容"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("to", "content"));
            return schema;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public Result execute(Map<String, Object> args) {
            String to = str(args.get("to"));
            String content = str(args.get("content"));
            if (to == null || to.isBlank() || content == null || content.isBlank()) {
                return Result.error("Error: to and content are required");
            }
            // F17：遍历所有团队找 to 所在团队
            for (TeamManager.Team team : teamMgr.listTeams()) {
                if (team.hasMember(to) || TeammateRunner.LEAD_NAME.equals(to) && team.name() != null) {
                    // N6 简化：直接走团队的 mailbox 投递
                    team.sendMessage(senderName, to, content);
                    return Result.ok("消息已发送给 " + to + "（团队 " + team.name() + "）");
                }
            }
            return Result.error("recipient '" + to + "' not found in any team");
        }

        private static String str(Object o) {
            return o == null ? null : String.valueOf(o);
        }
    }

    /** 创建团队（F18）。 */
    public static final class TeamCreateTool implements Tool {

        private final TeamManager teamMgr;

        public TeamCreateTool(TeamManager teamMgr) {
            this.teamMgr = teamMgr;
        }

        @Override
        public String name() {
            return "TeamCreate";
        }

        @Override
        public String description() {
            return "创建一个 Agent 团队（按环境自动选择 tmux 或进程内后端）。";
        }

        @Override
        public Map<String, Object> schema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("team_name", Map.of("type", "string", "description", "团队名"));
            props.put("description", Map.of("type", "string", "description", "团队用途描述（可选）"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("team_name"));
            return schema;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public Result execute(Map<String, Object> args) {
            String teamName = str(args.get("team_name"));
            if (teamName == null || teamName.isBlank()) {
                return Result.error("Error: team_name is required");
            }
            // F18：同名去重 -2/-3...
            String finalName = teamName;
            int suffix = 2;
            while (teamMgr.getTeam(finalName) != null) {
                finalName = teamName + "-" + suffix++;
            }
            TeamManager.Team team = teamMgr.createTeam(finalName);
            return Result.ok(String.format(
                    "Team \"%s\" created (mode: %s). Use Agent tool with team_name=\"%s\" to add teammates.",
                    finalName, team.mode().name().toLowerCase(), finalName));
        }

        private static String str(Object o) {
            return o == null ? null : String.valueOf(o);
        }
    }

    /** 删除团队（F19）。 */
    public static final class TeamDeleteTool implements Tool {

        private final TeamManager teamMgr;

        public TeamDeleteTool(TeamManager teamMgr) {
            this.teamMgr = teamMgr;
        }

        @Override
        public String name() {
            return "TeamDelete";
        }

        @Override
        public String description() {
            return "删除团队并停止所有队员。";
        }

        @Override
        public Map<String, Object> schema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("team_name", Map.of("type", "string", "description", "要删除的团队名"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("team_name"));
            return schema;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public Result execute(Map<String, Object> args) {
            String teamName = str(args.get("team_name"));
            if (teamName == null || teamName.isBlank()) {
                return Result.error("Error: team_name is required");
            }
            TeamManager.Team team = teamMgr.getTeam(teamName);
            if (team == null) {
                return Result.error("Error: team '" + teamName + "' not found");
            }
            List<String> members = team.memberNames();
            teamMgr.deleteTeam(teamName); // 内部 stopAll 中断队员
            return Result.ok(String.format("Team \"%s\" deleted. Stopped %d member(s): %s",
                    teamName, members.size(), String.join(", ", members)));
        }

        private static String str(Object o) {
            return o == null ? null : String.valueOf(o);
        }
    }
}
