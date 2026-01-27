package com.lavis.memory;

import com.lavis.cognitive.memory.ImageContentCleanableChatMemory;
import dev.langchain4j.data.message.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * Memory manager coordinator
 * Orchestrates all memory management components for 7x24 operation
 * - Session persistence
 * - Image cleanup
 * - Context compression
 * - Memory monitoring
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryManager {

    private static final int DEFAULT_KEEP_IMAGES = 10;
    private static final int DEFAULT_TOKEN_THRESHOLD = 100_000;
    private static final int DEFAULT_KEEP_RECENT_MESSAGES = 10;
    private static final int DEFAULT_SESSION_RETENTION_DAYS = 30;

    private final SessionStore sessionStore;
    private final ImageCleanupService imageCleanupService;
    private final ContextCompactor contextCompactor;

    private String currentSessionKey;
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    /**
     * Initialize a new session
     */
    public String initializeSession() {
        currentSessionKey = sessionStore.createSession();
        log.info("Initialized new session: {}", currentSessionKey);
        return currentSessionKey;
    }

    /**
     * Get current session key
     */
    public String getCurrentSessionKey() {
        if (currentSessionKey == null) {
            currentSessionKey = initializeSession();
        }
        return currentSessionKey;
    }

    /**
     * Save a message to current session
     */
    public void saveMessage(ChatMessage message, int tokenCount) {
        String sessionKey = getCurrentSessionKey();
        sessionStore.saveMessage(sessionKey, message, tokenCount);
    }

    /**
     * Perform memory management on chat history
     * - Clean up old images
     * - Compress if needed
     * - Persist to database
     */
    public MemoryManagementResult manageMemory(ImageContentCleanableChatMemory chatMemory) {
        List<ChatMessage> messages = chatMemory.messages();

        // 1. Clean up old images
        int imagesCleanedInMemory = imageCleanupService.cleanupInMemoryImages(
                chatMemory, DEFAULT_KEEP_IMAGES);
        int imagesCleanedInDb = imageCleanupService.cleanupSessionImages(
                getCurrentSessionKey(), DEFAULT_KEEP_IMAGES);

        // 2. Check if compression is needed
        boolean compressionNeeded = contextCompactor.needsCompression(
                messages, DEFAULT_TOKEN_THRESHOLD);

        List<ChatMessage> compressedMessages = messages;
        if (compressionNeeded) {
            compressedMessages = contextCompactor.compressHistory(
                    messages, DEFAULT_KEEP_RECENT_MESSAGES);
        }

        // 3. Get memory stats
        MemoryStats memoryStats = getMemoryStats();

        return new MemoryManagementResult(
                imagesCleanedInMemory + imagesCleanedInDb,
                compressionNeeded,
                compressedMessages.size(),
                memoryStats
        );
    }

    /**
     * Scheduled cleanup task - runs every hour
     */
    @Scheduled(fixedRate = 3600000) // Every hour
    public void scheduledCleanup() {
        log.info("Starting scheduled memory cleanup...");

        try {
            // Clean up old sessions
            int deletedSessions = sessionStore.deleteOldSessions(DEFAULT_SESSION_RETENTION_DAYS);

            // Clean up images in current session
            if (currentSessionKey != null) {
                int deletedImages = imageCleanupService.cleanupSessionImages(
                        currentSessionKey, DEFAULT_KEEP_IMAGES);
                log.info("Cleaned up {} images from current session", deletedImages);
            }

            // Log memory stats
            MemoryStats stats = getMemoryStats();
            log.info("Memory usage: Heap={} MB, Used={} MB ({}%)",
                    stats.heapMaxMB(), stats.heapUsedMB(), stats.heapUsagePercent());

            log.info("Scheduled cleanup completed: {} old sessions deleted", deletedSessions);
        } catch (Exception e) {
            log.error("Error during scheduled cleanup", e);
        }
    }

    /**
     * Get current memory statistics
     */
    public MemoryStats getMemoryStats() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();

        long heapUsed = heapUsage.getUsed();
        long heapMax = heapUsage.getMax();
        long heapCommitted = heapUsage.getCommitted();

        int usagePercent = (int) ((heapUsed * 100) / heapMax);

        return new MemoryStats(
                heapUsed / (1024 * 1024),
                heapMax / (1024 * 1024),
                heapCommitted / (1024 * 1024),
                usagePercent
        );
    }

    /**
     * Get session statistics
     */
    public SessionStore.SessionStats getSessionStats() {
        return sessionStore.getSessionStats(getCurrentSessionKey());
    }

    /**
     * Reset current session
     */
    public void resetSession() {
        log.info("Resetting session: {}", currentSessionKey);
        currentSessionKey = null;
    }

    /**
     * Memory management result record
     */
    public record MemoryManagementResult(
            int imagesCleanedCount,
            boolean compressionPerformed,
            int finalMessageCount,
            MemoryStats memoryStats
    ) {}

    /**
     * Memory statistics record
     */
    public record MemoryStats(
            long heapUsedMB,
            long heapMaxMB,
            long heapCommittedMB,
            int heapUsagePercent
    ) {}
}