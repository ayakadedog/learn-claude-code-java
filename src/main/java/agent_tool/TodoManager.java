package agent_tool;

import java.util.ArrayList;
import java.util.List;

/**
 * TodoManager - 任务清单管理器
 * <p>
 * 用于 AI Agent 管理多步骤任务，支持三种状态：
 * - pending: 待完成 [ ]
 * - in_progress: 正在进行 [>]
 * - completed: 已完成 [x]
 */
public class TodoManager {

    /** 最大允许的任务数量 */
    private static final int MAX_ITEMS = 20;

    /** 任务列表 */
    private final List<TodoItem> items = new ArrayList<>();

    /**
     * 渲染任务清单为可读文本
     *
     * @return 格式化的任务清单
     */
    public String render() {
        if (items.isEmpty()) {
            return "No todos.";
        }

        StringBuilder sb = new StringBuilder();

        for (TodoItem item : items) {
            String marker;
            switch (item.getStatus()) {
                case "in_progress":
                    marker = "[>]";
                    break;
                case "completed":
                    marker = "[x]";
                    break;
                default:
                    marker = "[ ]";
            }
            sb.append(marker).append(" #").append(item.getId()).append(": ").append(item.getText()).append("\n");
        }

        long completedCount = items.stream()
                .filter(item -> item.getStatus().equals("completed"))
                .count();

        sb.append("\n(").append(completedCount).append("/").append(items.size()).append(" completed)");

        return sb.toString();
    }
    /**
     * 更新任务清单
     *
     * @param items 要更新的任务列表（JSON 解析后的列表）
     * @return 渲染后的任务清单文本
     */
    public String update(List<TodoItem> items) {
        if (items.size() > MAX_ITEMS) {
            return "Error: Max " + MAX_ITEMS + " todos allowed";
        }

        System.out.println("[DEBUG] Received items: " + items);

        List<TodoItem> validated = new ArrayList<>();
        int inProgressCount = 0;

        for (int i = 0; i < items.size(); i++) {
            TodoItem item = items.get(i);
            String id = item.getId() != null ? item.getId() : String.valueOf(i + 1);
            String text = item.getText();
            String status = item.getStatus() != null ? item.getStatus().toLowerCase() : "pending";

            if (text == null || text.trim().isEmpty()) {
                return "Error: Item #" + id + " - text required";
            }

            if (!status.equals("pending") && !status.equals("in_progress") && !status.equals("completed")) {
                return "Error: Item #" + id + " - invalid status '" + status + "'";
            }

            if (status.equals("in_progress")) {
                inProgressCount++;
            }

            TodoItem validatedItem = new TodoItem(id, text.trim(), status);
            validated.add(validatedItem);
        }

        if (inProgressCount > 1) {
            return "Error: Only one task can be in_progress at a time";
        }

        this.items.clear();
        this.items.addAll(validated);

        return render();
    }


    /**
     * 获取当前任务列表
     */
    public List<TodoItem> getItems() {
        return new ArrayList<>(items);
    }
}
