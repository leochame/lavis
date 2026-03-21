package com.lavis.infra.persistence.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "scheduled_tasks")
public class ScheduledTaskEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    @Column(name = "schedule_mode", nullable = false)
    private String scheduleMode = "CRON";

    @Column(name = "interval_seconds")
    private Integer intervalSeconds;

    @Column(name = "execution_mode", nullable = false)
    private String executionMode = "COMMAND";

    @Column(name = "source_type", nullable = false)
    private String sourceType = "MANUAL";

    @Column(name = "source_path")
    private String sourcePath;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String command;

    @Column(name = "request_content", columnDefinition = "TEXT")
    private String requestContent;

    @Column(name = "request_use_orchestrator")
    private Boolean requestUseOrchestrator = false;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "last_run_status")
    private String lastRunStatus;

    @Column(name = "last_run_result", columnDefinition = "TEXT")
    private String lastRunResult;

    @Column(name = "run_count")
    private Integer runCount = 0;

    @Column(name = "penalty_points")
    private Integer penaltyPoints = 0;

    @Column(name = "auto_paused")
    private Boolean autoPaused = false;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
