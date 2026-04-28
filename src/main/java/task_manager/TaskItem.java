package task_manager;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * TaskItem - 持久化任务数据模型
 * 支持依赖图（blockedBy / blocks），每个任务独立 JSON 文件存储
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskItem {

    /** 任务 ID，单调递增 */
    private Integer id;

    /** 任务主题（简短描述） */
    private String subject;

    /** 任务详细描述 */
    private String description;

    /** 任务状态：pending / in_progress / completed / cancelled */
    private String status;

    /** 阻塞我的任务 ID 列表（入边） */
    private List<Integer> blockedBy;

    /** 我阻塞的任务 ID 列表（出边） */
    private List<Integer> blocks;

    /** 任务负责人（可用于子代理认领） */
    private String owner;

    /**
     * 创建一个最小化的 TaskItem（仅包含必要字段）
     */
    public TaskItem(Integer id, String subject) {
        this.id = id;
        this.subject = subject;
        this.description = "";
        this.status = "pending";
        this.blockedBy = new ArrayList<>();
        this.blocks = new ArrayList<>();
        this.owner = "";
    }
}
