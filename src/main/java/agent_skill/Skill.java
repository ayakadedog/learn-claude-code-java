package agent_skill;

import java.util.Map;

import lombok.Data;

/**
 * Skill - 技能数据类
 * 存储技能的元数据和内容
 */
@Data
public class Skill {

    private final String name;

    private final String description;

    private final Map<String, String> meta;

    private final String body;

    @Override
    public String toString() {
        return "Skill{name='" + name + "', description='" + description + "'}";
    }
}
