package com.lavis.controller;

import com.lavis.cognitive.AgentService;
import com.lavis.cognitive.orchestrator.TaskOrchestrator;
import com.lavis.perception.ScreenCapturer;
import com.lavis.service.llm.LlmFactory;
import com.lavis.service.tts.AsyncTtsService;
import com.lavis.service.tts.TtsDecisionService;
import com.lavis.websocket.AgentWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Agent REST API Controller
 *
 * Core architecture:
 * 1. Fast System (/chat): Vision-based instant Q&A and single-step operations
 * 2. Slow System (/task): Plan-Execute based complex task orchestration
 * 3. System Control: Status, reset, stop, screenshot
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final ScreenCapturer screenCapturer;
    private final LlmFactory llmFactory;
    private final TtsDecisionService ttsDecisionService;
    private final AsyncTtsService asyncTtsService;
    private final AgentWebSocketHandler webSocketHandler;

    // Task history
    private final Deque<TaskRecord> taskHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY_SIZE = 50;

    // ==========================================
    // Core APIs
    // ==========================================

    /**
     * 1. Chat (Fast System)
     * For: Visual Q&A, single-step commands, lightweight interactions
     * Underlying: Text + Screenshot -> Agent -> Response
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message cannot be empty"));
        }

        log.info("💬 [Chat] Received message: {}", message);

        long startTime = System.currentTimeMillis();
        try {
            String response = agentService.chatWithScreenshot(message);
            long duration = System.currentTimeMillis() - startTime;

            addToHistory("chat", message, response, true, duration);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "response", response,
                    "duration_ms", duration
            ));
        } catch (Exception e) {
            return handleError("chat", message, startTime, e);
        }
    }

    /**
     * 2. Execute Task (Slow System)
     * For: Complex workflows, multi-step operations, tasks requiring self-correction
     * Underlying: TaskOrchestrator (Planner -> Executor -> Reflector)
     */
    @PostMapping("/task")
    public ResponseEntity<Map<String, Object>> executeTask(@RequestBody Map<String, String> request) {
        String goal = request.get("goal");
        // Support legacy parameter name "task"
        if (goal == null || goal.isBlank()) goal = request.get("task");

        if (goal == null || goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Task goal cannot be empty"));
        }

        log.info("🚀 [Task] Received task: {}", goal);

        long startTime = System.currentTimeMillis();
        try {
            TaskOrchestrator orchestrator = agentService.getTaskOrchestrator();
            TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal(goal);
            long duration = System.currentTimeMillis() - startTime;

            addToHistory("task", goal, result.getMessage(), result.isSuccess(), duration);

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());
            response.put("duration_ms", duration);

            // Attach execution details
            if (result.getPlan() != null) {
                response.put("plan_summary", result.getPlan().generateSummary());
                response.put("steps_total", result.getPlan().getSteps().size());
            }
            response.put("execution_summary", orchestrator.getExecutionSummary());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return handleError("task", goal, startTime, e);
        }
    }

    // ==========================================
    // System Control
    // ==========================================

    /**
     * Emergency stop
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        var orchestrator = agentService.getTaskOrchestrator();
        boolean interrupted = false;
        if (orchestrator != null) {
            orchestrator.interrupt();
            interrupted = true;
        }

        log.info("🛑 Emergency stop triggered by user");
        return ResponseEntity.ok(Map.of(
                "status", interrupted ? "Stop command sent" : "Orchestrator unavailable",
                "interrupted", interrupted));
    }

    /**
     * Global reset (memory, orchestrator, history)
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> reset() {
        // 1. Reset conversation memory
        agentService.resetConversation();

        // 2. Reset orchestrator state
        var orchestrator = agentService.getTaskOrchestrator();
        if (orchestrator != null) {
            orchestrator.reset();
        }

        log.info("🔄 System state fully reset");
        return ResponseEntity.ok(Map.of("status", "System reset"));
    }

    /**
     * Get full system status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();

        // Basic service status
        status.put("available", agentService.isAvailable());
        status.put("model", agentService.getModelInfo());

        // Orchestrator state
        var orchestrator = agentService.getTaskOrchestrator();
        if (orchestrator != null) {
            status.put("orchestrator_state", orchestrator.getState());
            if (orchestrator.getCurrentPlan() != null) {
                var plan = orchestrator.getCurrentPlan();
                status.put("current_plan_progress", plan.getProgressPercent());
                status.put("current_plan", plan);
            }
        }

        return ResponseEntity.ok(status);
    }

    // ==========================================
    // Utilities
    // ==========================================

    /**
     * Screen capture (for debugging)
     */
    @GetMapping("/screenshot")
    public ResponseEntity<Map<String, Object>> getScreenshot() {
        try {
            String base64 = screenCapturer.captureScreenAsBase64();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "image", base64,
                    "size", screenCapturer.getScreenSize()
            ));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/history")
    public ResponseEntity<List<TaskRecord>> getHistory() {
        return ResponseEntity.ok(new ArrayList<>(taskHistory));
    }

    @DeleteMapping("/history")
    public ResponseEntity<Void> clearHistory() {
        taskHistory.clear();
        return ResponseEntity.ok().build();
    }

    // ==========================================
    // Voice Chat (语音对话)
    // ==========================================

    /**
     * 语音对话接口 (Voice Chat) - 异步 TTS 版本
     *
     * 流程优化：
     * 1. STT 完成后，立即并行启动：
     *    - LLM 生成回复
     *    - TTS 决策判断（基于用户问题）
     * 2. LLM 完成后立即返回 HTTP 响应（文字先行）
     * 3. 异步：根据决策结果 + 回复内容，生成 TTS 并通过 WebSocket 推送
     *
     * 请求格式：multipart/form-data
     * @param audioFile 用户录音文件 (WAV/MP3/M4A)
     * @param wsSessionId WebSocket Session ID（用于推送 TTS 音频）
     *
     * 响应格式：
     * {
     *   "success": true,
     *   "user_text": "用户说的文本",
     *   "agent_text": "Agent 的回复文本",
     *   "request_id": "唯一请求ID",
     *   "audio_pending": true  // 告知前端音频稍后通过 WS 推送
     * }
     */
    @PostMapping(value = "/voice-chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> voiceChat(
            @RequestParam("file") MultipartFile audioFile,
            @RequestParam(value = "ws_session_id", required = false) String wsSessionId,
            @RequestParam(value = "screenshot", required = false) MultipartFile screenshot
    ) {
        if (audioFile == null || audioFile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Audio file is required"));
        }

        log.info("🎤 [Voice Chat] Received audio file: {}", audioFile.getOriginalFilename());

        // 生成唯一请求 ID
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        try {
            // 1. STT: 音频 → 文本
            String userText = llmFactory.getSttModel().transcribe(audioFile);
            log.info("User transcribed text: {}", userText);

            // 2. Check if user needs voice feedback (runs in parallel with LLM)
            // This is based on user intent, not LLM response content
            CompletableFuture<Boolean> needsVoiceFeedbackFuture = asyncTtsService.checkNeedsVoiceFeedbackAsync(
                userText, ttsDecisionService
            );

            // 3. LLM generates response
            String agentText = agentService.chatWithScreenshot(userText);
            log.info("Agent response: {}", agentText);

            // 4. Get voice feedback decision (should be done by now, as it's fast)
            boolean needsVoiceFeedback = needsVoiceFeedbackFuture.join();
            log.info("Voice feedback decision: needsVoiceFeedback={}", needsVoiceFeedback);

            // 5. Determine WebSocket Session ID
            String sessionId = wsSessionId;
            if (sessionId == null || sessionId.isBlank()) {
                // If frontend didn't pass it, try to get the first available session
                sessionId = webSocketHandler.getFirstSessionId();
            }

            // 6. If user needs voice feedback and has valid WebSocket connection, generate TTS async
            // AsyncTtsService will determine whether to speak original text or generate a summary
            boolean audioPending = false;
            if (needsVoiceFeedback && sessionId != null && webSocketHandler.isSessionActive(sessionId)) {
                asyncTtsService.generateAndPush(sessionId, agentText, requestId);
                audioPending = true;
                log.info("TTS generation started asynchronously for requestId: {}", requestId);
            } else if (needsVoiceFeedback) {
                log.warn("Voice feedback needed but no active WebSocket session, skipping TTS");
            }

            long duration = System.currentTimeMillis() - startTime;
            addToHistory("voice-chat", userText, agentText, true, duration);

            // 7. Return text response immediately
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("user_text", userText);
            response.put("agent_text", agentText);
            response.put("request_id", requestId);
            response.put("audio_pending", audioPending);
            response.put("duration_ms", duration);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Voice chat failed", e);
            return handleError("voice-chat", audioFile.getOriginalFilename(), startTime, e);
        }
    }

    // ==========================================
    // TTS API (Text-to-Speech Proxy)
    // ==========================================

    /**
     * TTS 代理端点
     * 前端调用此端点将文本转换为音频，配置统一在后端管理
     * 
     * 请求格式：
     * {
     *   "text": "要转换的文本"
     * }
     * 
     * 响应格式：
     * {
     *   "success": true,
     *   "audio": "Base64 编码的音频数据",
     *   "format": "mp3"
     * }
     */
    @PostMapping("/tts")
    public ResponseEntity<Map<String, Object>> textToSpeech(@RequestBody Map<String, String> request) {
        String text = request.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Text cannot be empty"));
        }

        log.info("🎙️ [TTS] Received text to convert: {} chars", text.length());

        long startTime = System.currentTimeMillis();
        try {
            // 使用后端配置的 TTS 模型生成音频
            String audioBase64 = llmFactory.getTtsModel().textToSpeech(text);
            long duration = System.currentTimeMillis() - startTime;

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "audio", audioBase64,
                    "format", "mp3",
                    "duration_ms", duration
            ));
        } catch (Exception e) {
            log.error("TTS generation failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage(),
                    "success", false
            ));
        }
    }

    // ==========================================
    // Private helper methods
    // ==========================================

    private ResponseEntity<Map<String, Object>> handleError(String type, String input, long startTime, Exception e) {
        log.error("{} execution failed", type, e);
        addToHistory(type, input, e.getMessage(), false, System.currentTimeMillis() - startTime);
        return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
    }

    private void addToHistory(String type, String input, String output, boolean success, long durationMs) {
        TaskRecord record = new TaskRecord(
                UUID.randomUUID().toString(),
                type,
                input,
                output,
                success,
                durationMs,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
        taskHistory.addFirst(record);
        if (taskHistory.size() > MAX_HISTORY_SIZE) {
            taskHistory.removeLast();
        }
    }

    public record TaskRecord(
            String id,
            String type,
            String input,
            String output,
            boolean success,
            long durationMs,
            String timestamp
    ) {}
}
