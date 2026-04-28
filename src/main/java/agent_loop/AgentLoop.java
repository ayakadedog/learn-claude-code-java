package agent_loop;

import agent_tool.AgentTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import memery.MemoryCompactor;

import java.util.*;
import java.util.stream.Collectors;

import static constant.Constant.*;

/**
 * Agent 循环核心类
 * 负责与 LLM 对话、管理消息历史、处理工具调用
 */
public class AgentLoop {

    /** 从 AgentTools 类的 @Tool 注解自动生成工具规范（父代理完整工具集） */
    private static final List<ToolSpecification> PARENT_TOOL_SPECS = ToolSpecifications.toolSpecificationsFrom(AgentTools.class);

    /** 子代理工具规范：过滤掉 subagent 工具，防止递归 */
    private static final List<ToolSpecification> CHILD_TOOL_SPECS = PARENT_TOOL_SPECS.stream()
            .filter(spec -> !spec.name().equals("subagent"))
            .collect(Collectors.toList());

    /** ChatModel 实例（当前使用 DeepSeek） */
    private static final ChatModel chatModel = OpenAiChatModel.builder()
            .apiKey(DEEPSEEKKEY)
            .modelName(DEEPSEEKMODEL)
            .baseUrl(BASEURL)
            .build();

    /** 工具实例 */
    private static final AgentTools tool = new AgentTools();

    /** JSON 解析器 */
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Nag Reminder：连续未使用 todo 的轮次计数 */
    private static int roundsSinceTodo = 0;

    /** Nag Reminder 阈值：连续 3 轮不使用 todo 就提醒 */
    private static final int NAG_THRESHOLD = 3;

    /** 子代理最大执行轮数（安全限制） */
    private static final int SUBAGENT_MAX_ROUNDS = 30;

    /**
     * 执行用户消息的入口方法
     *
     * @param history 消息历史列表，会被直接修改
     */
    public static void run(List<ChatMessage> history) {
        // 添加系统提示词（动态获取技能列表） todo 这个貌似要放在最外面 这个是初始化语句
        history.add(UserMessage.from(getQuery()));

        // Agent Loop 主循环
        while (true) {
            // Layer 1: 微压缩 - 每次调用前清理旧工具结果
            MemoryCompactor.microCompact(history);

            // Layer 2: 自动压缩 - token 超限时触发
            if (MemoryCompactor.shouldAutoCompact(history)) {
                System.out.println("[auto_compact triggered]");
                MemoryCompactor.autoCompact(history, null);
                continue;
            }

            // 构建请求，附带工具规范
            ChatRequest request = ChatRequest.builder()
                    .messages(history)
                    .toolSpecifications(PARENT_TOOL_SPECS)
                    .build();

            // 发送请求给 LLM
            ChatResponse chatResponse = chatModel.chat(request);
            AiMessage aiMessage = chatResponse.aiMessage();

            // 将 LLM 响应加入消息历史
            history.add(aiMessage);

            // 获取工具调用请求
            List<ToolExecutionRequest> calls = aiMessage.toolExecutionRequests();

            // 如果没有工具调用，说明 LLM 已完成
            if (calls == null || calls.isEmpty()) {
                System.out.println(aiMessage.text());
                break;
            }

            // 处理工具调用
            List<ToolExecutionResultMessage> results = new ArrayList<>();
            boolean usedTodo = false;
            boolean manualCompact = false;

            for (ToolExecutionRequest call : calls) {
                String toolName = call.name();
                String argsJson = call.arguments();

                String output;
                try {
                    JsonNode node = mapper.readTree(argsJson);

                    // Layer 3: 手动压缩检测
                    if ("compact".equals(toolName)) {
                        manualCompact = true;
                        String focus = node.has("focus") && !node.get("focus").isNull()
                                ? node.get("focus").asText() : null;
                        output = "Compressing... (focus: " + (focus != null ? focus : "none") + ")";
                    } else {
                        output = dispatch(toolName, node);
                    }
                } catch (Exception e) {
                    output = "Error: " + e.getMessage();
                }

                // 跟踪是否使用了 todo 工具
                if (toolName.equals("todo")) {
                    usedTodo = true;
                }

                // 打印工具执行结果
                System.out.println("> " + toolName + ": " +
                        output.substring(0, Math.min(200, output.length())));

                // 将工具结果封装成消息，加入历史
                results.add(ToolExecutionResultMessage.from(call, output));
            }

            // Nag Reminder
            if (usedTodo) {
                roundsSinceTodo = 0;
            } else {
                roundsSinceTodo++;
            }

            // 将所有工具执行结果加入消息历史
            history.addAll(results);

            // Layer 3: 手动压缩执行
            if (manualCompact) {
                System.out.println("[manual compact]");
                MemoryCompactor.autoCompact(history, null);
                continue;
            }

            // 如果连续 3 轮没用 todo，插入提醒
            if (roundsSinceTodo >= NAG_THRESHOLD) {
                System.out.println("\n<reminder>Update your todos.</reminder>\n");
                history.add(UserMessage.from("<reminder>Update your todos.</reminder>"));
                roundsSinceTodo = 0;
            }
        }
    }

    /**
     * 工具分发器
     * 根据工具名称调用对应的 AgentTools 方法
     *
     * @param toolName 工具名称（来自 LLM）
     * @param node     JSON 参数节点
     * @return 工具执行结果
     */
    private static String dispatch(String toolName, JsonNode node) {
        switch (toolName) {
            case "run":
            case "bash":
                return tool.run(get(node, "command"));

            case "read":
            case "read_file":
                return tool.read(
                        get(node, "path"),
                        node.has("limit") ? node.get("limit").asInt() : null);

            case "write":
            case "write_file":
                return tool.write(
                        get(node, "path"),
                        get(node, "content"));

            case "edit":
            case "edit_file":
                return tool.edit(
                        get(node, "path"),
                        get(node, "old_text"),
                        get(node, "new_text"));

            case "todo":
                return tool.todo(get(node, "items"));

            case "subagent":
            case "task":
                return runSubagent(get(node, "task"));

            case "loadSkill":
            case "load_skill":
                return tool.loadSkill(get(node, "name"));

            default:
                return "Unknown tool: " + toolName;
        }
    }

    /**
     * 运行子代理
     * 创建独立的上下文，使用精简工具集（无 subagent），只返回最终总结
     *
     * @param task 子代理要执行的任务描述
     * @return 子代理的执行总结
     */
    public static String runSubagent(String task) {
        System.out.println("> subagent: " + task.substring(0, Math.min(80, task.length())));

        // 子代理有独立的上下文，不继承父代理的对话历史
        List<ChatMessage> subMessages = new ArrayList<>();
        subMessages.add(UserMessage.from(task));

        try {
            for (int round = 0; round < SUBAGENT_MAX_ROUNDS; round++) {
                // 使用子代理工具集（没有 subagent 工具）
                ChatRequest request = ChatRequest.builder()
                        .messages(subMessages)
                        .toolSpecifications(CHILD_TOOL_SPECS)
                        .build();

                ChatResponse chatResponse = chatModel.chat(request);
                AiMessage aiMessage = chatResponse.aiMessage();
                subMessages.add(aiMessage);

                List<ToolExecutionRequest> calls = aiMessage.toolExecutionRequests();

                // 如果没有工具调用，子代理已完成，返回总结
                if (calls == null || calls.isEmpty()) {
                    String summary = aiMessage.text() != null ? aiMessage.text() : "(no summary)";
                    System.out.println("  subagent done: " + summary.substring(0, Math.min(200, summary.length())));
                    return summary;
                }

                // 执行子代理的工具调用
                List<ToolExecutionResultMessage> results = new ArrayList<>();
                for (ToolExecutionRequest call : calls) {
                    String output;
                    try {
                        JsonNode node = mapper.readTree(call.arguments());
                        output = dispatch(call.name(), node);
                    } catch (Exception e) {
                        output = "Error: " + e.getMessage();
                    }
                    System.out.println("  [sub] " + call.name() + ": " +
                            output.substring(0, Math.min(150, output.length())));
                    results.add(ToolExecutionResultMessage.from(call, output));
                }
                subMessages.addAll(results);
            }

            return "Error: Subagent exceeded max rounds (" + SUBAGENT_MAX_ROUNDS + ")";

        } catch (Exception e) {
            return "Error: Subagent failed: " + e.getMessage();
        }
    }

    /**
     * 从 JSON 节点中获取指定键的值
     * 兼容 arg0 格式（Java 编译时参数名丢失的情况）
     *
     * @param node JSON 节点
     * @param key  要获取的键名
     * @return 对应的值
     */
    private static String get(JsonNode node, String key) {
        if (node.has(key) && !node.get(key).isNull()) {
            return node.get(key).asText();
        }

        // 兼容 arg0 格式
        if (node.has("arg0")) {
            return node.get("arg0").asText();
        }

        throw new RuntimeException("Missing param: " + key +
                " in " + node.toString());
    }

    /**
     * 调用 LLM 生成对话摘要（供 MemoryCompactor 使用）
     * 使用精简请求，不带工具规范，只获取文本总结
     *
     * @param prompt 摘要生成提示词
     * @return LLM 生成的摘要文本
     */
    public static String callForSummary(String prompt) {
        try {
            List<ChatMessage> summaryMessages = new ArrayList<>();
            summaryMessages.add(UserMessage.from(prompt));

            ChatRequest request = ChatRequest.builder()
                    .messages(summaryMessages)
                    .build();

            ChatResponse chatResponse = chatModel.chat(request);
            AiMessage aiMessage = chatResponse.aiMessage();

            return aiMessage.text() != null ? aiMessage.text() : "(no summary)";

        } catch (Exception e) {
            return "Error generating summary: " + e.getMessage();
        }
    }
}
