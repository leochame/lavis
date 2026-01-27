package com.lavis.repository;

import com.lavis.entity.SessionMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {

    List<SessionMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<SessionMessageEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<SessionMessageEntity> findBySessionIdAndCreatedAtAfter(String sessionId, LocalDateTime createdAt);

    Long countBySessionId(String sessionId);
}
