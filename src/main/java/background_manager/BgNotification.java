package background_manager;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 后台任务通知
 * 子进程完成后，结果封装为此对象进入通知队列
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BgNotification {

    /** 任务 ID */
    private String taskId;

    /** 任务结果（截断后的摘要） */
    private String result;
}
