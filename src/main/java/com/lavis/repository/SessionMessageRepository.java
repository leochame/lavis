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

    List<SessionMessageEntity> findBySessionIdAndHasImageOrderByCreatedAtDesc(String sessionId, boolean hasImage);

    void deleteBySessionId(String sessionId);

    Long countBySessionId(String sessionId);

    // Context Engineering: Turn-based queries

    /**
     * 查找指定 Turn 的所有消息
     */
    List<SessionMessageEntity> findByTurnIdOrderByTurnPositionAsc(String turnId);

    /**
     * 查找指定 Turn 中包含图片的消息
     */
    List<SessionMessageEntity> findByTurnIdAndHasImageOrderByTurnPositionAsc(String turnId, boolean hasImage);

    /**
     * 查找指定会话中所有未压缩的历史 Turn（排除当前 Turn）
     */
    List<SessionMessageEntity> findBySessionIdAndTurnIdNotAndIsCompressedFalseAndHasImageTrue(
            String sessionId, String currentTurnId);

    /**
     * 查找指定会话的所有不同 Turn ID
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT m.turnId FROM SessionMessageEntity m WHERE m.sessionId = :sessionId AND m.turnId IS NOT NULL ORDER BY m.createdAt ASC")
    List<String> findDistinctTurnIdsBySessionId(String sessionId);

    /**
     * 统计指定 Turn 的图片数量
     */
    Long countByTurnIdAndHasImage(String turnId, boolean hasImage);
}
