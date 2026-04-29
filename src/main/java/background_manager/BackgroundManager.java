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
 * 
 * <p>核心设计思想：让 Agent 主循环保持单线程，只把子进程 I/O 并行化。
 * 这样可以避免复杂的并发控制，同时又能让长时间运行的命令不阻塞 Agent。</p>
 * 
 * <h2>工作原理</h2>
 * <pre>
 * 1. run() 方法启动守护线程执行命令，立即返回 task_id
 * 2. 守护线程在后台执行命令，完成后结果进入通知队列
 * 3. Agent 主循环每轮调用 drainNotifications() 排空通知队列
 * 4. 通知被注入消息历史，LLM 可以感知后台任务结果
 * </pre>
 * 
 * <h2>线程安全保证</h2>
 * <ul>
 *   <li>tasks: ConcurrentHashMap，支持并发读写</li>
 *   <li>notificationQueue: ConcurrentLinkedQueue，无锁队列</li>
 *   <li>所有操作都是原子的，不需要显式加锁</li>
 * </ul>
 * 
 * @see BgTask 后台任务数据模型
 * @see BgNotification 通知数据模型
 */
public class BackgroundManager {

    // ================== 常量定义 ==================

    /** 
     * 工作目录
     * 所有后台命令都在此目录下执行，与 Agent 的工作目录一致
     */
    private static final Path WORKDIR = Paths.get(".").toAbsolutePath().normalize();

    /** 
     * 后台任务超时时间（毫秒）
     * 默认 300 秒（5 分钟），比同步 run() 的 120 秒更长
     * 因为后台任务通常是长时间运行的（如 dev server、build）
     */
    private static final Integer TIMEOUT_MS = 300_000;

    /** 
     * 通知队列中结果的最大长度
     * 通知只保留前 500 字符，避免消息历史膨胀
     * 完整输出可以通过 bg_status 工具查看
     */
    private static final Integer NOTIFICATION_RESULT_MAX_LENGTH = 500;

    /** 
     * 完整输出的最大长度
     * 限制为 50000 字符，防止内存溢出和上下文过长
     */
    private static final Integer OUTPUT_MAX_LENGTH = 50000;

    // ================== 核心组件 ==================

    /** 
     * 守护线程池，用于执行后台任务
     * 
     * <p>使用 CachedThreadPool，线程数按需创建，空闲 60 秒后回收。
     * 所有线程都是守护线程（daemon=true），主程序退出时自动终止。</p>
     * 
     * <p>守护线程的意义：
     * <ul>
     *   <li>用户关闭 Agent 时，后台任务也随之终止</li>
     *   <li>避免僵尸进程（如 dev server 在后台继续运行）</li>
     * </ul></p>
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bg-task-thread");
        t.setDaemon(true);  // 关键：设为守护线程
        return t;
    });

    /** 
     * 任务存储，key 是 task_id
     * 
     * <p>使用 ConcurrentHashMap 而非 HashMap，原因：
     * <ul>
     *   <li>主线程可能查询状态，同时守护线程在更新状态</li>
     *   <li>ConcurrentHashMap 支持并发读写，无需显式加锁</li>
     * </ul></p>
     */
    private final Map<String, BgTask> tasks = new ConcurrentHashMap<>();

    /** 
     * 线程安全的通知队列
     * 
     * <p>这是整个异步机制的核心：
     * <ul>
     *   <li>守护线程执行完命令后，结果 add() 到此队列</li>
     *   <li>主循环每轮 drainNotifications() 从此队列 poll()</li>
     *   <li>ConcurrentLinkedQueue 是无锁队列，高并发性能好</li>
     * </ul></p>
     */
    private final ConcurrentLinkedQueue<BgNotification> notificationQueue = new ConcurrentLinkedQueue<>();

    // ================== 公共方法 ==================

    /**
     * 启动后台任务，立即返回
     * 
     * <p>这是 LLM 调用 bg_run 工具时执行的方法。
     * 不会阻塞等待命令完成，而是立即返回 task_id。</p>
     * 
     * <h3>执行流程</h3>
     * <pre>
     * 1. 生成 8 位短 ID（UUID 前 8 位）
     * 2. 创建 BgTask 对象，状态设为 "running"
     * 3. 存入 tasks Map
     * 4. 提交到线程池（守护线程开始执行）
     * 5. 立即返回启动信息
     * </pre>
     *
     * @param command 要执行的命令（如 "npm run dev"）
     * @return 启动信息，包含 task_id，如 "Background task a3f2b1c4 started"
     */
    public String run(String command) {
        // 生成 8 位短 ID，便于 LLM 记忆和引用
        // UUID 格式：xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx，取前 8 位
        String taskId = UUID.randomUUID().toString().substring(0, 8);

        // 创建任务对象，记录初始状态
        BgTask task = new BgTask();
        task.setId(taskId);
        task.setCommand(command);
        task.setStatus("running");  // 初始状态为运行中
        task.setStartTime(System.currentTimeMillis());  // 记录启动时间，用于计算已运行时长
        tasks.put(taskId, task);

        // 提交到线程池，守护线程开始执行
        // submit() 立即返回，不会阻塞
        executor.submit(() -> execute(taskId, command));

        // 打印日志，方便调试
        System.out.println("[bg] Started task " + taskId + ": " +
                command.substring(0, Math.min(80, command.length())));
        
        // 返回启动信息，告知 LLM 可以用 bg_status 查询进度
        return "Background task " + taskId + " started. Use bg_status to check progress.";
    }

    /**
     * 查询后台任务状态
     * 
     * <p>这是 LLM 调用 bg_status 工具时执行的方法。
     * 返回任务的当前状态和（如果已完成）输出摘要。</p>
     *
     * @param taskId 任务 ID（由 run() 返回）
     * @return 任务状态信息，格式如下：
     *         <ul>
     *           <li>运行中：Task a3f2b1c4: running (45s elapsed)</li>
     *           <li>已完成：Task a3f2b1c4: completed\nOutput (last 500 chars): ...</li>
     *           <li>不存在：Error: Unknown task a3f2b1c4</li>
     *         </ul>
     */
    public String status(String taskId) {
        // 从 Map 中获取任务
        BgTask task = tasks.get(taskId);
        if (task == null) {
            return "Error: Unknown task " + taskId;
        }

        // 构建状态信息
        StringBuilder sb = new StringBuilder();
        sb.append("Task ").append(taskId).append(": ").append(task.getStatus());

        // 根据状态添加额外信息
        if ("running".equals(task.getStatus())) {
            // 运行中：显示已运行时长
            long elapsed = (System.currentTimeMillis() - task.getStartTime()) / 1000;
            sb.append(" (").append(elapsed).append("s elapsed)");
        } else if ("completed".equals(task.getStatus()) || "failed".equals(task.getStatus())) {
            // 已完成/失败：显示输出的最后 500 字符
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
     * 
     * <p>这是 Agent 主循环每轮调用的方法，在调用 LLM 之前执行。
     * 将所有积压的通知一次性取出，注入消息历史。</p>
     * 
     * <h3>为什么叫 "drain"（排空）？</h3>
     * <p>因为不是取一个，而是取走所有。poll() 是非阻塞的，
     * 队列为空时返回 null，所以用 while 循环直到队列为空。</p>
     *
     * @return 通知列表，可能为空（如果没有后台任务完成）
     */
    public List<BgNotification> drainNotifications() {
        List<BgNotification> notifications = new ArrayList<>();
        
        // 循环取出，直到队列为空
        // poll() 是原子操作，线程安全
        while (true) {
            BgNotification notif = notificationQueue.poll();
            if (notif == null) {
                break;  // 队列为空，退出循环
            }
            notifications.add(notif);
        }
        
        return notifications;
    }

    /**
     * 列出所有后台任务
     * 
     * <p>这是 LLM 调用 bg_list 工具时执行的方法。
     * 返回所有后台任务的概览，包括 ID、命令、状态。</p>
     *
     * @return 格式化的任务列表，如：
     *         <pre>
     *         === Background Tasks (3) ===
     *         [>] a3f2b1c4: npm run dev (running)
     *         [-] b7e9d2a1: mvn test (completed)
     *         [!] c5f8a3b2: gradle build (failed)
     *         </pre>
     */
    public String listAll() {
        if (tasks.isEmpty()) {
            return "No background tasks.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Background Tasks (").append(tasks.size()).append(") ===\n");

        // 遍历所有任务，格式化输出
        for (BgTask task : tasks.values()) {
            // 状态标记：[>] 运行中, [-] 已完成, [!] 失败
            String marker = "running".equals(task.getStatus()) ? "[>]" :
                    "completed".equals(task.getStatus()) ? "[-]" : "[!]";
            
            sb.append(marker).append(" ").append(task.getId())
                    .append(": ").append(task.getCommand(), 0, Math.min(60, task.getCommand().length()))
                    .append(" (").append(task.getStatus()).append(")\n");
        }

        return sb.toString();
    }

    // ================== 私有方法 ==================

    /**
     * 执行后台命令（在守护线程中运行）
     * 
     * <p>这是真正执行命令的方法，在守护线程中异步运行。
     * 执行完成后，结果进入通知队列，等待主循环取走。</p>
     * 
     * <h3>执行流程</h3>
     * <pre>
     * 1. 根据操作系统选择 shell（Windows 用 cmd，Linux/Mac 用 bash）
     * 2. 启动子进程
     * 3. 逐行读取输出（避免缓冲区满导致阻塞）
     * 4. 超时检测（300 秒）
     * 5. 更新任务状态
     * 6. 结果进入通知队列
     * </pre>
     *
     * @param taskId  任务 ID
     * @param command 要执行的命令
     */
    private void execute(String taskId, String command) {
        BgTask task = tasks.get(taskId);

        try {
            // 检测操作系统，选择对应的 shell
            boolean isWindows = System.getProperty("os.name")
                    .toLowerCase()
                    .contains("win");

            // 构建 ProcessBuilder
            // Windows: cmd /c command
            // Linux/Mac: bash -c command
            ProcessBuilder pb;
            if (isWindows) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            // 设置工作目录
            pb.directory(WORKDIR.toFile());
            // 合并 stdout 和 stderr，方便统一读取
            pb.redirectErrorStream(true);

            // 启动子进程
            Process process = pb.start();

            // 读取 子agent信息 
            // 创建缓冲读取器，逐行读取输出 
            // 注意：必须逐行读取，否则缓冲区满会导致子进程阻塞
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            StringBuilder output = new StringBuilder();
            String line;

            // 记录开始时间，用于超时检测
            long start = System.currentTimeMillis();

            // 逐行读取输出
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

                // 超时检测：如果超过 300 秒，强制终止进程

                if (System.currentTimeMillis() - start > TIMEOUT_MS) {
                    process.destroyForcibly();  // 强制终止
                    output.append("\nError: Timeout (").append(TIMEOUT_MS / 1000).append("s)");
                    break;
                }
            }

            // 处理输出结果：保留最后 50000 字符（后面的内容更重要）
            String result = output.toString().trim();
            if (result.isEmpty()) {
                result = "(no output)";
            }
            if (result.length() > OUTPUT_MAX_LENGTH) {
                result = result.substring(result.length() - OUTPUT_MAX_LENGTH);
            }

            // 更新任务状态为已完成
            task.setOutput(result);
            task.setStatus("completed");

            // 创建通知，进入通知队列
            // 注意：通知只保留前 500 字符，避免消息历史膨胀
            BgNotification notif = new BgNotification();
            notif.setTaskId(taskId);
            notif.setResult(result.substring(0, Math.min(NOTIFICATION_RESULT_MAX_LENGTH, result.length())));
            notificationQueue.add(notif);  // 进入队列，等待主循环取走

            // 打印日志
            System.out.println("[bg] Completed task " + taskId + " (" + result.length() + " chars)");

        } catch (Exception e) {
            // 异常处理：更新任务状态为失败
            String errorMsg = "Error: " + e.getMessage();
            task.setOutput(errorMsg);
            task.setStatus("failed");

            // 即使失败也要发送通知，让 LLM 知道任务失败了
            BgNotification notif = new BgNotification();
            notif.setTaskId(taskId);
            notif.setResult(errorMsg.substring(0, Math.min(NOTIFICATION_RESULT_MAX_LENGTH, errorMsg.length())));
            notificationQueue.add(notif);

            // 打印错误日志
            System.out.println("[bg] Failed task " + taskId + ": " + e.getMessage());
        }
    }
}
