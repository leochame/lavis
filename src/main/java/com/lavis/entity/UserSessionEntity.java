package com.lavis.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_sessions")
public class UserSessionEntity {

    @Id
    private String id;

    @Column(name = "session_key", unique = true, nullable = false)
    private String sessionKey;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    @Column(name = "message_count")
    private Integer messageCount = 0;

    @Column(name = "total_tokens")
    private Integer totalTokens = 0;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        lastActiveAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        lastActiveAt = LocalDateTime.now();
    }
}
