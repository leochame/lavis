package com.lavis.service.tts;

import com.lavis.service.llm.LlmFactory;
import com.lavis.websocket.AgentWebSocketHandler;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async TTS Service
 *
 * Responsible for asynchronously generating TTS audio and pushing to frontend via WebSocket.
 * Supports sentence-by-sentence streaming for minimal first-byte latency.
 *
 * Design principles:
 * 1. Never block HTTP response - TTS generation is fully async
 * 2. Generate and push sentence by sentence
 * 3. On error, send tts_error message for graceful frontend degradation
 * 4. For code/long content, generate a brief summary instead of reading verbatim
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncTtsService {

    private final LlmFactory llmFactory;
    private final TextCleanerService textCleanerService;
    private final AgentWebSocketHandler webSocketHandler;

    // Threshold for content that needs summarization
    private static final int MAX_SPEAKABLE_LENGTH = 300;
    private static final double CODE_RATIO_THRESHOLD = 0.3;

    // Summary generation prompt
    private static final String SUMMARY_SYSTEM_PROMPT = """
        You are a voice assistant. Generate a brief spoken summary (under 50 characters) for the user.

        Rules:
        - Be concise and natural for speech
        - If it contains code, mention what was created and where (e.g., "Function created in utils.js")
        - If it's a long explanation, give the key point
        - If it's a task completion, confirm what was done
        - Use natural spoken language, not written style
        - Response in the same language as the original content
        - Output ONLY the summary text, no quotes or extra formatting
        """;

    /**
     * Async generate TTS and push to frontend
     *
     * @param sessionId WebSocket Session ID
     * @param agentText Agent's original response text
     * @param requestId Request ID (for frontend matching)
     */
    @Async
    public void generateAndPush(String sessionId, String agentText, String requestId) {
        log.info("[AsyncTTS] Starting async TTS generation for session: {}, requestId: {}",
            sessionId, requestId);

        try {
            // 1. Check if session is valid
            if (!webSocketHandler.isSessionActive(sessionId)) {
                log.warn("[AsyncTTS] Session not active, skipping TTS: {}", sessionId);
                return;
            }

            // 2. Determine what to speak: original text or summary
            String textToSpeak = determineTextToSpeak(agentText);

            if (textToSpeak == null || textToSpeak.isBlank()) {
                log.info("[AsyncTTS] Nothing to speak, sending tts_skip");
                sendTtsSkip(sessionId, requestId, "empty_content");
                return;
            }

            // 3. Clean the text
            String cleanedText = textCleanerService.clean(textToSpeak);
            if (cleanedText.isEmpty()) {
                log.info("[AsyncTTS] Cleaned text is empty, sending tts_skip");
                sendTtsSkip(sessionId, requestId, "empty_after_clean");
                return;
            }

            // 4. Split into sentences
            List<String> sentences = textCleanerService.splitToSentences(cleanedText);
            if (sentences.isEmpty()) {
                log.info("[AsyncTTS] No sentences to speak, sending tts_skip");
                sendTtsSkip(sessionId, requestId, "no_sentences");
                return;
            }

            log.info("[AsyncTTS] Processing {} sentences", sentences.size());

            // 5. Generate and push sentence by sentence
            AtomicInteger index = new AtomicInteger(0);
            int totalSentences = sentences.size();

            for (String sentence : sentences) {
                int currentIndex = index.getAndIncrement();
                boolean isLast = (currentIndex == totalSentences - 1);

                try {
                    // Generate audio
                    String audioBase64 = llmFactory.getTtsModel().textToSpeech(sentence);

                    // Push audio
                    boolean sent = sendTtsAudio(sessionId, requestId, audioBase64, currentIndex, isLast);
                    if (!sent) {
                        log.warn("[AsyncTTS] Failed to send audio, session may be closed");
                        break;
                    }

                    log.debug("[AsyncTTS] Sent sentence {}/{}: {}",
                        currentIndex + 1, totalSentences, sentence.substring(0, Math.min(30, sentence.length())));

                } catch (Exception e) {
                    log.error("[AsyncTTS] Failed to generate TTS for sentence {}: {}",
                        currentIndex, e.getMessage());
                    // Continue processing next sentence, don't break the whole flow
                }
            }

            log.info("[AsyncTTS] Completed TTS generation for requestId: {}", requestId);

        } catch (Exception e) {
            log.error("[AsyncTTS] TTS generation failed", e);
            sendTtsError(sessionId, requestId, e.getMessage());
        }
    }

    /**
     * Determine what text to speak based on content analysis
     *
     * @param agentText Original agent response
     * @return Text to speak (original, summary, or null)
     */
    private String determineTextToSpeak(String agentText) {
        if (agentText == null || agentText.isBlank()) {
            return null;
        }

        // Check if content is primarily code
        boolean isPrimarilyCode = textCleanerService.isPrimarilyCode(agentText);

        // Check content length after cleaning
        String cleanedPreview = textCleanerService.clean(agentText);
        boolean isTooLong = cleanedPreview.length() > MAX_SPEAKABLE_LENGTH;

        // If content is code-heavy or too long, generate a summary
        if (isPrimarilyCode || isTooLong) {
            log.info("[AsyncTTS] Content needs summary: isPrimarilyCode={}, length={}",
                isPrimarilyCode, cleanedPreview.length());
            return generateSummary(agentText);
        }

        // Otherwise, use the original text
        return agentText;
    }

    /**
     * Generate a brief spoken summary using LLM
     *
     * @param agentText Original agent response
     * @return Brief summary suitable for TTS
     */
    private String generateSummary(String agentText) {
        try {
            ChatLanguageModel model = llmFactory.getModel();

            // Truncate if too long to avoid token limits
            String truncated = agentText.length() > 2000
                ? agentText.substring(0, 2000) + "..."
                : agentText;

            // Build messages
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(SUMMARY_SYSTEM_PROMPT));
            messages.add(UserMessage.from("Please summarize this response:\n\n" + truncated));

            // Call LLM
            Response<AiMessage> response = model.generate(messages);
            String summary = response.content().text();

            log.info("[AsyncTTS] Generated summary: {}", summary);
            return summary != null ? summary.trim() : "Task completed.";

        } catch (Exception e) {
            log.error("[AsyncTTS] Failed to generate summary, using fallback", e);
            // Fallback: simple notification
            return "Task completed.";
        }
    }

    /**
     * Async check if user needs voice feedback (runs in parallel with LLM)
     *
     * @param userQuery User's question
     * @param decisionService TTS decision service
     * @return CompletableFuture<Boolean>
     */
    public CompletableFuture<Boolean> checkNeedsVoiceFeedbackAsync(String userQuery, TtsDecisionService decisionService) {
        return CompletableFuture.supplyAsync(() -> decisionService.needsVoiceFeedback(userQuery));
    }

    /**
     * @deprecated Use {@link #checkNeedsVoiceFeedbackAsync(String, TtsDecisionService)} instead
     */
    @Deprecated
    public CompletableFuture<Boolean> checkNeedsTtsAsync(String userQuery, TtsDecisionService decisionService) {
        return checkNeedsVoiceFeedbackAsync(userQuery, decisionService);
    }

    /**
     * Send TTS audio message
     */
    private boolean sendTtsAudio(String sessionId, String requestId, String audioBase64, int index, boolean isLast) {
        return webSocketHandler.sendToSessionById(sessionId, Map.of(
            "type", "tts_audio",
            "requestId", requestId,
            "data", audioBase64,
            "index", index,
            "isLast", isLast
        ));
    }

    /**
     * Send TTS skip message
     */
    private void sendTtsSkip(String sessionId, String requestId, String reason) {
        webSocketHandler.sendToSessionById(sessionId, Map.of(
            "type", "tts_skip",
            "requestId", requestId,
            "reason", reason
        ));
    }

    /**
     * Send TTS error message
     */
    private void sendTtsError(String sessionId, String requestId, String error) {
        webSocketHandler.sendToSessionById(sessionId, Map.of(
            "type", "tts_error",
            "requestId", requestId,
            "error", error != null ? error : "Unknown error"
        ));
    }
}
