package dinocode.teams;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * tmux 后端（ch15 F9/T3）：外部队员进程跑在 tmux 后台窗口。
 */
public final class TmuxBackend {

    private static final Logger LOG = Logger.getLogger(TmuxBackend.class.getName());
    private static final int TIMEOUT_SECONDS = 30;

    private TmuxBackend() {
    }

    /** 创建后台窗口跑队员 CLI；失败抛 RuntimeException（F9）。 */
    public static String spawnTmuxTeammate(String teamName, String memberName, String cliCommand) {
        String paneName = teamName + "-" + memberName;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "tmux", "new-window", "-d", "-n", paneName, cliCommand);
            Process process = pb.start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("Failed to spawn tmux window: timeout");
            }
            if (process.exitValue() != 0) {
                String err = new String(process.getErrorStream().readAllBytes());
                throw new RuntimeException("Failed to spawn tmux window: " + err.strip());
            }
            return paneName;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to spawn tmux window: " + e.getMessage(), e);
        }
    }

    /** 停队员：先 C-c 再 kill-window；best-effort 失败仅 log（F9）。 */
    public static void stopTmuxTeammate(String teamName, String memberName) {
        String paneName = teamName + "-" + memberName;
        try {
            new ProcessBuilder("tmux", "send-keys", "-t", paneName, "C-c").start().waitFor(5, TimeUnit.SECONDS);
            Thread.sleep(200);
            new ProcessBuilder("tmux", "kill-window", "-t", paneName).start().waitFor(5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            LOG.fine(() -> "[team] tmux stop failed (best-effort): " + e.getMessage());
        }
    }
}
