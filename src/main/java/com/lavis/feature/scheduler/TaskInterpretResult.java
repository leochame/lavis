package com.lavis.feature.scheduler;

import lombok.Data;

@Data
public class TaskInterpretResult {
    private boolean ready;
    private String missingField;
    private String message;
    private TaskDraft draft;
    private TaskRequest task;

    @Data
    public static class TaskDraft {
        private String name;
        private String scheduleMode;
        private String cronExpression;
        private Integer intervalSeconds;
        private String requestContent;
        private Boolean requestUseOrchestrator;
        private Boolean enabled;
    }
}

