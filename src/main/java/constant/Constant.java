package constant;

/**
 * 全局常量配置类
 * 存放模型配置、API密钥、系统提示词模板等
 */
public class Constant {

    /**
     * 系统提示词模板
     * %s 会被替换为当前工作目录
     */
    public static final String QUERY_TEMPLATE =
            "You are a coding agent at %s in windows.\n"
            + "Use the todo tool to plan multi-step tasks. Mark in_progress before starting, completed when done.\n"
            + "Prefer tools over prose.";

    /**
     * 最终的系统提示词（已替换工作目录）
     */
    public static final String QUERY = String.format(QUERY_TEMPLATE, System.getProperty("user.dir"));

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

  }
