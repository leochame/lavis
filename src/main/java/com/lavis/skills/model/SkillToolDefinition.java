package com.lavis.skills.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 转换为 LLM Tool Definition 的标准格式。
 * 遵循 OpenAI/Anthropic Function Calling 规范。
 *
 * 这是解决"LLM 自主选择技能"问题的核心：
 * - 将 SKILL.md 中的元数据转换为 JSON Schema
 * - LLM 可以根据 description 进行语义路由
 * - 参数定义严格类型化
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillToolDefinition {

    /** 工具名称（转换为 snake_case，如 java_coding） */
    private String name;

    /** 工具描述（用于 LLM 语义路由） */
    private String description;

    /** 参数 JSON Schema */
    private ParameterSchema parameters;

    /**
     * 参数 Schema 定义
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ParameterSchema {
        @JsonProperty("type")
        @Builder.Default
        private String type = "object";

        @JsonProperty("properties")
        private Map<String, PropertyDefinition> properties;

        @JsonProperty("required")
        private List<String> required;
    }

    /**
     * 单个参数属性定义
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PropertyDefinition {
        @JsonProperty("type")
        @Builder.Default
        private String type = "string";

        @JsonProperty("description")
        private String description;

        @JsonProperty("default")
        private Object defaultValue;

        @JsonProperty("enum")
        private List<String> enumValues;
    }

    /**
     * 从 ParsedSkill 构建 ToolDefinition
     */
    public static SkillToolDefinition fromParsedSkill(ParsedSkill skill) {
        // 构建参数 Schema
        Map<String, PropertyDefinition> properties = new LinkedHashMap<>();
        List<String> requiredParams = new java.util.ArrayList<>();

        if (skill.getParameters() != null) {
            for (ParsedSkill.SkillParameter param : skill.getParameters()) {
                PropertyDefinition prop = PropertyDefinition.builder()
                        .type(inferType(param.getDefaultValue()))
                        .description(param.getDescription())
                        .defaultValue(param.getDefaultValue())
                        .build();
                properties.put(param.getName(), prop);

                if (param.isRequired()) {
                    requiredParams.add(param.getName());
                }
            }
        }

        ParameterSchema paramSchema = ParameterSchema.builder()
                .type("object")
                .properties(properties.isEmpty() ? null : properties)
                .required(requiredParams.isEmpty() ? null : requiredParams)
                .build();

        return SkillToolDefinition.builder()
                .name(toSnakeCase(skill.getName()))
                .description(buildDescription(skill))
                .parameters(paramSchema)
                .build();
    }

    /**
     * 构建增强的描述（包含 category 和 version 信息）
     */
    private static String buildDescription(ParsedSkill skill) {
        StringBuilder desc = new StringBuilder();

        if (skill.getDescription() != null) {
            desc.append(skill.getDescription());
        }

        // 添加元信息帮助 LLM 更好地理解
        if (skill.getCategory() != null) {
            desc.append(" [Category: ").append(skill.getCategory()).append("]");
        }

        return desc.toString().trim();
    }

    /**
     * 推断参数类型
     */
    private static String inferType(String defaultValue) {
        if (defaultValue == null) {
            return "string";
        }

        // 尝试解析为数字
        try {
            Integer.parseInt(defaultValue);
            return "integer";
        } catch (NumberFormatException ignored) {}

        try {
            Double.parseDouble(defaultValue);
            return "number";
        } catch (NumberFormatException ignored) {}

        // 布尔值
        if ("true".equalsIgnoreCase(defaultValue) || "false".equalsIgnoreCase(defaultValue)) {
            return "boolean";
        }

        return "string";
    }

    /**
     * 将 kebab-case 或 PascalCase 转换为 snake_case
     * 例如: "Java-Coding" -> "java_coding"
     */
    private static String toSnakeCase(String name) {
        if (name == null) return "unnamed_skill";

        return name
                .replaceAll("([a-z])([A-Z])", "$1_$2")  // PascalCase -> snake_case
                .replaceAll("-", "_")                    // kebab-case -> snake_case
                .replaceAll("\\s+", "_")                 // spaces -> underscore
                .toLowerCase();
    }
}
