package com.lavis.entity;

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

    @Column(columnDefinition = "TEXT", nullable = false)
    private String command;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_run_status")
    private String lastRunStatus;

    @Column(name = "last_run_result", columnDefinition = "TEXT")
    private String lastRunResult;

    @Column(name = "run_count")
    private Integer runCount = 0;

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
