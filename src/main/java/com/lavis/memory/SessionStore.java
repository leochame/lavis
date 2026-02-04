package com.lavis.memory;

import com.lavis.entity.SessionMessageEntity;
import com.lavis.entity.UserSessionEntity;
import com.lavis.repository.SessionMessageRepository;
import com.lavis.repository.UserSessionRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Session persistence service
 * Manages conversation session storage to database
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionStore {

    private final UserSessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;

    /**
     * Create a new session
     */
    @Transactional
    public String createSession() {
        String sessionKey = UUID.randomUUID().toString();

        UserSessionEntity session = new UserSessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setSessionKey(sessionKey);
        session.setMessageCount(0);
        session.setTotalTokens(0);
        session.setMetadata("{}");

        sessionRepository.save(session);
        log.info("Created new session: {}", sessionKey);

        return sessionKey;
    }

    /**
     * Save a message to session
     */
    @Transactional
    public void saveMessage(String sessionKey, ChatMessage message, int tokenCount) {
        saveMessage(sessionKey, message, tokenCount, null, null);
    }

    /**
     * Save a message to session with Turn context
     *
     * @param sessionKey Session identifier
     * @param message    Chat message to save
     * @param tokenCount Token count for this message
     * @param turnId     Turn identifier (nullable for backward compatibility)
     * @param imageId    Image identifier if message contains image (nullable)
     */
    @Transactional
    public void saveMessage(String sessionKey, ChatMessage message, int tokenCount, String turnId, String imageId) {
        UserSessionEntity session = sessionRepository.findBySessionKey(sessionKey)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionKey));

        SessionMessageEntity messageEntity = new SessionMessageEntity();
        messageEntity.setSessionId(session.getId());
        messageEntity.setMessageType(getMessageType(message));
        messageEntity.setContent(extractTextContent(message));
        messageEntity.setHasImage(hasImageContent(message));
        messageEntity.setTokenCount(tokenCount);

        // Context Engineering: Turn tracking
        messageEntity.setTurnId(turnId);
        messageEntity.setImageId(imageId);
        messageEntity.setIsCompressed(false);

        // Set turn position from TurnContext if available
        TurnContext currentTurn = TurnContext.current();
        if (currentTurn != null && turnId != null && turnId.equals(currentTurn.getTurnId())) {
            messageEntity.setTurnPosition(currentTurn.nextPosition());
        }

        messageRepository.save(messageEntity);

        // Update session statistics
        session.setMessageCount(session.getMessageCount() + 1);
        session.setTotalTokens(session.getTotalTokens() + tokenCount);
        sessionRepository.save(session);

        log.debug("Saved message to session {}: type={}, tokens={}, turnId={}",
                sessionKey, messageEntity.getMessageType(), tokenCount, turnId);
    }

    /**
     * Load all messages from a session
     */
    @Transactional(readOnly = true)
    public List<SessionMessageEntity> loadMessages(String sessionKey) {
        UserSessionEntity session = sessionRepository.findBySessionKey(sessionKey)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionKey));

        return messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
    }

    /**
     * Get session statistics
     */
    @Transactional(readOnly = true)
    public SessionStats getSessionStats(String sessionKey) {
        UserSessionEntity session = sessionRepository.findBySessionKey(sessionKey)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionKey));

        long messageCount = messageRepository.countBySessionId(session.getId());

        return new SessionStats(
                session.getSessionKey(),
                messageCount,
                session.getTotalTokens(),
                session.getCreatedAt(),
                session.getLastActiveAt()
        );
    }

    /**
     * Delete old sessions (older than specified days)
     */
    @Transactional
    public int deleteOldSessions(int daysToKeep) {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysToKeep);
        List<UserSessionEntity> oldSessions = sessionRepository.findByLastActiveAtBefore(cutoffDate);

        int deletedCount = 0;
        for (UserSessionEntity session : oldSessions) {
            // Delete messages first (foreign key constraint)
            messageRepository.deleteBySessionId(session.getId());
            sessionRepository.delete(session);
            deletedCount++;
        }

        log.info("Deleted {} old sessions (older than {} days)", deletedCount, daysToKeep);
        return deletedCount;
    }

    /**
     * Delete messages with images from old sessions
     */
    @Transactional
    public int cleanupOldImages(String sessionKey, int keepLastN) {
        UserSessionEntity session = sessionRepository.findBySessionKey(sessionKey)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionKey));

        List<SessionMessageEntity> imageMessages = messageRepository.findBySessionIdAndHasImageOrderByCreatedAtDesc(
                session.getId(), true);

        if (imageMessages.size() <= keepLastN) {
            return 0;
        }

        // Keep last N, delete the rest
        List<SessionMessageEntity> toDelete = imageMessages.subList(keepLastN, imageMessages.size());
        int deletedCount = toDelete.size();

        for (SessionMessageEntity msg : toDelete) {
            messageRepository.delete(msg);
        }

        log.info("Cleaned up {} old image messages from session {}", deletedCount, sessionKey);
        return deletedCount;
    }

    // Helper methods

    private String getMessageType(ChatMessage message) {
        if (message instanceof UserMessage) {
            return "user";
        } else if (message instanceof AiMessage) {
            return "assistant";
        } else if (message instanceof SystemMessage) {
            return "system";
        } else {
            return "tool";
        }
    }

    private String extractTextContent(ChatMessage message) {
        if (message instanceof UserMessage userMsg) {
            if (userMsg.hasSingleText()) {
                return userMsg.singleText();
            } else {
                // Extract text from contents
                return userMsg.contents().stream()
                        .filter(c -> !(c instanceof ImageContent))
                        .map(Content::toString)
                        .collect(Collectors.joining("\n"));
            }
        } else if (message instanceof AiMessage aiMsg) {
            return aiMsg.text();
        } else if (message instanceof SystemMessage sysMsg) {
            return sysMsg.text();
        }
        return message.toString();
    }

    private boolean hasImageContent(ChatMessage message) {
        if (message instanceof UserMessage userMsg) {
            return userMsg.contents().stream()
                    .anyMatch(c -> c instanceof ImageContent);
        }
        return false;
    }

    /**
     * Session statistics record
     */
    public record SessionStats(
            String sessionKey,
            long messageCount,
            int totalTokens,
            LocalDateTime createdAt,
            LocalDateTime lastActiveAt
    ) {}
}
