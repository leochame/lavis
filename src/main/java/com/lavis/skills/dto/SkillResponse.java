package com.lavis.skills.dto;

import com.lavis.entity.AgentSkillEntity;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SkillResponse {
    private String id;
    private String name;
    private String description;
    private String category;
    private String version;
    private String author;
    private String content;
    private String command;
    private Boolean enabled;
    private String installSource;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastUsedAt;
    private Integer useCount;

    public static SkillResponse from(AgentSkillEntity entity) {
        return SkillResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .category(entity.getCategory())
                .version(entity.getVersion())
                .author(entity.getAuthor())
                .content(entity.getContent())
                .command(entity.getCommand())
                .enabled(entity.getEnabled())
                .installSource(entity.getInstallSource())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .lastUsedAt(entity.getLastUsedAt())
                .useCount(entity.getUseCount())
                .build();
    }
}
