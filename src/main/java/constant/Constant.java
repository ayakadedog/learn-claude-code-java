package constant;

import agent_tool.AgentTools;

/**
 * 全局常量配置类
 * 存放模型配置、API密钥、系统提示词模板等
 */
public class Constant {
    /**
     * 系统提示词模板
     * %s 会被替换为当前工作目录
     * %s 会被替换为技能列表
     */
    public static final String QUERY_TEMPLATE =
            "You are a coding agent at %s in windows.\n\n"
            + "## CRITICAL: EXECUTION FIRST\n"
            + "Planning is NOT progress. Creating tasks is NOT doing work.\n"
            + "After creating at most 5-8 tasks, IMMEDIATELY start executing the first one.\n"
            + "Use task_update to mark a task in_progress BEFORE you start working on it.\n"
            + "Never create more than 8 tasks without completing at least one.\n\n"
            + "## Workflow\n"
            + "1. Plan: Create tasks with task_create (max 8, keep it simple)\n"
            + "2. Execute: Mark task in_progress → do the actual work with run/read/write/edit → mark completed\n"
            + "3. Repeat: Move to next task\n\n"
            + "## Tools\n"
            + "Use the subagent tool to delegate independent tasks like exploration or research.\n"
            + "Prefer tools over prose.\n\n"
            + "## Background Tasks\n"
            + "Use bg_run for long-running commands (dev servers, build watches, test suites).\n"
            + "bg_run returns immediately with a task_id. Results are delivered automatically.\n"
            + "Use bg_status to check a task, bg_list to see all background tasks.\n"
            + "Use run (not bg_run) for quick commands that finish in seconds.\n\n"
            + "Skills available:\n%s\n\n"
            + "Use load_skill to get detailed instructions for a skill when needed.";

    /**
     * 获取带技能列表的系统提示词
     * 动态获取，因为技能列表在运行时加载
     */
    public static String getQuery() {
        String workdir = System.getProperty("user.dir");
        String skillDescriptions = AgentTools.getSkillLoader().getDescriptions();
        return String.format(QUERY_TEMPLATE, workdir, skillDescriptions);
    }

    /**
     * 最终的系统提示词（已替换工作目录）
     * @deprecated 使用 {@link #getQuery()} 代替，支持动态技能列表
     */
    @Deprecated
    public static final String QUERY = String.format(QUERY_TEMPLATE, System.getProperty("user.dir"), "(loading...)");

    /**
     * 子代理系统提示词模板
     * 子代理没有 todo 工具，专注于完成具体任务并总结
     */
    public static final String SUBAGENT_QUERY_TEMPLATE =
            "You are a coding subagent at %s in windows.\n"
            + "Complete the given task independently using available tools.\n"
            + "When finished, provide a concise summary of your findings.";

    /**
     * 子代理系统提示词（已替换工作目录）
     */
    public static final String SUBAGENT_QUERY = String.format(SUBAGENT_QUERY_TEMPLATE, System.getProperty("user.dir"));

    // ================== API 基础地址 ==================

    /** DeepSeek API 地址 */
    public static final String BASEURL = "https://api.deepseek.com/v1";

    /** MiniMax API 地址 */
    public static final String MINIMAXBASEURL = "https://api.minimax.chat/v1";

    // ================== 模型名称 ==================

    /** DeepSeek 模型名称 */
    public static final String DEEPSEEKMODEL = "deepseek-chat";

    /** MiniMax 模型名称 */
    public static final String MINIMAXMODEL = "MiniMax-M2.7";

    // ================== API 密钥 ==================

    /** DeepSeek API 密钥 */
    public static final String DEEPSEEKKEY = "";

    /** MiniMax API 密钥 */
    public static final String MINIMAXKEY = "";
}
