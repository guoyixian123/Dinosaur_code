package dinocode.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hook 动作执行器（ch12 F17~F26）：shell / prompt / http / subagent 占位。
 * 拦截事件下的约定信号——shell exit 2（stderr 为原因，F19）、http decision=block（F25）。
 * hook 自身失败只返回 error、不拦截（G9/F29）。
 */
public final class HookExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)}");

    private final HttpClient http;

    public HookExecutor() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** 单次动作执行结果。 */
    public record ExecutionResult(boolean blocked, String reason, String prompt, Throwable error) {
        public static ExecutionResult empty() {
            return new ExecutionResult(false, null, null, null);
        }

        static ExecutionResult blocked(String reason) {
            return new ExecutionResult(true, reason, null, null);
        }

        static ExecutionResult failure(Throwable t) {
            return new ExecutionResult(false, null, null, t);
        }
    }

    /** 按动作类型分发执行。blocking = 当前事件是拦截类事件。 */
    public ExecutionResult run(HookRule rule, HookRule.Payload payload, boolean blocking) {
        try {
            if (rule.action() instanceof Action.Shell shell) {
                return runShell(shell, payload, blocking, rule.effectiveTimeout());
            }
            if (rule.action() instanceof Action.Prompt prompt) {
                return new ExecutionResult(false, null, prompt.text(), null); // F22 永不拦截
            }
            if (rule.action() instanceof Action.Http httpAction) {
                return runHttp(httpAction, payload, blocking, rule.effectiveTimeout());
            }
            if (rule.action() instanceof Action.Subagent sub) {
                // F26/N8 占位：固定格式日志，不报错不拦截
                System.err.println("[hook subagent] not yet implemented, skipped: " + sub.agentName());
                return ExecutionResult.empty();
            }
            return ExecutionResult.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResult.failure(e);
        } catch (Exception e) {
            return ExecutionResult.failure(e);
        }
    }

    // ---------- shell（F17~F19） ----------

    private ExecutionResult runShell(Action.Shell shell, HookRule.Payload payload,
                                     boolean blocking, Duration timeout) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", shell.command())
                .redirectErrorStream(false);
        // F17 契约：payload 序列化成单行 JSON 通过 stdin 传入；
        // ProcessRunner 边读边等——输出超管道缓冲的正常 hook 不再被误判超时
        dinocode.tool.ProcessRunner.Output out = dinocode.tool.ProcessRunner.run(
                pb, timeout.toSeconds(), payload.toSortedJson() + "\n");
        if (out.timedOut()) {
            return ExecutionResult.failure(new IOException("hook 命令超时"));
        }
        String stderr = out.stderr().strip();
        String stdout = out.stdout().strip();
        int code = out.exitCode();

        if (blocking && code == 2) { // F19：拦截命中
            String reason = !stderr.isEmpty() ? stderr : stdout;
            return ExecutionResult.blocked(reason);
        }
        if (code != 0) {
            return ExecutionResult.failure(new IOException("exit code " + code
                    + (stderr.isEmpty() ? "" : ": " + firstLine(stderr))));
        }
        return ExecutionResult.empty(); // exit 0 放行
    }

    // ---------- http（F23~F25） ----------

    private ExecutionResult runHttp(Action.Http action, HookRule.Payload payload,
                                    boolean blocking, Duration timeout) throws Exception {
        String body = action.bodyTemplate() == null || action.bodyTemplate().isEmpty()
                ? payload.toSortedJson()
                : renderTemplate(action.bodyTemplate(), payload);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(action.url()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        String method = action.method() == null ? "POST" : action.method().toUpperCase();
        if (!method.equals("POST")) {
            builder = HttpRequest.newBuilder(URI.create(action.url()))
                    .timeout(timeout)
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        if (action.headers() != null) {
            action.headers().forEach(builder::header);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (blocking && response.statusCode() >= 200 && response.statusCode() < 300) {
            // F25：2xx 且 decision=block → 拦截
            try {
                JsonNode node = JSON.readTree(response.body());
                if ("block".equalsIgnoreCase(node.path("decision").asText(""))) {
                    return ExecutionResult.blocked(node.path("reason").asText(""));
                }
            } catch (Exception e) {
                return ExecutionResult.failure(e); // JSON 解析失败：失败不拦截
            }
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return ExecutionResult.failure(new IOException("HTTP " + response.statusCode()));
        }
        return ExecutionResult.empty();
    }

    /** ${field} / ${nested.path} 占位渲染（F23/N10）：不存在的字段渲染为空串。 */
    static String renderTemplate(String template, HookRule.Payload payload) {
        Matcher m = TEMPLATE_VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = payload.getByPath(m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String firstLine(String s) {
        int idx = s.indexOf('\n');
        return idx < 0 ? s : s.substring(0, idx);
    }
}
