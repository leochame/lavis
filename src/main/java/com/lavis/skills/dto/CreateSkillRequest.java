package com.lavis.skills.dto;

import lombok.Data;

@Data
public class CreateSkillRequest {
    private String name;
    private String description;
    private String category;
    private String version;
    private String author;
    private String content;
    private String command;
}
