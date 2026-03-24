import agent_loop.AgentLoop;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;


public class Main {


    public static void main(String[] args) throws Exception {

        AgentLoop agent = new AgentLoop();  

        List<ChatMessage> history = new ArrayList<>();

        Scanner scanner = new Scanner(System.in);

        while (true) {

            System.out.print("\u001B[36ms01 >> \u001B[0m");

            String query = scanner.nextLine();

            if (query.trim().isEmpty() || query.equals("exit")) {
                break;
            }

            history.add(UserMessage.from(query));

            agent.run(query);

            ChatMessage last = history.get(history.size() - 1);

            if (last instanceof AiMessage ai) {
                System.out.println(ai.text());
            }
        }
    }

}

