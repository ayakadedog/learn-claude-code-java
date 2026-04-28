package agent_tool;

import agent_skill.SkillLoader;
import background_manager.BackgroundManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import task_manager.TaskManager;

import java.io.*;
import java.nio.file.*;
import java.util.List;

/**
 * Agent 工具类
 * 提供文件读写、命令执行、任务管理等工具，供 LLM 调用
 */
public class AgentTools {

    /** 工作目录，限制所有文件操作在此目录内 */
    private static final Path WORKDIR = Paths.get(".").toAbsolutePath().normalize();

    /** TodoManager 实例，全局共享任务清单 */
    private static final TodoManager todoManager = new TodoManager();

    /** SkillLoader 实例，全局共享技能加载器 */
    private static final SkillLoader skillLoader = new SkillLoader(WORKDIR.resolve("skills"));

    /** TaskManager 实例，全局共享持久化任务管理器 */
    private static final TaskManager taskManager = new TaskManager(WORKDIR.resolve(".tasks"));

    /** BackgroundManager 实例，全局共享后台任务管理器 */
    private static final BackgroundManager backgroundManager = new BackgroundManager();

    /** JSON 解析器 */
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 执行 Shell 命令
     * 自动根据操作系统选择 cmd (Windows) 或 bash (Linux/Mac)
     *
     * @param command 要执行的命令
     * @return 命令输出或错误信息
     */
    @Tool("Run a shell command safely")
    public String run(@P("command") String command) {
        // 危险命令黑名单
        String[] dangerous = { "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/" };
        for (String d : dangerous) {
            if (command.contains(d)) {
                return "Error: Dangerous command blocked";
            }
        }

        try {
            boolean isWindows = System.getProperty("os.name")
                    .toLowerCase()
                    .contains("win");

            Process process;

            if (isWindows) {
                // Windows: 使用 cmd 执行
                process = new ProcessBuilder("cmd", "/c", command)
                        .directory(WORKDIR.toFile())
                        .redirectErrorStream(true)
                        .start();
            } else {
                // Linux/Mac: 使用 bash 执行
                process = new ProcessBuilder("bash", "-c", command)
                        .directory(WORKDIR.toFile())
                        .redirectErrorStream(true)
                        .start();
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            StringBuilder output = new StringBuilder();
            String line;

            long start = System.currentTimeMillis();

            // 读取命令输出
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

                // 超时控制（120秒）
                if (System.currentTimeMillis() - start > 120_000) {
                    process.destroy();
                    return "Error: Timeout (120s)";
                }
            }

            String result = output.toString().trim();
            return result.isEmpty()
                    ? "(no output)"
                    : result.substring(0, Math.min(50000, result.length()));

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 读取文件内容
     *
     * @param path  文件路径
     * @param limit 行数限制（可选，null 表示全部）
     * @return 文件内容或错误信息
     */
    @Tool("Read file content")
    public String read(@P("path") String path, @P("limit") Integer limit) {
        try {
            Path file = safePath(path);
            String text = Files.readString(file);

            String[] lines = text.split("\n");

            // 如果限制了行数，截取前 limit 行
            if (limit != null && limit < lines.length) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < limit; i++) {
                    sb.append(lines[i]).append("\n");
                }
                sb.append("... (").append(lines.length - limit).append(" more lines)");
                return sb.toString().substring(0, Math.min(50000, sb.length()));
            }

            return text.substring(0, Math.min(50000, text.length()));

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 写入文件内容
     *
     * @param path    文件路径
     * @param content 要写入的内容
     * @return 成功或错误信息
     */
    @Tool("Write content to file")
    public String write(@P("path") String path, @P("content") String content) {
        try {
            Path file = safePath(path);

            // 确保父目录存在
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);

            return "Wrote " + content.length() + " bytes to " + path;

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 编辑文件（替换文本）
     *
     * @param path    文件路径
     * @param oldText 要替换的旧文本
     * @param newText 替换后的新文本
     * @return 成功或错误信息
     */
    @Tool("Edit file by replacing text")
    public String edit(@P("path") String path, @P("old_text") String oldText, @P("new_text") String newText) {
        try {
            Path file = safePath(path);

            String content = Files.readString(file);

            if (!content.contains(oldText)) {
                return "Error: Text not found in " + path;
            }

            // 使用正则表达式精确替换（避免特殊字符问题）
            String updated = content.replaceFirst(
                    java.util.regex.Pattern.quote(oldText),
                    newText);

            Files.writeString(file, updated);

            return "Edited " + path;

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

//    /**
//     * 更新任务清单
//     * 用于 AI 规划和管理多步骤任务
//     *
//     * @param items JSON 格式的任务列表，如：
//     *              [{"id":"1","text":"完成任务","status":"pending"},...]
//     *              status 可选值：pending / in_progress / completed
//     * @return 渲染后的任务清单
//     */
//    @Tool("Update task list. Track progress on multi-step tasks.")
//    public String todo(@P("items") String items) {
//        try {
//            List<TodoItem> todoItems = mapper.readValue(items, new TypeReference<List<TodoItem>>() {});
//            return todoManager.update(todoItems);
//        } catch (Exception e) {
//            return "Error: " + e.getMessage();
//        }
//    }

    /**
     * 创建子代理来执行独立任务
     * 实际执行逻辑在 AgentLoop.runSubagent() 中
     * 这里只是一个占位，让 LangChain4j 能识别这个工具
     *
     * @param task 要委派给子代理的任务描述
     * @return 子代理的执行总结
     */
    @Tool("Spawn a subagent with fresh context to handle an independent task. " +
          "It shares the filesystem but not conversation history. " +
          "Use this to delegate exploration, research, or parallel subtasks.")
    public String subagent(@P("task") String task) {
        // 实际执行在 AgentLoop.dispatch() 中路由到 AgentLoop.runSubagent()
        // 这里返回一个标记，dispatch 会拦截并调用真正的子代理逻辑
        return "[DELEGATED]";
    }

    /**
     * 加载技能的完整内容
     * 技能列表已在系统提示中显示，此工具用于按需加载详细指令
     *
     * @param name 技能名称
     * @return 技能的完整内容，或错误信息
     */
    @Tool("Load a skill's full instructions by name. " +
          "Use this when you need detailed guidance for a specific skill.")
    public String loadSkill(@P("name") String name) {
        return skillLoader.getContent(name);
    }

    /**
     * 手动触发对话压缩
     * 当 LLM 判断上下文过长或完成大任务后可主动调用此工具
     * 实际执行逻辑在 AgentLoop.dispatch() 中路由到 MemoryCompactor.autoCompact()
     *
     * @param focus 压缩时需要保留的关注点（可选，如 "focus on UserService changes"）
     * @return 压缩结果
     */
    @Tool("Trigger manual conversation compression to reduce context length. " +
          "Use this when the conversation is getting long or after completing a major task. " +
          "Optionally specify what information should be preserved in the summary.")
    public String compact(@P("focus") String focus) {
        return "[COMPACT_REQUESTED" + (focus != null && !focus.isEmpty() ? ":" + focus : "") + "]";
    }

    /**
     * 创建持久化任务（带依赖图）
     * 任务存储在 .tasks/ 目录下，跨会话持久化
     *
     * @param subject     任务主题
     * @param description 任务描述（可选）
     * @return 创建的任务 JSON
     */
    @Tool("Create a persistent task. IMPORTANT: Keep total tasks under 8. " +
          "After creating tasks, immediately start executing by calling task_update with status=in_progress, " +
          "then use run/read/write/edit to do the actual work. " +
          "Do NOT create more tasks without completing existing ones first.")
    public String taskCreate(@P("subject") String subject, @P("description") String description) {
        return taskManager.create(subject, description);
    }

    /**
     * 更新持久化任务（状态变更 + 依赖关联）
     * 完成任务时自动解除依赖，解锁后续任务
     *
     * @param taskId       任务 ID
     * @param status       新状态（pending / in_progress / completed / cancelled）
     * @param addBlockedBy 新增阻塞依赖：被哪个任务阻塞（传入任务 ID）
     * @param addBlocks    新增阻塞关系：阻塞了哪个任务（传入任务 ID）
     * @return 更新后的任务 JSON
     */
    @Tool("Update a persistent task. Change status or add dependencies. " +
          "When a task is completed, it automatically unblocks dependent tasks. " +
          "add_blocked_by: ID of task that blocks this one. " +
          "add_blocks: ID of task that this one blocks.")
    public String taskUpdate(@P("task_id") Integer taskId,
                             @P("status") String status,
                             @P("add_blocked_by") Integer addBlockedBy,
                             @P("add_blocks") Integer addBlocks) {
        return taskManager.update(taskId, status, addBlockedBy, addBlocks);
    }

    /**
     * 列出所有持久化任务
     *
     * @return 格式化的任务列表（含依赖信息）
     */
    @Tool("List all persistent tasks with their status and dependency info.")
    public String taskList() {
        return taskManager.listAll();
    }

    /**
     * 获取单个持久化任务详情
     *
     * @param taskId 任务 ID
     * @return 任务 JSON 详情
     */
    @Tool("Get details of a specific persistent task by ID.")
    public String taskGet(@P("task_id") Integer taskId) {
        return taskManager.get(taskId);
    }

    /**
     * 获取 SkillLoader 实例（供 Constant 使用）
     */
    public static SkillLoader getSkillLoader() {
        return skillLoader;
    }

    /**
     * 获取 BackgroundManager 实例（供 AgentLoop 排空通知使用）
     */
    public static BackgroundManager getBackgroundManager() {
        return backgroundManager;
    }

    /**
     * 后台执行 Shell 命令，立即返回
     * 适合长时间运行的命令（如 dev server、build watch、test --watch）
     * 命令在守护线程中执行，完成后结果通过通知队列传递给 Agent
     *
     * @param command 要执行的命令
     * @return 启动信息，包含 task_id
     */
    @Tool("Run a shell command in the background (non-blocking). " +
          "Use this for long-running commands like dev servers, build watches, or test suites. " +
          "The command runs in a daemon thread and results will be delivered in the next agent loop iteration. " +
          "Use bg_status to check progress or bg_list to see all background tasks.")
    public String bgRun(@P("command") String command) {
        return backgroundManager.run(command);
    }

    /**
     * 查询后台任务状态
     *
     * @param taskId 任务 ID
     * @return 任务状态信息
     */
    @Tool("Check the status of a background task by its ID. " +
          "Returns current status (running/completed/failed) and output if finished.")
    public String bgStatus(@P("task_id") String taskId) {
        return backgroundManager.status(taskId);
    }

    /**
     * 列出所有后台任务
     *
     * @return 格式化的任务列表
     */
    @Tool("List all background tasks with their current status.")
    public String bgList() {
        return backgroundManager.listAll();
    }

    /**
     * 安全路径检查
     * 确保文件操作不会超出 WORKDIR，防止路径遍历攻击
     *
     * @param input 用户输入的路径
     * @return 解析后的安全路径
     */
    private Path safePath(String input) {
        Path resolved = WORKDIR.resolve(input).normalize();

        // 检查是否在 WORKDIR 内
        if (!resolved.startsWith(WORKDIR)) {
            throw new RuntimeException("Access denied");
        }

        return resolved;
    }
}
