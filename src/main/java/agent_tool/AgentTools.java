package agent_tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.*;
import java.nio.file.*;

public class AgentTools {

    private static final Path WORKDIR = Paths.get(".").toAbsolutePath().normalize();

    @Tool("Run a shell command safely")
    public String run(@P("command") String command) {

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
                // 👉 Windows 用 cmd
                process = new ProcessBuilder("cmd", "/c", command)
                        .directory(WORKDIR.toFile())
                        .redirectErrorStream(true)
                        .start();
            } else {
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

            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

                // ✅ 超时控制（120秒）
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

    @Tool("Read file content")
    public String read(@P("path") String path, @P("limit") Integer limit) {
        try {
            Path file = safePath(path);
            String text = Files.readString(file);

            String[] lines = text.split("\n");

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

    @Tool("Write content to file")
    public String write(@P("path") String path, @P("content") String content) {
        try {
            Path file = safePath(path);

            Files.createDirectories(file.getParent());
            Files.writeString(file, content);

            return "Wrote " + content.length() + " bytes to " + path;

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool("Edit file by replacing text")
    public String edit(@P("path") String path, @P("old_text") String oldText, @P("new_text") String newText) {
        try {
            Path file = safePath(path);

            String content = Files.readString(file);

            if (!content.contains(oldText)) {
                return "Error: Text not found in " + path;
            }

            String updated = content.replaceFirst(
                    java.util.regex.Pattern.quote(oldText),
                    newText);

            Files.writeString(file, updated);

            return "Edited " + path;

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private Path safePath(String input) {
        Path resolved = WORKDIR.resolve(input).normalize();

        if (!resolved.startsWith(WORKDIR)) {
            throw new RuntimeException("Access denied");
        }

        return resolved;
    }
}