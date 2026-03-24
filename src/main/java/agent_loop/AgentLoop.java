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

public class AgentLoop {

    private static final List<ToolSpecification> toolSpecifications = ToolSpecifications
            .toolSpecificationsFrom(AgentTools.class);

    private static final ChatModel chatModel = OpenAiChatModel.builder()
            .apiKey(MINIMAXKEY)
            .modelName(MINIMAXMODEL)
            .baseUrl(MINIMAXBASEURL)
            .build();

    private static final AgentTools tool = new AgentTools();
    private static final List<ChatMessage> messages = new ArrayList<>();
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void run(String message) {
        messages.add(SystemMessage.from(QUERY));
        messages.add(UserMessage.from(message));

        while (true) {

            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(toolSpecifications)
                    .build();

            ChatResponse chatResponse = chatModel.chat(request);
            AiMessage aiMessage = chatResponse.aiMessage();

            messages.add(aiMessage);

            List<ToolExecutionRequest> calls = aiMessage.toolExecutionRequests();

            if (calls == null || calls.isEmpty()) {
                System.out.println(aiMessage.text());
                break;
            }

            List<ToolExecutionResultMessage> results = new ArrayList<>();

            for (ToolExecutionRequest call : calls) {

                String toolName = call.name();
                String argsJson = call.arguments();

                String output;

                try {
                    JsonNode node = mapper.readTree(argsJson);

                    // ✅ 核心：工具分发
                    output = dispatch(toolName, node);

                } catch (Exception e) {
                    output = "Error: " + e.getMessage();
                }

                System.out.println("> " + toolName + ": " +
                        output.substring(0, Math.min(200, output.length())));

                results.add(ToolExecutionResultMessage.from(call, output));
            }

            messages.addAll(results);
        }
    }

    // ================== 核心：Tool Dispatch ==================
    private static String dispatch(String toolName, JsonNode node) {

        switch (toolName) {

            // ===== bash =====
            case "run":
            case "bash":
                return tool.run(get(node, "command"));

            // ===== read =====
            case "read":
            case "read_file":
                return tool.read(
                        get(node, "path"),
                        node.has("limit") ? node.get("limit").asInt() : null);

            // ===== write =====
            case "write":
            case "write_file":
                return tool.write(
                        get(node, "path"),
                        get(node, "content"));

            // ===== edit =====
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

    private static String get(JsonNode node, String key) {

        if (node.has(key) && !node.get(key).isNull()) {
            return node.get(key).asText();
        }

        // 👉 兼容 arg0（你之前的问题）
        if (node.has("arg0")) {
            return node.get("arg0").asText();
        }

        throw new RuntimeException("Missing param: " + key +
                " in " + node.toString());
    }
}