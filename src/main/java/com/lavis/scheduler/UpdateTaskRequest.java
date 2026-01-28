package com.lavis.scheduler;

import lombok.Data;

@Data
public class UpdateTaskRequest {
    private String name;
    private String description;
    private String cronExpression;
    private String command;
    private Boolean enabled;
}
