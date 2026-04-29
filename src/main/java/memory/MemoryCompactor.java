package memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import agent_loop.AgentLoop;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * 记忆压缩器 - 三层压缩机制
 * <p>
 * Layer 1: micro_compact - 每次调用前清理旧工具结果（保留最近 N 条）
 * Layer 2: auto_compact - token 超限时自动总结并替换历史
 * Layer 3: manual compact - LLM 通过 compact 工具主动触发压缩
 */
public class MemoryCompactor {

    /** 微压缩保留的最近结果数量 */
    private static final int KEEP_RECENT = 3;

    /** 自动压缩 token 阈值（约 50000 tokens） */
    private static final int TOKEN_THRESHOLD = 50000;

    /** 工具结果内容最小长度，低于此值不清理 */
    private static final int MIN_CONTENT_LENGTH = 100;

    /** 对话记录保存目录 */
    private static final Path TRANSCRIPT_DIR = Paths.get(".transcripts");

    /** JSON 解析器 */
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Layer 1: 微压缩 - 清理旧的工具执行结果
     * <p>
     * 工作原理：
     * 1. 找出所有包含 tool_calls 的 AiMessage
     * 2. 对于每个 AiMessage，收集其所有 ToolExecutionResultMessage
     * 3. 保留最近 KEEP_RECENT 个 AiMessage 及其所有 ToolExecutionResultMessage
     * 4. 删除旧的 AiMessage 及其所有 ToolExecutionResultMessage
     * <p>
     * 目的：减少上下文长度，防止历史消息无限膨胀
     * <p>
     * 注意：
     * - 一个 AiMessage 可能包含多个 tool_calls
     * - 必须整体删除 AiMessage 及其所有 ToolExecutionResultMessage
     * - 不能部分删除，否则会破坏 API 消息结构
     *
     * @param messages 消息历史列表（会被直接修改）
     */
    public static void microCompact(List<ChatMessage> messages) {
        // 存储每个 AiMessage 的信息：[AiMessage索引, Set<ToolExecutionResultMessage索引>]
        List<Object[]> aiMessageGroups = new ArrayList<>();

        // 第一步：找出所有包含 tool_calls 的 AiMessage 及其对应的 ToolExecutionResultMessage
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AiMessage aiMsg && aiMsg.hasToolExecutionRequests()) {
                List<ToolExecutionRequest> requests = aiMsg.toolExecutionRequests();
                if (requests != null && !requests.isEmpty()) {
                    Set<Integer> resultIndexes = new HashSet<>();
                    
                    // 找到该 AiMessage 的所有 ToolExecutionResultMessage
                    for (ToolExecutionRequest req : requests) {
                        String toolId = req.id();
                        for (int j = i + 1; j < messages.size(); j++) {
                            ChatMessage nextMsg = messages.get(j);
                            if (nextMsg instanceof ToolExecutionResultMessage resultMsg) {
                                if (toolId.equals(resultMsg.id())) {
                                    resultIndexes.add(j);
                                    break;
                                }
                            }
                        }
                    }
                    
                    // 只有当所有 tool_calls 都有对应的响应时才加入列表
                    if (resultIndexes.size() == requests.size()) {
                        aiMessageGroups.add(new Object[]{i, resultIndexes});
                    }
                }
            }
        }

        // 如果 AiMessage 数量 <= KEEP_RECENT，无需清理
        if (aiMessageGroups.size() <= KEEP_RECENT) {
            return;
        }

        // 第二步：删除旧的 AiMessage 组（从后往前删除，避免索引变化）
        int toRemoveCount = aiMessageGroups.size() - KEEP_RECENT;
        
        // 收集需要删除的索引（去重，降序排列）
        Set<Integer> indexesToRemove = new TreeSet<>(Collections.reverseOrder());

        for (int i = 0; i < toRemoveCount; i++) {
            Object[] group = aiMessageGroups.get(i);
            Integer aiMsgIdx = (Integer) group[0];
            @SuppressWarnings("unchecked")
            Set<Integer> resultIndexes = (Set<Integer>) group[1];
            
            indexesToRemove.add(aiMsgIdx);
            indexesToRemove.addAll(resultIndexes);
        }

        // 从后往前删除
        int removedCount = 0;
        for (Integer idx : indexesToRemove) {
            if (idx < messages.size()) {
                messages.remove(idx.intValue());
                removedCount++;
            }
        }

        // 打印清理日志
        if (removedCount > 0) {
            System.out.println("[micro_compact] Removed " + removedCount + 
                    " messages (" + toRemoveCount + " AiMessage groups)");
        }
    }

    /**
     * 估算消息列表的 token 数量
     * <p>
     * 估算方法：
     * 将消息列表序列化为 JSON 字符串，然后按 4 字符 ≈ 1 token 估算
     * <p>
     * 注意：这是一个粗略估算，实际 token 数可能因模型而异
     *
     * @param messages 消息历史列表
     * @return 估算的 token 数量
     */
    public static int estimateTokens(List<ChatMessage> messages) {
        String jsonStr;
        try {
            // 将消息列表序列化为 JSON 字符串
            jsonStr = mapper.writeValueAsString(messages);
        } catch (Exception e) {
            // 序列化失败时，使用 toString() 作为备选
            jsonStr = messages.toString();
        }
        // 简单估算：4 个字符 ≈ 1 个 token
        return jsonStr.length() / 4;
    }

    /**
     * Layer 2 & 3: 自动/手动压缩 - 保存完整对话并生成摘要
     * <p>
     * 执行流程：
     * 1. 将完整对话保存到 .transcripts/transcript_{timestamp}.jsonl（便于后续恢复或调试）
     * 2. 调用 LLM 生成结构化摘要（包含：已完成任务、当前状态、关键决策）
     * 3. 清空原消息历史，用摘要消息替换
     * <p>
     * 触发场景：
     * - Layer 2: token 数超过 TOKEN_THRESHOLD 时自动触发
     * - Layer 3: LLM 调用 compact 工具时手动触发
     *
     * @param messages 消息历史列表（会被清空并替换为摘要）
     * @param focus    手动压缩时的关注点（可选，用于指导摘要重点）
     * @return true 表示压缩成功，false 表示压缩失败
     */
    public static boolean autoCompact(List<ChatMessage> messages, String focus) {
        try {
            // 第一步：保存完整对话记录到磁盘（防止信息丢失）
            saveTranscript(messages);

            // 第二步：调用 LLM 生成对话摘要
            String summary = generateSummary(messages, focus);

            // 第三步：清空原消息历史
            messages.clear();

            // 构建上下文信息（区分手动/自动压缩）
            String contextInfo = focus != null
                    ? "[Manual compression. Focus: " + focus + "]"
                    : "[Auto-compression triggered]";

            // 第四步：用摘要消息替换原历史
            // 添加用户消息（包含摘要）
            messages.add(UserMessage.from(contextInfo + "\n\n" + summary));
            // 添加 AI 确认消息（保持对话格式完整）
            messages.add(AiMessage.from("Understood. I have the context from the summary. Continuing."));

            // 打印压缩日志
            System.out.println("[auto_compact] Conversation compressed. Summary length: " + summary.length());
            return true;

        } catch (Exception e) {
            // 压缩失败，打印错误信息
            System.err.println("[auto_compact] Error: " + e.getMessage());
            return false;
        }
    }

    /**
     * 保存完整对话记录到磁盘
     * <p>
     * 文件格式：JSONL（每行一个 JSON 对象）
     * 文件路径：.transcripts/transcript_{timestamp}.jsonl
     * <p>
     * 用途：
     * - 调试：查看完整对话历史
     * - 恢复：必要时可以从磁盘恢复对话上下文
     *
     * @param messages 消息历史列表
     * @throws IOException 文件写入失败时抛出
     */
    private static void saveTranscript(List<ChatMessage> messages) throws IOException {
        // 确保目录存在
        Files.createDirectories(TRANSCRIPT_DIR);

        // 生成时间戳作为文件名
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        Path transcriptPath = TRANSCRIPT_DIR.resolve("transcript_" + timestamp + ".jsonl");

        // 写入文件（使用 try-with-resources 自动关闭资源）
        try (BufferedWriter writer = Files.newBufferedWriter(transcriptPath)) {
            for (ChatMessage msg : messages) {
                // 将每条消息序列化为 JSON 并写入一行
                String line = mapper.writeValueAsString(msg);
                writer.write(line);
                writer.newLine();
            }
        }

        // 打印保存日志
        System.out.println("[transcript saved: " + transcriptPath + "]");
    }

    /**
     * 调用 LLM 生成对话摘要
     * <p>
     * 摘要结构：
     * 1) What was accomplished - 已完成的任务
     * 2) Current state of the project - 项目当前状态
     * 3) Key decisions made and code changes - 关键决策和代码变更
     *
     * @param messages 原始消息历史
     * @param focus    关注点（可选，用于指导摘要重点）
     * @return LLM 生成的摘要文本
     * @throws Exception LLM 调用失败时抛出
     */
    private static String generateSummary(List<ChatMessage> messages, String focus) throws Exception {
        String conversationText;
        try {
            // 将消息历史序列化为 JSON 字符串
            conversationText = mapper.writeValueAsString(messages);
            // 限制长度，防止超过 LLM 上下文限制
            if (conversationText.length() > 80000) {
                conversationText = conversationText.substring(0, 80000) + "... [truncated]";
            }
        } catch (Exception e) {
            // 序列化失败时，使用 toString() 作为备选
            conversationText = messages.toString();
        }

        // 构建摘要生成提示词
        String prompt = "Summarize this conversation for continuity. Include:\n"
                + "1) What was accomplished\n"           // 已完成的任务
                + "2) Current state of the project\n"    // 项目当前状态
                + "3) Key decisions made and code changes\n"  // 关键决策和代码变更
                + "Be concise but preserve critical details.\n\n"  // 要求简洁但保留关键细节
                + conversationText;

        // 如果有指定关注点，添加到提示词中
        if (focus != null && !focus.isEmpty()) {
            prompt += "\n\nFocus area: " + focus;
        }

        // 调用 AgentLoop 中的方法，让 LLM 生成摘要
        return AgentLoop.callForSummary(prompt);
    }

    /**
     * 检查是否需要自动压缩
     * <p>
     * 判断标准：估算的 token 数是否超过 TOKEN_THRESHOLD（默认 50000）
     *
     * @param messages 消息历史列表
     * @return true 表示需要压缩，false 表示暂不需要
     */
    public static boolean shouldAutoCompact(List<ChatMessage> messages) {
        return estimateTokens(messages) > TOKEN_THRESHOLD;
    }
}
