package com.lavis.controller;

import com.lavis.cognitive.AgentService;
import com.lavis.cognitive.orchestrator.TaskOrchestrator;
import com.lavis.perception.ScreenCapturer;
import com.lavis.service.llm.LlmFactory;
import com.lavis.service.voice.VoiceChatService;
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
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Agent REST API Controller
 *
 * Architecture:
 * - Fast System (/chat): Vision-based instant Q&A
 * - Slow System (/task, /voice-chat): Plan-Execute orchestration
 * - System Control: status, reset, stop, screenshot
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final ScreenCapturer screenCapturer;
    private final LlmFactory llmFactory;
    private final VoiceChatService voiceChatService;

    private final Deque<TaskRecord> taskHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY_SIZE = 50;

    // ==================== Core APIs ====================

    /**
     * Chat (Fast System) - Visual Q&A, single-step commands
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message cannot be empty"));
        }

        log.info("[Chat] message: {}", message);
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
     * Execute Task (Slow System) - Complex workflows via TaskOrchestrator
     */
    @PostMapping("/task")
    public ResponseEntity<Map<String, Object>> executeTask(@RequestBody Map<String, String> request) {
        String goal = request.get("goal");
        if (goal == null || goal.isBlank()) goal = request.get("task");
        if (goal == null || goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Task goal cannot be empty"));
        }

        log.info("[Task] goal: {}", goal);
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
            response.put("execution_summary", orchestrator.getExecutionSummary());

            if (result.getPlan() != null) {
                response.put("plan_summary", result.getPlan().generateSummary());
                response.put("steps_total", result.getPlan().getSteps().size());
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return handleError("task", goal, startTime, e);
        }
    }

    /**
     * Voice Chat - Audio input with async TTS response via WebSocket
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

        log.info("[Voice Chat] file: {}", audioFile.getOriginalFilename());
        long startTime = System.currentTimeMillis();

        try {
            VoiceChatService.VoiceChatResult result = voiceChatService.process(audioFile, wsSessionId);
            addToHistory("voice-chat", result.userText(), result.agentText(), result.success(), result.durationMs());
            return ResponseEntity.ok(result.toResponseMap());
        } catch (Exception e) {
            return handleError("voice-chat", audioFile.getOriginalFilename(), startTime, e);
        }
    }

    // ==================== System Control ====================

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        var orchestrator = agentService.getTaskOrchestrator();
        boolean interrupted = orchestrator != null;
        if (interrupted) orchestrator.interrupt();

        log.info("[Stop] Emergency stop triggered");
        return ResponseEntity.ok(Map.of(
            "status", interrupted ? "Stop command sent" : "Orchestrator unavailable",
            "interrupted", interrupted
        ));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> reset() {
        agentService.resetConversation();
        var orchestrator = agentService.getTaskOrchestrator();
        if (orchestrator != null) orchestrator.reset();

        log.info("[Reset] System state reset");
        return ResponseEntity.ok(Map.of("status", "System reset"));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("available", agentService.isAvailable());
        status.put("model", agentService.getModelInfo());

        var orchestrator = agentService.getTaskOrchestrator();
        if (orchestrator != null) {
            status.put("orchestrator_state", orchestrator.getState());
            var plan = orchestrator.getCurrentPlan();
            if (plan != null) {
                status.put("current_plan_progress", plan.getProgressPercent());
                status.put("current_plan", plan);
            }
        }

        return ResponseEntity.ok(status);
    }

    // ==================== Utilities ====================

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

    @PostMapping("/tts")
    public ResponseEntity<Map<String, Object>> textToSpeech(@RequestBody Map<String, String> request) {
        String text = request.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Text cannot be empty"));
        }

        log.info("[TTS] {} chars", text.length());
        long startTime = System.currentTimeMillis();

        try {
            String audioBase64 = llmFactory.getTtsModel().textToSpeech(text);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "audio", audioBase64,
                "format", "mp3",
                "duration_ms", System.currentTimeMillis() - startTime
            ));
        } catch (Exception e) {
            log.error("TTS failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage(), "success", false));
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

    // ==================== Private Helpers ====================

    private ResponseEntity<Map<String, Object>> handleError(String type, String input, long startTime, Exception e) {
        log.error("{} failed", type, e);
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
