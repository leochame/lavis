package com.lavis.memory;

import com.lavis.cognitive.memory.ImageContentCleanableChatMemory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ImageContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Image cleanup service
 *
 * Note: As of Context Engineering Phase 2, image cleanup is now primarily
 * event-driven via MemoryManager.onTurnEnd() and VisualCompactor.
 *
 * This service is retained for:
 * - In-memory cleanup of ImageContentCleanableChatMemory
 * - Legacy compatibility
 * - Manual cleanup triggers
 *
 * The @Scheduled annotation has been removed to prevent blind deletion
 * that could cause the model to "lose sight" of important images.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageCleanupService {

    private static final int DEFAULT_KEEP_LAST_N = 10;

    private final SessionStore sessionStore;

    /**
     * Clean up old images from in-memory chat history
     * This works with ImageContentCleanableChatMemory
     */
    public int cleanupInMemoryImages(ImageContentCleanableChatMemory chatMemory, int keepLastN) {
        List<ChatMessage> messages = chatMemory.messages();

        // Count UserMessages with images
        AtomicInteger imageCount = new AtomicInteger(0);
        AtomicInteger cleanedCount = new AtomicInteger(0);

        // Iterate from newest to oldest
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);

            if (message instanceof UserMessage userMsg) {
                boolean hasImage = userMsg.contents().stream()
                        .anyMatch(c -> c instanceof ImageContent);

                if (hasImage) {
                    imageCount.incrementAndGet();

                    // If beyond keepLastN, the ImageContentCleanableChatMemory
                    // should have already cleaned it up
                    if (imageCount.get() > keepLastN) {
                        cleanedCount.incrementAndGet();
                    }
                }
            }
        }

        if (cleanedCount.get() > 0) {
            log.info("In-memory cleanup: {} old images removed, {} recent images kept",
                    cleanedCount.get(), Math.min(imageCount.get(), keepLastN));
        }

        return cleanedCount.get();
    }

    /**
     * Clean up old images from a specific session
     *
     * @deprecated Use VisualCompactor for Turn-aware compression instead.
     *             This method is retained for legacy compatibility.
     */
    @Deprecated
    public int cleanupSessionImages(String sessionKey, int keepLastN) {
        try {
            int deletedCount = sessionStore.cleanupOldImages(sessionKey, keepLastN);
            if (deletedCount > 0) {
                log.info("Database cleanup: {} old image messages removed from session {}",
                        deletedCount, sessionKey);
            }
            return deletedCount;
        } catch (Exception e) {
            log.error("Error cleaning up images for session {}", sessionKey, e);
            return 0;
        }
    }

    /**
     * Get image statistics for a session
     */
    public ImageStats getImageStats(List<ChatMessage> messages) {
        int totalImages = 0;
        int userMessagesWithImages = 0;

        for (ChatMessage message : messages) {
            if (message instanceof UserMessage userMsg) {
                long imageCount = userMsg.contents().stream()
                        .filter(c -> c instanceof ImageContent)
                        .count();

                if (imageCount > 0) {
                    userMessagesWithImages++;
                    totalImages += imageCount;
                }
            }
        }

        return new ImageStats(totalImages, userMessagesWithImages);
    }

    /**
     * Image statistics record
     */
    public record ImageStats(
            int totalImages,
            int userMessagesWithImages
    ) {}
}
