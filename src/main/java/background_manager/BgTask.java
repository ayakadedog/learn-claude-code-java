package background_manager;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 后台任务数据模型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BgTask {

    /** 任务 ID（UUID 前 8 位） */
    private String id;

    /** 执行的命令 */
    private String command;

    /** 任务状态：running / completed / failed */
    private String status;

    /** 命令输出 */
    private String output;

    /** 任务启动时间（毫秒时间戳） */
    private Long startTime;
}
