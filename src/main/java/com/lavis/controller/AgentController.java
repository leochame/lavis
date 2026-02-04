package com.lavis.controller;

import com.lavis.cognitive.AgentService;
import com.lavis.service.chat.ChatRequest;
import com.lavis.service.chat.ChatResponse;
import com.lavis.service.chat.UnifiedChatService;
import com.lavis.service.llm.LlmFactory;
import com.lavis.service.voice.VoiceChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Agent REST API Controller
 *
 * Architecture:
 * - Unified Chat System: 统一处理文本和音频输入
 *   - /chat: 文本输入（默认快速路径，支持 use_orchestrator 参数切换）
 *   - /voice-chat: 音频输入（默认复杂路径，支持 use_orchestrator 参数切换）
 * - System Control: status, stop
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final LlmFactory llmFactory;
    private final VoiceChatService voiceChatService; // 保留用于向后兼容
    private final UnifiedChatService unifiedChatService;

    private final Deque<TaskRecord> taskHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY_SIZE = 50;

    // ==================== Core APIs ====================

    /**
     * Chat - 文本输入的统一处理接口
     * 
     * 支持参数：
     * - message: 用户消息（必需）
     * - use_orchestrator: 是否使用 TaskOrchestrator（可选，默认 false，使用快速路径）
     * - needs_tts: 是否需要 TTS 语音反馈（可选，默认 false）
     * - ws_session_id: WebSocket session ID（可选，用于 TTS 推送）
     * 
     * 默认行为：使用快速路径（chatWithScreenshot），适合简单问答和单步命令
     * 设置 use_orchestrator=true 可切换到复杂任务路径（TaskOrchestrator）
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        String message = (String) request.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message cannot be empty"));
        }

        // 解析可选参数
        Boolean useOrchestrator = parseBoolean(request.get("use_orchestrator"));
        Boolean needsTts = parseBoolean(request.get("needs_tts"));
        String wsSessionId = (String) request.get("ws_session_id");

        log.info("[Chat] message: {}, use_orchestrator: {}, needs_tts: {}", 
                message, useOrchestrator, needsTts);
        long startTime = System.currentTimeMillis();

        try {
            // 使用统一服务处理
            ChatRequest chatRequest = unifiedChatService.normalizeTextInput(
                message, wsSessionId, useOrchestrator, needsTts
            );
            ChatResponse response = unifiedChatService.process(chatRequest);
            
            addToHistory("chat", message, response.agentText(), response.success(), response.durationMs());

            // 返回统一格式（包含向后兼容字段）
            return ResponseEntity.ok(response.toResponseMap(true));
        } catch (Exception e) {
            return handleError("chat", message, startTime, e);
        }
    }

    /**
     * 解析布尔值参数（支持多种格式）
     */
    private Boolean parseBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return null;
    }


    /**
     * Voice Chat - 音频输入的统一处理接口
     * 
     * 支持参数：
     * - file: 音频文件（必需）
     * - use_orchestrator: 是否使用 TaskOrchestrator（可选，默认 true，使用复杂路径）
     * - ws_session_id: WebSocket session ID（可选，用于 TTS 推送）
     * - screenshot: 截图文件（可选，暂未使用）
     * 
     * 默认行为：使用复杂路径（TaskOrchestrator），适合复杂任务和需要规划的场景
     * 设置 use_orchestrator=false 可切换到快速路径（chatWithScreenshot），适合简单问答
     */
    @PostMapping(value = "/voice-chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> voiceChat(
            @RequestParam("file") MultipartFile audioFile,
            @RequestParam(value = "ws_session_id", required = false) String wsSessionId,
            @RequestParam(value = "use_orchestrator", required = false) Boolean useOrchestrator,
            @RequestParam(value = "screenshot", required = false) MultipartFile screenshot
    ) {
        if (audioFile == null || audioFile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Audio file is required"));
        }

        log.info("[Voice Chat] file: {}, use_orchestrator: {}", 
                audioFile.getOriginalFilename(), useOrchestrator);
        long startTime = System.currentTimeMillis();

        try {
            // 使用统一服务处理
            ChatRequest chatRequest = unifiedChatService.normalizeAudioInput(
                audioFile, wsSessionId, useOrchestrator
            );
            ChatResponse response = unifiedChatService.process(chatRequest);
            
            addToHistory("voice-chat", response.userText(), response.agentText(), 
                    response.success(), response.durationMs());
            
            // 返回统一格式（不包含向后兼容字段，保持原有格式）
            return ResponseEntity.ok(response.toResponseMap(false));
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


    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("available", agentService.isAvailable());
        status.put("model", agentService.getModelInfo());

        var orchestrator = agentService.getTaskOrchestrator();
        if (orchestrator != null) {
            status.put("orchestrator_state", orchestrator.getState());
        }

        return ResponseEntity.ok(status);
    }

    // ==================== Utilities ====================


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
