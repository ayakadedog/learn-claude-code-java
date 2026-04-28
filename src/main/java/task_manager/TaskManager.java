package task_manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TaskManager - 持久化任务管理器
 * <p>
 * 核心设计：每个任务一个 JSON 文件，支持 CRUD + 依赖图（DAG）
 * <p>
 * 依赖图模型：
 * <pre>
 *   Task 1 (completed) ──blocks──→ Task 3 (unblocked)
 *   Task 2 (completed) ──blocks──→ Task 3 (unblocked)
 *   Task 3 (pending)    ──blocks──→ Task 4 (blocked)
 * </pre>
 * <p>
 * 每个任务有两条依赖边：
 * - blockedBy：入边，我被谁阻塞（我等谁先完成）
 * - blocks：出边，我阻塞了谁（谁等我完成）
 * <p>
 * 完成任务时自动解除依赖（clearDependency），将当前任务 ID 从下游任务的 blockedBy 中移除，解锁后续任务
 * <p>
 * 文件存储结构：
 * <pre>
 * .tasks/
 * ├── task_1.json   ← {"id":1, "subject":"设计数据库", "status":"completed", ...}
 * ├── task_2.json   ← {"id":2, "subject":"写API", "status":"in_progress", ...}
 * └── task_3.json   ← {"id":3, "subject":"前端对接", "status":"pending", "blockedBy":[2]}
 * </pre>
 */
public class TaskManager {

    /** 任务文件名前缀，格式为 task_{id}.json */
    private static final String FILE_PREFIX = "task_";

    /** 任务文件名后缀 */
    private static final String FILE_SUFFIX = ".json";

    /**
     * 最大允许的任务数量
     * 防止 AI 疯狂创建任务不执行，硬限制兜底
     */
    private static final Integer MAX_TASKS = 10;

    /**
     * 合法的任务状态集合
     * - pending:     待完成，刚创建的默认状态
     * - in_progress: 正在进行，AI 开始执行实际工作前必须标记此状态
     * - completed:   已完成，会自动触发依赖解除
     * - cancelled:   已取消
     */
    private static final Set<String> VALID_STATUSES = Set.of(
            "pending", "in_progress", "completed", "cancelled"
    );

    /** 任务文件存储目录（如 .tasks/） */
    private final Path tasksDir;

    /** Jackson JSON 序列化/反序列化器，开启缩进输出方便调试查看 */
    private final ObjectMapper mapper;

    /**
     * 下一个可用 ID
     * 初始化时通过扫描目录中已有文件确定最大 ID，然后 +1
     * 保证 ID 单调递增，不会重复
     */
    private int nextId;

    /**
     * 构造器：初始化任务目录和 ID 计数器
     *
     * @param tasksDir 任务文件存储目录（如 WORKDIR/.tasks）
     */
    public TaskManager(Path tasksDir) {
        this.tasksDir = tasksDir;
        this.mapper = new ObjectMapper();
        // 开启缩进输出，让 JSON 文件可读性更好
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 确保目录存在，不存在则创建
        try {
            Files.createDirectories(tasksDir);
        } catch (IOException e) {
            System.err.println("[TaskManager] Failed to create tasks directory: " + e.getMessage());
        }

        // 扫描已有文件，确定下一个可用 ID
        // 例如目录下有 task_1.json ~ task_5.json，则 nextId = 6
        this.nextId = maxId() + 1;
    }

    /**
     * 创建新任务
     * <p>
     * 流程：
     * 1. 校验 subject 不为空
     * 2. 检查任务数量是否超过上限
     * 3. 创建 TaskItem 对象，ID 自增
     * 4. 保存到 JSON 文件
     * 5. 检查未完成任务数，超过 3 个时附带执行引导提醒
     *
     * @param subject     任务主题（必填）
     * @param description 任务描述（可选，传空字符串即可）
     * @return 创建的任务 JSON 字符串，可能附带执行引导提醒
     */
    public String create(String subject, String description) {
        // 校验：subject 不能为空
        if (subject == null || subject.trim().isEmpty()) {
            return "Error: subject is required";
        }

        // 检查任务数量上限，防止 AI 疯狂开任务不执行
        Integer currentCount = loadAll().size();
        if (currentCount >= MAX_TASKS) {
            return "Error: Max " + MAX_TASKS + " tasks allowed. Complete existing tasks before creating new ones. "
                    + "Use task_update to mark a task in_progress, then do the actual work with run/read/write/edit.";
        }

        // 创建任务对象，使用 nextId 作为 ID
        TaskItem task = new TaskItem(nextId, subject.trim());
        // 设置描述（可选）
        if (description != null && !description.trim().isEmpty()) {
            task.setDescription(description.trim());
        }

        // 持久化到文件
        save(task);
        // ID 自增，为下次创建准备
        nextId++;

        // 统计未完成任务数（pending + in_progress），用于执行引导
        Integer pendingCount = 0;
        for (TaskItem t : loadAll()) {
            if (!"completed".equals(t.getStatus()) && !"cancelled".equals(t.getStatus())) {
                pendingCount++;
            }
        }

        System.out.println("[TaskManager] Created task #" + task.getId() + ": " + task.getSubject());

        // 返回结果中附带执行引导
        // 当未完成任务 ≥ 3 个时，提醒 AI 停止规划、开始执行
        String result = toJson(task);
        if (pendingCount >= 3) {
            result += "\n\n[REMINDER] You have " + pendingCount + " unfinished tasks. "
                    + "Stop creating and start executing! "
                    + "Use task_update with status=in_progress on the first unblocked task, "
                    + "then use run/read/write/edit to do the work.";
        }
        return result;
    }

    /**
     * 更新任务（状态变更 + 依赖关联）
     * <p>
     * 三种操作可组合使用：
     * 1. 状态变更：status 参数，如 "in_progress" / "completed"
     * 2. 新增阻塞依赖：addBlockedBy，表示"我被谁阻塞"（入边）
     * 3. 新增阻塞关系：addBlocks，表示"我阻塞了谁"（出边）
     * <p>
     * 依赖双向维护原则：
     * A blockedBy B ⟺ B blocks A
     * 添加任何一条边时，必须同时维护对端任务的对应字段
     * <p>
     * 完成任务时自动解除依赖：
     * - 将当前任务 ID 从下游任务的 blockedBy 中移除
     * - 将下游任务 ID 从当前任务的 blocks 中移除
     *
     * @param taskId        任务 ID
     * @param status        新状态（可选，传 null 则不更新）
     * @param addBlockedBy  新增阻塞依赖：被哪个任务阻塞（传入任务 ID）
     * @param addBlocks     新增阻塞关系：阻塞了哪个任务（传入任务 ID）
     * @return 更新后的任务 JSON 字符串
     */
    public String update(Integer taskId, String status,
                         Integer addBlockedBy, Integer addBlocks) {
        // 加载任务，不存在则报错
        TaskItem task = load(taskId);
        if (task == null) {
            return "Error: Task #" + taskId + " not found";
        }

        // === 状态变更 ===
        if (status != null && !status.trim().isEmpty()) {
            String newStatus = status.trim().toLowerCase();
            // 校验状态值合法性
            if (!VALID_STATUSES.contains(newStatus)) {
                return "Error: Invalid status '" + newStatus + "'. Valid: " + VALID_STATUSES;
            }
            task.setStatus(newStatus);

            // 完成时自动解除依赖（核心逻辑）
            // 例如：Task 2 完成 → 从 Task 3 的 blockedBy 中移除 2 → Task 3 解锁
            if ("completed".equals(newStatus)) {
                clearDependency(task);
            }
        }

        // === 新增 blockedBy 依赖（双向维护） ===
        // 含义：当前任务被 addBlockedBy 阻塞，即 addBlockedBy 完成后当前任务才能开始
        if (addBlockedBy != null) {
            // 加载阻塞方任务
            TaskItem blocker = load(addBlockedBy);
            if (blocker == null) {
                return "Error: Blocker task #" + addBlockedBy + " not found";
            }
            // 在当前任务的 blockedBy 中添加阻塞方 ID（入边）
            if (!task.getBlockedBy().contains(addBlockedBy)) {
                task.getBlockedBy().add(addBlockedBy);
            }
            // 在阻塞方任务的 blocks 中添加当前任务 ID（出边，双向维护）
            if (!blocker.getBlocks().contains(taskId)) {
                blocker.getBlocks().add(taskId);
                save(blocker);
            }
        }

        // === 新增 blocks 依赖（双向维护） ===
        // 含义：当前任务阻塞了 addBlocks，即当前任务完成后 addBlocks 才能开始
        if (addBlocks != null) {
            // 加载被阻塞方任务
            TaskItem blocked = load(addBlocks);
            if (blocked == null) {
                return "Error: Blocked task #" + addBlocks + " not found";
            }
            // 在当前任务的 blocks 中添加被阻塞方 ID（出边）
            if (!task.getBlocks().contains(addBlocks)) {
                task.getBlocks().add(addBlocks);
            }
            // 在被阻塞方任务的 blockedBy 中添加当前任务 ID（入边，双向维护）
            if (!blocked.getBlockedBy().contains(taskId)) {
                blocked.getBlockedBy().add(taskId);
                save(blocked);
            }
        }

        // 保存更新后的任务
        save(task);
        System.out.println("[TaskManager] Updated task #" + taskId);
        return toJson(task);
    }

    /**
     * 获取单个任务详情
     *
     * @param taskId 任务 ID
     * @return 任务 JSON 字符串，或错误信息
     */
    public String get(Integer taskId) {
        TaskItem task = load(taskId);
        if (task == null) {
            return "Error: Task #" + taskId + " not found";
        }
        return toJson(task);
    }

    /**
     * 列出所有任务（渲染为可读文本，供 AI 阅读）
     * <p>
     * 输出格式示例：
     * <pre>
     * === Task Graph (4 tasks) ===
     *
     * [x] #1: 设计数据库表  [blocks: [2]]
     * [>] #2: 写后端API  [blocked by: [1]]  [blocks: [3, 4]]
     * [ ] #3: 写前端页面  [blocked by: [2]]  [blocks: [4]]
     * [ ] #4: 联调测试  [blocked by: [2, 3]]
     *
     * --- Summary ---
     * Completed: 1 | In Progress: 1 | Blocked: 2 | Total: 4
     * </pre>
     * <p>
     * AI 通过此输出可以：
     * - 看到哪些任务已完成（[x]）、正在做（[>]）、待做（[ ]）
     * - 看到依赖关系（blocked by / blocks）
     * - 判断下一个应该执行哪个任务（未被阻塞的 pending 任务）
     *
     * @return 格式化的任务列表
     */
    public String listAll() {
        List<TaskItem> allTasks = loadAll();

        if (allTasks.isEmpty()) {
            return "No tasks.";
        }

        // 按 ID 排序，保证输出顺序稳定
        allTasks.sort(Comparator.comparingInt(TaskItem::getId));

        StringBuilder sb = new StringBuilder();
        sb.append("=== Task Graph (").append(allTasks.size()).append(" tasks) ===\n\n");

        for (TaskItem task : allTasks) {
            // 状态标记：[ ] pending / [>] in_progress / [-] completed / [x] cancelled
            String marker = statusMarker(task.getStatus());
            sb.append(marker).append(" #").append(task.getId()).append(": ").append(task.getSubject());

            // 显示入边：我被谁阻塞
            if (!task.getBlockedBy().isEmpty()) {
                sb.append("  [blocked by: ").append(task.getBlockedBy()).append("]");
            }
            // 显示出边：我阻塞了谁
            if (!task.getBlocks().isEmpty()) {
                sb.append("  [blocks: ").append(task.getBlocks()).append("]");
            }
            // 显示负责人
            if (task.getOwner() != null && !task.getOwner().isEmpty()) {
                sb.append("  [owner: ").append(task.getOwner()).append("]");
            }
            sb.append("\n");
        }

        // 统计信息，帮助 AI 快速了解整体进度
        long completedCount = allTasks.stream()
                .filter(t -> "completed".equals(t.getStatus())).count();
        long inProgressCount = allTasks.stream()
                .filter(t -> "in_progress".equals(t.getStatus())).count();
        // 被阻塞的任务：blockedBy 非空且未完成
        long blockedCount = allTasks.stream()
                .filter(t -> !t.getBlockedBy().isEmpty() && !"completed".equals(t.getStatus())).count();

        sb.append("\n--- Summary ---\n");
        sb.append("Completed: ").append(completedCount)
                .append(" | In Progress: ").append(inProgressCount)
                .append(" | Blocked: ").append(blockedCount)
                .append(" | Total: ").append(allTasks.size()).append("\n");

        return sb.toString();
    }

    /**
     * 依赖解除：完成任务时，将其 ID 从下游任务的 blockedBy 中移除
     * <p>
     * 这是依赖图的核心机制，实现了"完成一个任务，自动解锁后续任务"
     * <p>
     * 执行流程：
     * 1. 遍历当前任务的 blocks 列表（即我阻塞了谁）
     * 2. 对每个下游任务，从其 blockedBy 中移除当前任务 ID
     * 3. 清空当前任务的 blocks 列表（因为已经完成了，不再阻塞任何人）
     * <p>
     * 示例：
     * <pre>
     * 完成前：Task 2.blocks = [3, 4], Task 3.blockedBy = [2], Task 4.blockedBy = [2, 3]
     * 完成 Task 2 后：Task 3.blockedBy = []（解锁！）, Task 4.blockedBy = [3]（还被 3 阻塞）
     * </pre>
     * <p>
     * 注意：使用 new ArrayList<>() 包装遍历，避免在遍历时修改集合导致 ConcurrentModificationException
     *
     * @param completedTask 已完成的任务
     */
    private void clearDependency(TaskItem completedTask) {
        Integer completedId = completedTask.getId();

        // 遍历当前任务 blocks 列表中的每个下游任务
        // new ArrayList<>() 包装是为了避免遍历时修改原集合
        for (Integer blockedTaskId : new ArrayList<>(completedTask.getBlocks())) {
            TaskItem blockedTask = load(blockedTaskId);
            if (blockedTask != null) {
                // 从下游任务的 blockedBy 中移除当前任务 ID
                blockedTask.getBlockedBy().remove(completedId);
                save(blockedTask);
                System.out.println("[TaskManager] Unblocked task #" + blockedTaskId
                        + " (removed blockedBy: " + completedId + ")");
            }
        }

        // 清空当前任务的 blocks 列表
        // 已完成的任务不再阻塞任何任务
        completedTask.getBlocks().clear();
    }

    /**
     * 将任务对象序列化并写入 JSON 文件
     * <p>
     * 文件路径格式：{tasksDir}/task_{id}.json
     * 文件内容为格式化的 JSON（开启 INDENT_OUTPUT）
     *
     * @param task 任务对象
     */
    private void save(TaskItem task) {
        Path filePath = taskFilePath(task.getId());
        try {
            mapper.writeValue(filePath.toFile(), task);
        } catch (IOException e) {
            System.err.println("[TaskManager] Failed to save task #" + task.getId() + ": " + e.getMessage());
        }
    }

    /**
     * 从 JSON 文件加载任务对象
     *
     * @param taskId 任务 ID
     * @return 任务对象，文件不存在或解析失败返回 null
     */
    private TaskItem load(Integer taskId) {
        Path filePath = taskFilePath(taskId);
        // 文件不存在说明任务不存在
        if (!Files.exists(filePath)) {
            return null;
        }
        try {
            return mapper.readValue(filePath.toFile(), TaskItem.class);
        } catch (IOException e) {
            System.err.println("[TaskManager] Failed to load task #" + taskId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 加载所有任务
     * <p>
     * 扫描 tasksDir 目录下所有 task_*.json 文件，逐个反序列化为 TaskItem
     * <p>
     * 注意：单个文件加载失败不会影响其他文件，会跳过并打印错误日志
     *
     * @return 任务列表（可能为空）
     */
    private List<TaskItem> loadAll() {
        List<TaskItem> tasks = new ArrayList<>();

        // 目录不存在则返回空列表
        if (!Files.exists(tasksDir)) {
            return tasks;
        }

        try (Stream<Path> paths = Files.list(tasksDir)) {
            // 过滤出 task_*.json 文件，逐个加载
            paths.filter(p -> p.getFileName().toString().startsWith(FILE_PREFIX)
                            && p.getFileName().toString().endsWith(FILE_SUFFIX))
                    .forEach(p -> {
                        try {
                            TaskItem task = mapper.readValue(p.toFile(), TaskItem.class);
                            tasks.add(task);
                        } catch (IOException e) {
                            // 单个文件加载失败不影响其他文件
                            System.err.println("[TaskManager] Failed to load task file: " + p);
                        }
                    });
        } catch (IOException e) {
            System.err.println("[TaskManager] Failed to list tasks directory: " + e.getMessage());
        }

        return tasks;
    }

    /**
     * 扫描任务目录，获取当前最大 ID
     * <p>
     * 用于初始化 nextId，保证新创建的任务 ID 不会与已有任务冲突
     * <p>
     * 实现方式：从文件名中提取数字部分
     * 例如 task_5.json → 提取 5
     *
     * @return 最大任务 ID，目录为空或不存在时返回 0
     */
    private int maxId() {
        int max = 0;
        try (Stream<Path> paths = Files.list(tasksDir)) {
            List<Integer> ids = paths
                    // 过滤 task_*.json 文件
                    .filter(p -> p.getFileName().toString().startsWith(FILE_PREFIX)
                            && p.getFileName().toString().endsWith(FILE_SUFFIX))
                    // 从文件名中提取 ID 数字
                    .map(p -> {
                        String name = p.getFileName().toString();
                        // task_5.json → 截取 "5"
                        String numStr = name.substring(FILE_PREFIX.length(),
                                name.length() - FILE_SUFFIX.length());
                        try {
                            return Integer.parseInt(numStr);
                        } catch (NumberFormatException e) {
                            // 文件名格式异常，返回 0 忽略
                            return 0;
                        }
                    })
                    .collect(Collectors.toList());
            // 取最大值，空列表返回 0
            max = ids.isEmpty() ? 0 : Collections.max(ids);
        } catch (IOException e) {
            // 目录不存在或为空，返回 0
        }
        return max;
    }

    /**
     * 根据任务 ID 生成文件路径
     * <p>
     * 例如 taskId=5 → .tasks/task_5.json
     *
     * @param taskId 任务 ID
     * @return 对应的文件路径
     */
    private Path taskFilePath(Integer taskId) {
        return tasksDir.resolve(FILE_PREFIX + taskId + FILE_SUFFIX);
    }

    /**
     * 将任务对象序列化为 JSON 字符串（单行，无缩进）
     * <p>
     * 用于工具返回值，给 AI 阅读的任务详情
     * 注意：这里使用 writeValueAsString（单行），而 save() 使用 writeValue（缩进写入文件）
     *
     * @param task 任务对象
     * @return JSON 字符串
     */
    private String toJson(TaskItem task) {
        try {
            return mapper.writeValueAsString(task);
        } catch (Exception e) {
            return "Error serializing task: " + e.getMessage();
        }
    }

    /**
     * 状态标记符号，用于 listAll() 渲染可读输出
     * <p>
     * pending     → [ ] 待完成
     * in_progress → [>] 正在进行
     * completed   → [-] 已完成
     * cancelled   → [x] 已取消
     *
     * @param status 任务状态
     * @return 对应的标记符号
     */
    private String statusMarker(String status) {
        switch (status) {
            case "in_progress":
                return "[>]";
            case "completed":
                return "[-]";
            case "cancelled":
                return "[x]";
            default:
                return "[ ]";
        }
    }
}
