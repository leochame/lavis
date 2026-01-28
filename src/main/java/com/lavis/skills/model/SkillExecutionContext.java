package com.lavis.skills.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Skill 执行上下文。
 *
 * 这是解决"Context Gap"问题的核心数据结构：
 * - 包含 Skill 的完整知识（Markdown 正文）
 * - 包含结构化的参数
 * - 提供生成 System Prompt 注入的方法
 */
@Data
@Builder
public class SkillExecutionContext {

    /** 技能名称 */
    private String skillName;

    /** 技能描述 */
    private String description;

    /** 技能的完整知识内容（Markdown 正文，包含 Best Practices 等） */
    private String knowledgeContent;

    /** 结构化参数（来自 LLM Function Call） */
    private Map<String, Object> parameters;

    /** 原始命令模板 */
    private String commandTemplate;

    /** 解析后的命令（参数已替换） */
    private String resolvedCommand;

    /**
     * 生成用于注入到 LLM 上下文的 System Message。
     *
     * 这是"上下文注入"的核心方法：
     * 将 SKILL.md 中的知识转换为 LLM 可理解的指令。
     */
    public String toSystemPromptInjection() {
        StringBuilder sb = new StringBuilder();

        sb.append("## Active Skill: ").append(skillName).append("\n\n");

        if (description != null && !description.isBlank()) {
            sb.append("### Purpose\n");
            sb.append(description).append("\n\n");
        }

        if (knowledgeContent != null && !knowledgeContent.isBlank()) {
            sb.append("### Skill Knowledge & Guidelines\n");
            sb.append("The following are the best practices and guidelines for this skill. ");
            sb.append("You MUST follow these instructions when executing this skill:\n\n");
            sb.append(knowledgeContent).append("\n\n");
        }

        if (parameters != null && !parameters.isEmpty()) {
            sb.append("### Provided Parameters\n");
            sb.append("```json\n");
            sb.append(formatParameters(parameters));
            sb.append("\n```\n\n");
        }

        sb.append("### Execution Mode\n");
        sb.append("Apply the above knowledge and guidelines to complete the user's request. ");
        sb.append("Ensure your response adheres to all specified best practices.\n");

        return sb.toString();
    }

    /**
     * 生成用于注入到 User Message 的上下文（备选方案）。
     * 某些场景下，将知识作为 User Message 的一部分可能更有效。
     */
    public String toUserMessageInjection() {
        StringBuilder sb = new StringBuilder();

        sb.append("[Skill Activated: ").append(skillName).append("]\n\n");

        if (knowledgeContent != null && !knowledgeContent.isBlank()) {
            sb.append("Please follow these guidelines:\n\n");
            sb.append(knowledgeContent).append("\n\n");
        }

        sb.append("---\n");

        return sb.toString();
    }

    private String formatParameters(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            sb.append("  \"").append(entry.getKey()).append("\": ");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else {
                sb.append(value);
            }
            if (i < params.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
            i++;
        }
        sb.append("}");
        return sb.toString();
    }
}
