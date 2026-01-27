package com.lavis.memory;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Context compactor service
 * Compresses conversation history when it exceeds token limits
 * Uses AI to summarize older messages while preserving recent context
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextCompactor {

    private static final int DEFAULT_TOKEN_THRESHOLD = 100_000;
    private static final int KEEP_RECENT_MESSAGES = 10;

    private final ChatLanguageModel chatModel;

    /**
     * Check if conversation history needs compression
     */
    public boolean needsCompression(List<ChatMessage> messages, int tokenThreshold) {
        int estimatedTokens = estimateTokenCount(messages);
        boolean needs = estimatedTokens > tokenThreshold;

        if (needs) {
            log.info("Conversation history needs compression: {} tokens > {} threshold",
                    estimatedTokens, tokenThreshold);
        }

        return needs;
    }

    /**
     * Compress conversation history by summarizing older messages
     * Keeps recent messages intact for context continuity
     */
    public List<ChatMessage> compressHistory(List<ChatMessage> messages, int keepRecentN) {
        if (messages.size() <= keepRecentN) {
            log.debug("No compression needed: only {} messages", messages.size());
            return new ArrayList<>(messages);
        }

        // Split into old (to summarize) and recent (to keep)
        int splitIndex = messages.size() - keepRecentN;
        List<ChatMessage> oldMessages = messages.subList(0, splitIndex);
        List<ChatMessage> recentMessages = messages.subList(splitIndex, messages.size());

        log.info("Compressing {} old messages, keeping {} recent messages",
                oldMessages.size(), recentMessages.size());

        // Generate summary of old messages
        String summary = summarizeMessages(oldMessages);

        // Create compressed history
        List<ChatMessage> compressedHistory = new ArrayList<>();

        // Add summary as system message
        compressedHistory.add(SystemMessage.from(
                "Previous conversation summary:\n" + summary));

        // Add recent messages
        compressedHistory.addAll(recentMessages);

        int oldTokens = estimateTokenCount(oldMessages);
        int newTokens = estimateTokenCount(compressedHistory);
        log.info("Compression complete: {} tokens -> {} tokens ({}% reduction)",
                oldTokens, newTokens, (100 * (oldTokens - newTokens) / oldTokens));

        return compressedHistory;
    }

    /**
     * Summarize a list of messages using AI
     */
    private String summarizeMessages(List<ChatMessage> messages) {
        // Extract text content from messages
        String conversationText = messages.stream()
                .map(this::extractMessageText)
                .filter(text -> !text.isEmpty())
                .collect(Collectors.joining("\n\n"));

        // Create summarization prompt
        PromptTemplate template = PromptTemplate.from(
                """
                Please provide a concise summary of the following conversation history.
                Focus on:
                - Key tasks and actions performed
                - Important decisions made
                - Current state and context
                - Any unresolved issues or pending tasks

                Keep the summary under 500 words.

                Conversation:
                {{conversation}}

                Summary:
                """
        );

        Map<String, Object> variables = new HashMap<>();
        variables.put("conversation", conversationText);

        Prompt prompt = template.apply(variables);

        try {
            String summary = chatModel.generate(prompt.text());
            log.debug("Generated summary: {} characters", summary.length());
            return summary;
        } catch (Exception e) {
            log.error("Error generating summary", e);
            return "Error generating summary: " + e.getMessage();
        }
    }

    /**
     * Extract text content from a message
     */
    private String extractMessageText(ChatMessage message) {
        if (message instanceof UserMessage userMsg) {
            if (userMsg.hasSingleText()) {
                return "User: " + userMsg.singleText();
            } else {
                // Extract non-image content
                String text = userMsg.contents().stream()
                        .filter(c -> !(c instanceof ImageContent))
                        .map(Content::toString)
                        .collect(Collectors.joining(" "));
                return text.isEmpty() ? "" : "User: " + text;
            }
        } else if (message instanceof AiMessage aiMsg) {
            return "Assistant: " + aiMsg.text();
        } else if (message instanceof SystemMessage sysMsg) {
            return "System: " + sysMsg.text();
        } else if (message instanceof ToolExecutionResultMessage toolMsg) {
            return "Tool Result: " + toolMsg.text();
        }
        return "";
    }

    /**
     * Estimate token count for messages
     * Uses rough approximation: 1 token ≈ 4 characters
     */
    private int estimateTokenCount(List<ChatMessage> messages) {
        int totalChars = messages.stream()
                .mapToInt(msg -> extractMessageText(msg).length())
                .sum();

        // Rough estimate: 1 token ≈ 4 characters
        return totalChars / 4;
    }

    /**
     * Get compression statistics
     */
    public CompressionStats getCompressionStats(List<ChatMessage> messages) {
        int totalMessages = messages.size();
        int estimatedTokens = estimateTokenCount(messages);
        boolean needsCompression = estimatedTokens > DEFAULT_TOKEN_THRESHOLD;

        return new CompressionStats(
                totalMessages,
                estimatedTokens,
                DEFAULT_TOKEN_THRESHOLD,
                needsCompression
        );
    }

    /**
     * Compression statistics record
     */
    public record CompressionStats(
            int totalMessages,
            int estimatedTokens,
            int tokenThreshold,
            boolean needsCompression
    ) {}
}
