package dinocode.worktree;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 会话级 worktree 单例（ch14 F8/T7）：Jackson 序列化到 .dino/worktree_session.json，
 * snake_case 字段映射；ignoreUnknown 兼容字段增减。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorktreeSession(
        @JsonProperty("original_cwd") String originalCwd,
        @JsonProperty("worktree_path") String worktreePath,
        @JsonProperty("worktree_name") String worktreeName,
        @JsonProperty("worktree_branch") String worktreeBranch,
        @JsonProperty("original_branch") String originalBranch,
        @JsonProperty("original_head_commit") String originalHeadCommit,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("creation_duration_ms") long creationDurationMs) {
}
