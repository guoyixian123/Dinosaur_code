package dinocode.subagent;

import dinocode.core.Message;
import dinocode.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台子 Agent 任务管理器（ch13 F5/T5~T6）：
 * 状态机 PENDING→RUNNING→COMPLETED/FAILED/CANCELLED、TaskNotification 通知队列、
 * 后台虚拟线程启动。所有公共方法 synchronized（N3）。
 */
public final class SubAgentTaskManager {

    public enum TaskStatus {PENDING, RUNNING, COMPLETED, FAILED, CANCELLED}

    /** 通知（主 Agent 下一轮 drain 后注入对话）。 */
    public record TaskNotification(String taskId, String agentName, String status, String summary) {
    }

    /** 单个任务的状态条目。 */
    static final class TaskEntry {
        final String id;
        final String name;
        TaskStatus status = TaskStatus.PENDING;
        String output = "";
        String error = "";
        Thread thread;

        TaskEntry(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final Map<String, TaskEntry> tasks = new LinkedHashMap<>();
    private final List<TaskNotification> notifications = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger();

    /** 子 Agent 执行委托：由 AgentTool 注入（闭包捕获 client/registry 等运行时依赖）。 */
    public interface SubAgentRunner {
        /**
         * 阻塞执行子 Agent，返回最终文本；抛异常视为失败。
         *
         * @param history  初始对话（普通 spawn 为空列表；fork 场景为完整父对话拷贝）
         */
        String run(SubAgentSpec spec, String prompt, ToolRegistry filtered, List<Message> history)
                throws Exception;
    }

    private final SubAgentRunner runner;

    public SubAgentTaskManager(SubAgentRunner runner) {
        this.runner = runner;
    }

    public synchronized String createTask(String agentName) {
        String id = "task_" + nextId.incrementAndGet();
        tasks.put(id, new TaskEntry(id, agentName));
        return id;
    }

    public synchronized void setRunning(String taskId, Thread thread) {
        TaskEntry e = tasks.get(taskId);
        if (e != null) {
            e.status = TaskStatus.RUNNING;
            e.thread = thread;
        }
    }

    public synchronized void setCompleted(String taskId, String output) {
        TaskEntry e = tasks.get(taskId);
        if (e != null && e.status == TaskStatus.RUNNING) {
            e.status = TaskStatus.COMPLETED;
            e.output = output;
            notifications.add(new TaskNotification(taskId, e.name, "completed", truncate(output)));
        }
    }

    public synchronized void setFailed(String taskId, String error) {
        TaskEntry e = tasks.get(taskId);
        if (e != null && e.status != TaskStatus.CANCELLED) {
            e.status = TaskStatus.FAILED;
            e.error = error;
            notifications.add(new TaskNotification(taskId, e.name, "failed", truncate(error)));
        }
    }

    /** 中断后台线程并置 CANCELLED（F11/N2）。 */
    public synchronized boolean cancelTask(String taskId) {
        TaskEntry e = tasks.get(taskId);
        if (e == null) {
            return false;
        }
        if (e.thread != null) {
            e.thread.interrupt(); // N2：Thread.interrupt 受控取消
        }
        e.status = TaskStatus.CANCELLED;
        notifications.add(new TaskNotification(taskId, e.name, "cancelled", "任务被取消"));
        return true;
    }

    /** 一次性取出通知并清空（F5）。 */
    public synchronized List<TaskNotification> drainNotifications() {
        List<TaskNotification> out = List.copyOf(notifications);
        notifications.clear();
        return out;
    }

    public synchronized TaskEntry getTask(String taskId) {
        return tasks.get(taskId);
    }

    public synchronized List<TaskEntry> listTasks() {
        return List.copyOf(tasks.values());
    }

    /**
     * 后台启动子 Agent（F5/T6）：createTask → virtual thread → 过滤 registry →
     * 跑子 Agent → 完成/失败写状态并入通知队列。
     */
    public String spawnSubAgent(SubAgentSpec spec, String prompt, ToolRegistry parentRegistry) {
        String taskId = createTask(spec.name());
        Thread thread = Thread.ofVirtual().start(() -> {
            setRunning(taskId, Thread.currentThread());
            try {
                ToolRegistry filtered = ToolFilter.filterForAgent(parentRegistry, spec);
                String output = runner.run(spec, prompt, filtered, List.of());
                setCompleted(taskId, output);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                setFailed(taskId, "Interrupted");
            } catch (Exception e) {
                setFailed(taskId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
        setRunning(taskId, thread);
        return taskId;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 2000 ? s : s.substring(0, 2000) + "…";
    }
}
