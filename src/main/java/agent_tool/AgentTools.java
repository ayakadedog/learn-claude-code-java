package agent_tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

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

    /**
     * 更新任务清单
     * 用于 AI 规划和管理多步骤任务
     *
     * @param items JSON 格式的任务列表，如：
     *              [{"id":"1","text":"完成任务","status":"pending"},...]
     *              status 可选值：pending / in_progress / completed
     * @return 渲染后的任务清单
     */
    @Tool("Update task list. Track progress on multi-step tasks.")
    public String todo(@P("items") String items) {
        try {
            List<TodoItem> todoItems = mapper.readValue(items, new TypeReference<List<TodoItem>>() {});
            return todoManager.update(todoItems);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
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
