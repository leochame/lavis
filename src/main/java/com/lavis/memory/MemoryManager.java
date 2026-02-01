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
    private final VisualCompactor visualCompactor;
    private final ColdStorage coldStorage;

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
        String turnId = TurnContext.currentTurnId();
        sessionStore.saveMessage(sessionKey, message, tokenCount, turnId, null);
    }

    /**
     * Save a message with image to current session
     *
     * @param message    Chat message
     * @param tokenCount Token count
     * @param imageId    Image identifier for tracking
     */
    public void saveMessageWithImage(ChatMessage message, int tokenCount, String imageId) {
        String sessionKey = getCurrentSessionKey();
        String turnId = TurnContext.currentTurnId();

        // Record image in TurnContext for anchor tracking
        TurnContext turn = TurnContext.current();
        if (turn != null && imageId != null) {
            turn.recordImage(imageId);
        }

        sessionStore.saveMessage(sessionKey, message, tokenCount, turnId, imageId);
    }

    /**
     * Handle Turn end event
     * Called when a user request completes, triggers compression of the completed turn
     *
     * @param turn The completed TurnContext
     */
    public void onTurnEnd(TurnContext turn) {
        if (turn == null) {
            log.warn("onTurnEnd called with null TurnContext");
            return;
        }

        log.info("Turn ended: {} with {} images, {} messages",
                turn.getTurnId(), turn.getImageCount(), turn.currentPosition());

        // Context Engineering: Trigger visual compression for previous turns
        try {
            VisualCompactor.CompactionResult result = visualCompactor.compactPreviousTurns(
                    turn.getSessionId(), turn.getTurnId());

            if (result.compactedCount() > 0) {
                log.info("Visual compaction completed: {} images compressed, ~{} tokens saved",
                        result.compactedCount(), result.estimatedTokensSaved());
            }
        } catch (Exception e) {
            log.error("Error during visual compaction", e);
        }

        // Log turn summary for debugging
        if (turn.getImageCount() > 0) {
            log.debug("Turn {} anchors: first={}, last={}",
                    turn.getTurnId(), turn.getFirstImageId(), turn.getLastImageId());
        }
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
     * Note: Image cleanup is now primarily handled by onTurnEnd (event-driven)
     * This scheduled task handles session cleanup and cold storage maintenance
     */
    @Scheduled(fixedRate = 3600000) // Every hour
    public void scheduledCleanup() {
        log.info("Starting scheduled memory cleanup...");

        try {
            // Clean up old sessions
            int deletedSessions = sessionStore.deleteOldSessions(DEFAULT_SESSION_RETENTION_DAYS);

            // Clean up cold storage (remove files older than retention period)
            int coldStorageCleaned = coldStorage.cleanup();
            if (coldStorageCleaned > 0) {
                log.info("Cleaned up {} files from cold storage", coldStorageCleaned);
            }

            // Log memory stats
            MemoryStats stats = getMemoryStats();
            log.info("Memory usage: Heap={} MB, Used={} MB ({}%)",
                    stats.heapMaxMB(), stats.heapUsedMB(), stats.heapUsagePercent());

            // Log cold storage stats
            ColdStorage.StorageStats coldStats = coldStorage.getStats();
            log.info("Cold storage: {} files, {} MB", coldStats.totalFiles(), coldStats.totalSizeMB());

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