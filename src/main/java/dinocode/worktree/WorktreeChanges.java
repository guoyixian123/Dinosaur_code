package dinocode.worktree;

import java.io.IOException;
import java.nio.file.Path;

/**
 * worktree 变更检测（ch14 F14/T5）：fail-closed——状态无法验证时一律按「有变更」处理。
 */
public final class WorktreeChanges {

    /** 变更计数（F13/N7：供错误信息生成具体数字）。 */
    public record ChangeSummary(int changedFiles, int commits) {
    }

    private WorktreeChanges() {
    }

    /** 有变更？（F12/F14）：porcelain 非空、有未推送 commit、或任何一步失败 → true。 */
    public static boolean hasChanges(Path wtPath, String headCommit) {
        try {
            String status = WorktreeManager.runGitQuiet(wtPath, "status", "--porcelain");
            if (status != null && !status.isBlank()) {
                return true;
            }
            if (headCommit != null && !headCommit.isBlank()) {
                String ahead = WorktreeManager.runGitQuiet(wtPath,
                        "rev-list", "--count", headCommit + "..HEAD");
                if (ahead == null) {
                    return true; // fail-closed
                }
                try {
                    if (Integer.parseInt(ahead.strip()) > 0) {
                        return true;
                    }
                } catch (NumberFormatException e) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true; // fail-closed（F14）
        }
    }

    /**
     * 变更计数（F13）：无法验证时返回 null（强制调用方按「未知即不安全」处理）；
     * originalHeadCommit 为空也返回 null（无法算 commit 数）。
     */
    public static ChangeSummary countChanges(Path wtPath, String originalHeadCommit) {
        if (originalHeadCommit == null || originalHeadCommit.isBlank()) {
            return null;
        }
        try {
            String status = WorktreeManager.runGitQuiet(wtPath, "status", "--porcelain");
            if (status == null) {
                return null;
            }
            int changedFiles = (int) java.util.Arrays.stream(status.split("\n"))
                    .filter(l -> !l.isBlank())
                    .count();
            String commitsOut = WorktreeManager.runGitQuiet(wtPath,
                    "rev-list", "--count", originalHeadCommit + "..HEAD");
            if (commitsOut == null) {
                return null;
            }
            int commits;
            try {
                commits = Integer.parseInt(commitsOut.strip());
            } catch (NumberFormatException e) {
                return null;
            }
            return new ChangeSummary(changedFiles, commits);
        } catch (Exception e) {
            return null; // fail-closed
        }
    }
}
