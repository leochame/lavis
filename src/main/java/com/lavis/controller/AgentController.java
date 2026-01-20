package com.lavis.controller;

import com.lavis.cognitive.AgentService;
import com.lavis.cognitive.orchestrator.TaskOrchestrator;
import com.lavis.perception.ScreenCapturer;
import com.lavis.service.llm.LlmFactory;
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
    public ResponseEntity<Map<String, String>> stop() {
        var orchestrator = agentService.getTaskOrchestrator();
        if (orchestrator != null) {
            // TODO: Implement interrupt() method in Orchestrator
            // orchestrator.interrupt();
        }

        log.info("🛑 Emergency stop triggered by user");
        return ResponseEntity.ok(Map.of("status", "Stop command sent"));
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
     * 语音对话接口 (Voice Chat)
     * 
     * 流程：前端录音 → 后端 STT → Agent 处理 → 后端 TTS → 前端播放
     * 
     * 请求格式：multipart/form-data
     * @param audioFile 用户录音文件 (WAV/MP3/M4A)
     * @param screenshot (可选) 当前屏幕截图（如果语音包含视觉指令）
     * 
     * 响应格式：
     * {
     *   "success": true,
     *   "user_text": "用户说的文本",
     *   "agent_text": "Agent 的回复文本",
     *   "agent_audio": "Base64 编码的 MP3 音频"
     * }
     */
    @PostMapping(value = "/voice-chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> voiceChat(
            @RequestParam("file") MultipartFile audioFile,
            @RequestParam(value = "screenshot", required = false) MultipartFile screenshot
    ) {
        if (audioFile == null || audioFile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Audio file is required"));
        }

        log.info("🎤 [Voice Chat] Received audio file: {}", audioFile.getOriginalFilename());

        long startTime = System.currentTimeMillis();

        try {
            // 1. STT: 音频 → 文本
            String userText = llmFactory.getSttModel().transcribe(audioFile);
            log.info("User transcribed text: {}", userText);

            // 2. Agent: 文本 → 回复（可以带截图）
            String agentText;
            if (screenshot != null && !screenshot.isEmpty()) {
                // 如果提供了截图，将截图转为 Base64 传递给 Agent
                // 注意：这里简化处理，实际可能需要先保存截图文件
                agentText = agentService.chatWithScreenshot(userText);
            } else {
                agentText = agentService.chatWithScreenshot(userText);
            }

            log.info("Agent response: {}", agentText);

            // 3. TTS: 文本 → 音频
            String agentAudio = llmFactory.getTtsModel().textToSpeech(agentText);

            long duration = System.currentTimeMillis() - startTime;

            addToHistory("voice-chat", userText, agentText, true, duration);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "user_text", userText,
                    "agent_text", agentText,
                    "agent_audio", agentAudio,
                    "duration_ms", duration
            ));

        } catch (Exception e) {
            log.error("Voice chat failed", e);
            return handleError("voice-chat", audioFile.getOriginalFilename(), startTime, e);
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
