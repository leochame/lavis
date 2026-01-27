package com.lavis.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "session_messages")
public class SessionMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "message_type", nullable = false)
    private String messageType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "has_image")
    private Boolean hasImage = false;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
