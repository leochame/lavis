package com.lavis.scheduler;

import lombok.Data;

@Data
public class CreateTaskRequest {
    private String name;
    private String description;
    private String cronExpression;
    private String command;
    private boolean enabled = true;
}
