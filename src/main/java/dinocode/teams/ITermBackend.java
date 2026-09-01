package dinocode.teams;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * iTerm2 后端（ch15 F10/T4）：osascript AppleScript 操控 tab。best-effort。
 */
public final class ITermBackend {

    private static final Logger LOG = Logger.getLogger(ITermBackend.class.getName());
    private static final int TIMEOUT_SECONDS = 30;

    private ITermBackend() {
    }

    /** 在 iTerm2 当前 window 创建 tab 跑队员 CLI（F10/N8：双引号转义）。 */
    public static String spawnITermTeammate(String teamName, String memberName, String cliCommand) {
        String tabName = teamName + "-" + memberName;
        String escaped = cliCommand.replace("\"", "\\\"");
        String script = """
                tell application "iTerm"
                    activate
                    tell current window
                        create window with default profile
                        tell current session
                            set name to "%s"
                            write text "%s"
                        end tell
                    end tell
                end tell""".formatted(tabName, escaped);
        try {
            ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
            Process process = pb.start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("Failed to spawn iTerm tab: timeout");
            }
            if (process.exitValue() != 0) {
                String err = new String(process.getErrorStream().readAllBytes());
                throw new RuntimeException("Failed to spawn iTerm tab: " + err.strip());
            }
            return tabName;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to spawn iTerm tab: " + e.getMessage(), e);
        }
    }

    /** 遍历 window/tab 找名字匹配的 close；best-effort（N8：找不到 tab 不报错）。 */
    public static void stopITermTeammate(String teamName, String memberName) {
        String tabName = teamName + "-" + memberName;
        String script = """
                tell application "iTerm"
                    repeat with w in windows
                        repeat with t in tabs of w
                            if name of current session of t is "%s" then
                                close t
                                return
                            end if
                        end repeat
                    end repeat
                end tell""".formatted(tabName);
        try {
            ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
            Process process = pb.start();
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            LOG.fine(() -> "[team] iTerm stop failed (best-effort): " + e.getMessage());
        }
    }
}
