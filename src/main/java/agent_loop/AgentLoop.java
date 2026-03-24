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

import java.util.*;

import static constant.Constant.*;

/**
 * Agent 循环核心类
 * 负责与 LLM 对话、管理消息历史、处理工具调用
 */
public class AgentLoop {

    /**
     * 从 AgentTools 类的 @Tool 注解自动生成工具规范
     * 用于告诉 LLM 有哪些工具可用
     */
    private static final List<ToolSpecification> toolSpecifications = ToolSpecifications
            .toolSpecificationsFrom(AgentTools.class);

    /**
     * ChatModel 实例（当前使用 MiniMax）
     * 负责与 LLM API 通信
     */
    private static final ChatModel chatModel = OpenAiChatModel.builder()
            .apiKey(MINIMAXKEY)
            .modelName(MINIMAXMODEL)
            .baseUrl(MINIMAXBASEURL)
            .build();

    /** 工具实例，用于执行具体的文件/命令操作 */
    private static final AgentTools tool = new AgentTools();

    /** 消息历史，记录整个对话过程 */
    private static final List<ChatMessage> messages = new ArrayList<>();

    /** JSON 解析器，用于解析 LLM 返回的工具参数 */
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 执行用户消息的入口方法
     * 实现 Agent Loop：发送消息 -> 接收响应 -> 执行工具 -> 循环直到完成
     *
     * @param message 用户输入的消息
     */
    public static void run(String message) {
        // 添加系统提示词和用户消息
        messages.add(SystemMessage.from(QUERY));
        messages.add(UserMessage.from(message));

        // Agent Loop 主循环
        while (true) {
            // 构建请求，附带工具规范
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(toolSpecifications)
                    .build();

            // 发送请求给 LLM
            ChatResponse chatResponse = chatModel.chat(request);
            AiMessage aiMessage = chatResponse.aiMessage();

            // 将 LLM 响应加入消息历史
            messages.add(aiMessage);

            // 获取工具调用请求
            List<ToolExecutionRequest> calls = aiMessage.toolExecutionRequests();

            // 如果没有工具调用，说明 LLM 已完成，直接输出结果
            if (calls == null || calls.isEmpty()) {
                System.out.println(aiMessage.text());
                break;
            }

            // 处理工具调用
            List<ToolExecutionResultMessage> results = new ArrayList<>();

            for (ToolExecutionRequest call : calls) {
                String toolName = call.name();
                String argsJson = call.arguments();

                String output;
                try {
                    // 解析 JSON 参数
                    JsonNode node = mapper.readTree(argsJson);
                    // 分发到对应的工具方法
                    output = dispatch(toolName, node);
                } catch (Exception e) {
                    output = "Error: " + e.getMessage();
                }

                // 打印工具执行结果（截取前200字符）
                System.out.println("> " + toolName + ": " +
                        output.substring(0, Math.min(200, output.length())));

                // 将工具结果封装成消息，加入历史
                results.add(ToolExecutionResultMessage.from(call, output));
            }

            // 将所有工具执行结果加入消息历史，继续下一轮对话
            messages.addAll(results);
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

            default:
                return "Unknown tool: " + toolName;
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
}
