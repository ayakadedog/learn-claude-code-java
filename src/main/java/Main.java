import agent_loop.AgentLoop;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * 程序入口类
 * 提供交互式命令行界面，用户输入指令后由 Agent 处理
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // 创建 Agent 实例
        AgentLoop agent = new AgentLoop();

        // 对话历史（用于记录用户输入）
        List<ChatMessage> history = new ArrayList<>();

        // 创建Scanner用于读取用户输入
        Scanner scanner = new Scanner(System.in);

        // 主循环：持续接收用户输入直到 exit
        while (true) {
            // 打印提示符（青色 s01 >>）
            System.out.print("\u001B[36ms01 >> \u001B[0m");

            String query = scanner.nextLine();

            // 空输入或 exit 退出程序
            if (query.trim().isEmpty() || query.equals("exit")) {
                break;
            }

            // 记录用户消息
            history.add(UserMessage.from(query));

            // 调用 Agent 处理
            agent.run(query);

            // 获取最后一条 AI 消息并打印
            ChatMessage last = history.get(history.size() - 1);
            if (last instanceof AiMessage ai) {
                System.out.println(ai.text());
            }
        }
    }
}
