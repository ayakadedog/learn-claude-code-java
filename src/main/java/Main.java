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
        AgentLoop agent = new AgentLoop();
        List<ChatMessage> history = new ArrayList<>();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\033[36ms03 >> \033[0m");

            String query;
            try {
                query = scanner.nextLine();
            } catch (Exception e) {
                break;
            }

            if (query.trim().toLowerCase().isEmpty()
                    || query.trim().toLowerCase().equals("q")
                    || query.trim().toLowerCase().equals("exit")) {
                break;
            }

            history.add(UserMessage.from(query));

            agent.run(history);

            if (!history.isEmpty()) {
                ChatMessage last = history.get(history.size() - 1);
                if (last instanceof AiMessage ai && ai.text() != null) {
                    System.out.println(ai.text());
                }
            }

            System.out.println();
        }
    }
}
