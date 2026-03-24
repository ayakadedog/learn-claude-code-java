package constant;

public class Constant {


    public static final String QUERY_TEMPLATE = "You are a coding agent at %s in windows. Use bash to solve tasks. Act, don't explain";
    public static final String QUERY = String.format(QUERY_TEMPLATE, System.getProperty("user.dir"));
    public static final String BASEURL = "https://api.deepseek.com/v1";
    public static final String MINIMAXBASEURL = "https://api.minimax.chat/v1";
    public static final String DEEPSEEKMODEL = "deepseek-chat";
    public static final String MINIMAXMODEL = "MiniMax-M2.7";


}
