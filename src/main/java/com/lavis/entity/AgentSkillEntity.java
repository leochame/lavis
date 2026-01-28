package com.lavis.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "agent_skills")
public class AgentSkillEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column
    private String category;

    @Column
    private String version;

    @Column
    private String author;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    private String command;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "install_source")
    private String installSource;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "use_count")
    private Integer useCount = 0;

    // NOTE: Do not use @Lob here. SQLite JDBC driver does not implement ResultSet#getBlob(),
    // but it does support getBytes(). Mapping as plain byte[] makes Hibernate extract via getBytes().
    @Column(columnDefinition = "BLOB")
    private byte[] embedding;

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
