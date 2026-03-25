package agent_tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Todo 项，代表一个任务
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TodoItem {
    private String id;
    private String text;
    private String status;
}
