package com.lavis.skills.model;

import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.*;

import static dev.langchain4j.agent.tool.JsonSchemaProperty.*;

/**
 * Parsed representation of a SKILL.md file.
 * Contains frontmatter metadata and markdown body.
 *
 * 增强功能：
 * - 支持转换为 LangChain4j ToolSpecification（用于 LLM 工具调用）
 * - 支持生成执行上下文（用于知识注入）
 * - 类型安全的参数处理
 */
@Data
@Builder
public class ParsedSkill {
    private String name;
    private String description;
    private String category;
    private String version;
    private String author;
    private String command;

    /** The markdown body content (excluding frontmatter) - 包含 Best Practices 等知识 */
    private String content;

    /** Source file path */
    private Path sourcePath;

    /** Parameter definitions extracted from the skill */
    private List<SkillParameter> parameters;

    @Data
    @Builder
    public static class SkillParameter {
        private String name;
        private String description;
        private String defaultValue;
        private boolean required;

        /** 参数类型（string, integer, number, boolean） */
        @Builder.Default
        private String type = "string";

        /** 枚举值（可选） */
        private List<String> enumValues;
    }

    /**
     * 转换为 LangChain4j ToolSpecification。
     *
     * 这是实现"技能即工具"的核心方法：
     * - LLM 可以看到所有可用的 Skills
     * - 根据 description 进行语义路由
     * - 参数定义为严格的 JSON Schema
     */
    public ToolSpecification toToolSpecification() {
        // 构建增强的描述
        String enhancedDescription = buildEnhancedDescription();

        // 使用 ToolSpecification.builder()
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(toToolName())
                .description(enhancedDescription);

        // 为每个参数添加 JsonSchemaProperty
        if (parameters != null) {
            for (SkillParameter param : parameters) {
                List<JsonSchemaProperty> props = new ArrayList<>();

                // 添加类型
                String type = param.getType() != null ? param.getType() : inferType(param.getDefaultValue());
                props.add(type(type));

                // 添加描述
                if (param.getDescription() != null) {
                    props.add(description(param.getDescription()));
                }

                // 添加枚举值
                if (param.getEnumValues() != null && !param.getEnumValues().isEmpty()) {
                    props.add(enums(param.getEnumValues().toArray(new String[0])));
                }

                builder.addParameter(param.getName(), props.toArray(new JsonSchemaProperty[0]));
            }
        }

        return builder.build();
    }

    /**
     * 构建 SkillToolDefinition（自定义格式，用于序列化）
     */
    public SkillToolDefinition toSkillToolDefinition() {
        return SkillToolDefinition.fromParsedSkill(this);
    }

    /**
     * 构建执行上下文（包含知识注入）
     *
     * @param params 来自 LLM Function Call 的结构化参数
     * @return 包含完整知识的执行上下文
     */
    public SkillExecutionContext buildExecutionContext(Map<String, Object> params) {
        // 将 Object 参数转换为 String 用于命令解析
        Map<String, String> stringParams = new HashMap<>();
        if (params != null) {
            params.forEach((k, v) -> stringParams.put(k, v != null ? v.toString() : ""));
        }

        return SkillExecutionContext.builder()
                .skillName(name)
                .description(description)
                .knowledgeContent(content)  // 关键：注入 Markdown 正文知识
                .parameters(params)
                .commandTemplate(command)
                .resolvedCommand(resolveCommand(stringParams))
                .build();
    }

    /**
     * 转换为工具名称（snake_case 格式）
     */
    public String toToolName() {
        if (name == null) return "unnamed_skill";

        return name
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("-", "_")
                .replaceAll("\\s+", "_")
                .toLowerCase();
    }

    /**
     * Substitute parameters in the command string.
     * Replaces {{paramName}} with actual values.
     */
    public String resolveCommand(Map<String, String> params) {
        if (command == null) {
            return null;
        }
        String resolved = command;
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                resolved = resolved.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        }
        // Apply default values for unresolved parameters
        if (parameters != null) {
            for (SkillParameter param : parameters) {
                String placeholder = "{{" + param.getName() + "}}";
                if (resolved.contains(placeholder) && param.getDefaultValue() != null) {
                    resolved = resolved.replace(placeholder, param.getDefaultValue());
                }
            }
        }
        return resolved;
    }

    // ==================== Private Helper Methods ====================

    /**
     * 构建增强的描述（包含 category 信息，帮助 LLM 更好地理解）
     */
    private String buildEnhancedDescription() {
        StringBuilder desc = new StringBuilder();

        if (description != null && !description.isBlank()) {
            desc.append(description);
        } else {
            desc.append("Execute the ").append(name).append(" skill");
        }

        if (category != null && !category.isBlank()) {
            desc.append(" [Category: ").append(category).append("]");
        }

        // 添加提示，告诉 LLM 这个技能有详细的知识文档
        if (content != null && !content.isBlank()) {
            desc.append(" (This skill includes detailed guidelines that will be loaded upon execution)");
        }

        return desc.toString();
    }

    /**
     * 从默认值推断参数类型
     */
    private String inferType(String defaultValue) {
        if (defaultValue == null) {
            return "string";
        }

        try {
            Integer.parseInt(defaultValue);
            return "integer";
        } catch (NumberFormatException ignored) {}

        try {
            Double.parseDouble(defaultValue);
            return "number";
        } catch (NumberFormatException ignored) {}

        if ("true".equalsIgnoreCase(defaultValue) || "false".equalsIgnoreCase(defaultValue)) {
            return "boolean";
        }

        return "string";
    }
}
