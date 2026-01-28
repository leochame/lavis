package com.lavis.skills.model;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Parsed representation of a SKILL.md file.
 * Contains frontmatter metadata and markdown body.
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

    /** The markdown body content (excluding frontmatter) */
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
}
