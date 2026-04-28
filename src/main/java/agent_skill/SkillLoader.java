package agent_skill;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkillLoader - 技能加载器
 *
 * 功能：递归扫描 skills 目录下的 SKILL.md 文件，解析 YAML frontmatter
 *
 * SKILL.md 文件格式：
 * ---
 * name: pdf
 * description: Process PDF files
 * ---
 * ## PDF Processing Skill
 * 这里是技能的详细内容...
 */
public class SkillLoader {

    /**
     * 正则表达式：匹配 YAML frontmatter
     *
     * 格式要求：
     * ---
     * name: xxx
     * description: xxx
     * ---
     * 内容
     *
     * Pattern.DOTALL 让 . 能匹配换行符
     */
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\n(.*?)\n---\\s*\n(.*)$", Pattern.DOTALL
    );

    /** 存储所有已加载的技能，key 是技能名称 */
    private final Map<String, Skill> skills = new LinkedHashMap<>();

    /** skills 目录的路径 */
    private final Path skillsDir;

    /**
     * 构造器：创建 SkillLoader 实例并立即加载技能
     *
     * @param skillsDir skills 目录的路径
     */
    public SkillLoader(Path skillsDir) {
        this.skillsDir = skillsDir;
        loadSkills();  // 立即加载所有技能
    }

    /**
     * 加载所有技能
     * 递归扫描 skillsDir 目录，找出所有名为 SKILL.md 的文件
     */
    private void loadSkills() {
        // 如果目录不存在，直接返回
        if (!Files.exists(skillsDir)) {
            System.out.println("[SkillLoader] Skills directory not found: " + skillsDir);
            return;
        }

        try {
            // Files.walk() 递归遍历目录下的所有文件
            // filter() 筛选出文件名等于 "SKILL.md" 的文件
            // sorted() 按路径顺序排序（保证加载顺序一致）
            // forEach() 对每个文件执行加载操作
            Files.walk(skillsDir)
                    .filter(p -> p.getFileName().toString().equals("SKILL.md"))
                    .sorted()
                    .forEach(this::loadSkillFile);

        } catch (IOException e) {
            System.out.println("[SkillLoader] Error walking skills directory: " + e.getMessage());
        }

        System.out.println("[SkillLoader] Loaded " + skills.size() + " skills");
    }

    /**
     * 加载单个 SKILL.md 文件
     *
     * @param skillFile SKILL.md 文件的路径
     */
    private void loadSkillFile(Path skillFile) {
        try {
            // 读取文件全部内容
            String content = Files.readString(skillFile);

            // 用正则表达式解析 frontmatter
            Matcher matcher = FRONTMATTER_PATTERN.matcher(content.trim());

            // 如果文件格式不匹配，报错
            if (!matcher.matches()) {
                System.out.println("[SkillLoader] Invalid SKILL.md format: " + skillFile);
                return;
            }

            // matcher.group(1) = frontmatter 部分（---之间的YAML）
            // matcher.group(2) = body 部分（---之后的内容）
            String frontmatter = matcher.group(1);
            String body = matcher.group(2).trim();

            // 解析 YAML frontmatter，得到 name 和 description
            Map<String, String> meta = parseFrontmatter(frontmatter);

            // 技能名称：优先用 YAML 中的 name，否则用目录名
            // skillFile.getParent().getFileName() = 技能目录名，如 "pdf"
            String name = meta.getOrDefault("name", skillFile.getParent().getFileName().toString());

            // 技能描述：优先用 YAML 中的 description
            String description = meta.getOrDefault("description", "");

            // 创建 Skill 对象并存入 Map
            Skill skill = new Skill(name, description, meta, body);
            skills.put(name, skill);

            System.out.println("[SkillLoader] Loaded skill: " + name);

        } catch (IOException e) {
            System.out.println("[SkillLoader] Error reading skill file: " + e.getMessage());
        }
    }

    /**
     * 解析 YAML frontmatter（简化版，只支持 key: value 格式）
     *
     * @param frontmatter YAML 格式的字符串
     * @return 解析后的 Map，key 是属性名，value 是属性值
     */
    private Map<String, String> parseFrontmatter(String frontmatter) {
        Map<String, String> meta = new HashMap<>();

        // 按行分割
        for (String line : frontmatter.split("\n")) {
            // 找到第一个冒号的位置
            int colonIndex = line.indexOf(':');

            // 冒号必须在位置 > 0（行首不能是冒号）
            if (colonIndex > 0) {
                // 冒号前是 key
                String key = line.substring(0, colonIndex).trim();
                // 冒号后是 value
                String value = line.substring(colonIndex + 1).trim();

                meta.put(key, value);
            }
        }

        return meta;
    }

    /**
     * 获取所有技能的名称和描述列表
     * 用于在系统提示词中告诉 LLM 有哪些技能可用
     *
     * @return 格式化的技能列表字符串，每行一个技能
     *         例如：
     *           - pdf: Process PDF files
     *           - code-review: Review code for quality
     */
    public String getDescriptions() {
        if (skills.isEmpty()) {
            return "  (no skills available)";
        }

        StringBuilder sb = new StringBuilder();
        for (Skill skill : skills.values()) {
            sb.append("  - ").append(skill.getName());
            if (!skill.getDescription().isEmpty()) {
                sb.append(": ").append(skill.getDescription());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 按名称获取技能的完整内容
     * 当 LLM 调用 load_skill 工具时会执行这个方法
     *
     * @param name 技能名称
     * @return 技能的完整内容（包装在 <skill> 标签中），或错误信息
     */
    public String getContent(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            // 技能不存在，返回错误信息和可用技能列表
            return "Error: Unknown skill '" + name + "'. Available skills: " + String.join(", ", skills.keySet());
        }
        // 返回格式：<skill name="pdf">\n技能内容\n</skill>
        return "<skill name=\"" + skill.getName() + "\">\n" + skill.getBody() + "\n</skill>";
    }

    /**
     * 获取所有技能名称的集合
     *
     * @return 不可修改的技能名称 Set
     */
    public Set<String> getSkillNames() {
        return Collections.unmodifiableSet(skills.keySet());
    }

    /**
     * 检查指定名称的技能是否存在
     *
     * @param name 技能名称
     * @return 是否存在
     */
    public boolean hasSkill(String name) {
        return skills.containsKey(name);
    }

    /**
     * 获取已加载技能的数量
     *
     * @return 技能数量
     */
    public int getSkillCount() {
        return skills.size();
    }
}
