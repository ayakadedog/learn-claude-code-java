package background_manager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台任务管理器
 * 用线程安全的通知队列追踪后台任务，让 Agent 主循环保持单线程
 * 只有子进程 I/O 被并行化
 */
public class BackgroundManager {

    /** 工作目录 */
    private static final Path WORKDIR = Paths.get(".").toAbsolutePath().normalize();

    /** 后台任务超时时间（300秒） */
    private static final Integer TIMEOUT_MS = 300_000;

    /** 通知队列中结果的最大长度 */
    private static final Integer NOTIFICATION_RESULT_MAX_LENGTH = 500;

    /** 完整输出的最大长度 */
    private static final Integer OUTPUT_MAX_LENGTH = 50000;

    /** 守护线程池，用于执行后台任务 */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bg-task-thread");
        t.setDaemon(true);
        return t;
    });

    /** 任务存储，key 是 task_id */
    private final Map<String, BgTask> tasks = new ConcurrentHashMap<>();

    /** 线程安全的通知队列，子进程完成后结果进入此队列 */
    private final ConcurrentLinkedQueue<BgNotification> notificationQueue = new ConcurrentLinkedQueue<>();

    /**
     * 启动后台任务，立即返回
     * 命令在守护线程中执行，完成后结果进入通知队列
     *
     * @param command 要执行的命令
     * @return 启动信息，包含 task_id
     */
    public String run(String command) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);

        BgTask task = new BgTask();
        task.setId(taskId);
        task.setCommand(command);
        task.setStatus("running");
        task.setStartTime(System.currentTimeMillis());
        tasks.put(taskId, task);

        executor.submit(() -> execute(taskId, command));

        System.out.println("[bg] Started task " + taskId + ": " +
                command.substring(0, Math.min(80, command.length())));
        return "Background task " + taskId + " started. Use bg_status to check progress.";
    }

    /**
     * 查询后台任务状态
     *
     * @param taskId 任务 ID
     * @return 任务状态信息
     */
    public String status(String taskId) {
        BgTask task = tasks.get(taskId);
        if (task == null) {
            return "Error: Unknown task " + taskId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Task ").append(taskId).append(": ").append(task.getStatus());

        if ("running".equals(task.getStatus())) {
            long elapsed = (System.currentTimeMillis() - task.getStartTime()) / 1000;
            sb.append(" (").append(elapsed).append("s elapsed)");
        } else if ("completed".equals(task.getStatus()) || "failed".equals(task.getStatus())) {
            String output = task.getOutput();
            if (output != null && !output.isEmpty()) {
                sb.append("\nOutput (last 500 chars): ")
                        .append(output.substring(Math.max(0, output.length() - 500)));
            }
        }

        return sb.toString();
    }

    /**
     * 排空通知队列，取出所有已完成任务的通知
     * 在每轮 LLM 调用前调用
     *
     * @return 通知列表，可能为空
     */
    public List<BgNotification> drainNotifications() {
        List<BgNotification> notifications = new ArrayList<>();
        while (true) {
            BgNotification notif = notificationQueue.poll();
            if (notif == null) {
                break;
            }
            notifications.add(notif);
        }
        return notifications;
    }

    /**
     * 列出所有后台任务
     *
     * @return 格式化的任务列表
     */
    public String listAll() {
        if (tasks.isEmpty()) {
            return "No background tasks.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Background Tasks (").append(tasks.size()).append(") ===\n");

        for (BgTask task : tasks.values()) {
            String marker = "running".equals(task.getStatus()) ? "[>]" :
                    "completed".equals(task.getStatus()) ? "[-]" : "[!]";
            sb.append(marker).append(" ").append(task.getId())
                    .append(": ").append(task.getCommand(), 0, Math.min(60, task.getCommand().length()))
                    .append(" (").append(task.getStatus()).append(")\n");
        }

        return sb.toString();
    }

    /**
     * 执行后台命令（在守护线程中运行）
     * 子进程完成后，结果进入通知队列
     *
     * @param taskId  任务 ID
     * @param command 要执行的命令
     */
    private void execute(String taskId, String command) {
        BgTask task = tasks.get(taskId);

        try {
            boolean isWindows = System.getProperty("os.name")
                    .toLowerCase()
                    .contains("win");

            ProcessBuilder pb;
            if (isWindows) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            pb.directory(WORKDIR.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            StringBuilder output = new StringBuilder();
            String line;

            long start = System.currentTimeMillis();

            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

                if (System.currentTimeMillis() - start > TIMEOUT_MS) {
                    process.destroyForcibly();
                    output.append("\nError: Timeout (").append(TIMEOUT_MS / 1000).append("s)");
                    break;
                }
            }

            String result = output.toString().trim();
            if (result.isEmpty()) {
                result = "(no output)";
            }
            result = result.substring(0, Math.min(OUTPUT_MAX_LENGTH, result.length()));

            task.setOutput(result);
            task.setStatus("completed");

            // 结果进入通知队列（截断到 500 字符，避免上下文膨胀）
            BgNotification notif = new BgNotification();
            notif.setTaskId(taskId);
            notif.setResult(result.substring(0, Math.min(NOTIFICATION_RESULT_MAX_LENGTH, result.length())));
            notificationQueue.add(notif);

            System.out.println("[bg] Completed task " + taskId + " (" + result.length() + " chars)");

        } catch (Exception e) {
            String errorMsg = "Error: " + e.getMessage();
            task.setOutput(errorMsg);
            task.setStatus("failed");

            BgNotification notif = new BgNotification();
            notif.setTaskId(taskId);
            notif.setResult(errorMsg.substring(0, Math.min(NOTIFICATION_RESULT_MAX_LENGTH, errorMsg.length())));
            notificationQueue.add(notif);

            System.out.println("[bg] Failed task " + taskId + ": " + e.getMessage());
        }
    }
}
