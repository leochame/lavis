package com.lavis.service.voice;

import com.lavis.cognitive.AgentService;
import com.lavis.cognitive.orchestrator.TaskOrchestrator;
import com.lavis.service.llm.LlmFactory;
import com.lavis.service.tts.AsyncTtsService;
import com.lavis.service.tts.TtsDecisionService;
import com.lavis.websocket.AgentWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Voice Chat Service
 *
 * Handles voice chat workflow:
 * 1. STT transcription
 * 2. TaskOrchestrator execution
 * 3. Async TTS generation and WebSocket push
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceChatService {

    private final AgentService agentService;
    private final LlmFactory llmFactory;
    private final TtsDecisionService ttsDecisionService;
    private final AsyncTtsService asyncTtsService;
    private final AgentWebSocketHandler webSocketHandler;

    /**
     * Process voice chat request
     *
     * @param audioFile User's audio file
     * @param wsSessionId WebSocket session ID (optional)
     * @return VoiceChatResult containing response data
     */
    public VoiceChatResult process(MultipartFile audioFile, String wsSessionId) throws Exception {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        // 1. STT: Audio -> Text
        long sttStartTime = System.currentTimeMillis();
        String userText = llmFactory.getSttModel().transcribe(audioFile);
        long sttDuration = System.currentTimeMillis() - sttStartTime;
        log.info("✅ STT completed in {}ms ({}s) - Audio: {} bytes, Transcribed: {} chars",
                sttDuration, String.format("%.2f", sttDuration / 1000.0),
                audioFile.getSize(), userText.length());
        log.info("User transcribed: {}", userText);

        // 2. Parallel: Check voice feedback need
        CompletableFuture<Boolean> voiceFeedbackFuture = asyncTtsService.checkNeedsVoiceFeedbackAsync(
            userText, ttsDecisionService
        );

        // 3. Execute via TaskOrchestrator
        TaskOrchestrator orchestrator = agentService.getTaskOrchestrator();
        TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal(userText);
        String agentText = result.getMessage();
        log.info("Agent response: {}", agentText);

        // 4. Get voice feedback decision
        boolean needsVoiceFeedback = voiceFeedbackFuture.join();

        // 5. Resolve WebSocket session
        String sessionId = resolveSessionId(wsSessionId);

        // 6. Async TTS push
        boolean audioPending = false;
        if (needsVoiceFeedback && sessionId != null && webSocketHandler.isSessionActive(sessionId)) {
            asyncTtsService.generateAndPush(sessionId, agentText, requestId);
            audioPending = true;
            log.info("TTS started for requestId: {}", requestId);
        } else if (needsVoiceFeedback) {
            log.warn("Voice feedback needed but no active WebSocket session");
        }

        long duration = System.currentTimeMillis() - startTime;

        return new VoiceChatResult(
            result.isSuccess(),
            userText,
            agentText,
            requestId,
            audioPending,
            duration,
            orchestrator.getState().name()
        );
    }

    private String resolveSessionId(String wsSessionId) {
        if (wsSessionId != null && !wsSessionId.isBlank()) {
            return wsSessionId;
        }
        return webSocketHandler.getFirstSessionId();
    }

    /**
     * Voice chat result record
     */
    public record VoiceChatResult(
        boolean success,
        String userText,
        String agentText,
        String requestId,
        boolean audioPending,
        long durationMs,
        String orchestratorState
    ) {
        public Map<String, Object> toResponseMap() {
            Map<String, Object> response = new HashMap<>();
            response.put("success", success);
            response.put("user_text", userText);
            response.put("agent_text", agentText);
            response.put("request_id", requestId);
            response.put("audio_pending", audioPending);
            response.put("duration_ms", durationMs);
            response.put("orchestrator_state", orchestratorState);
            return response;
        }
    }
}
