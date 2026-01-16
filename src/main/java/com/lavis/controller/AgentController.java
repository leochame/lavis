package com.lavis.controller;

import com.lavis.cognitive.AgentService;
import com.lavis.cognitive.orchestrator.TaskOrchestrator;
import com.lavis.perception.ScreenCapturer;
import com.lavis.ui.JavaFXInitializer;
import com.lavis.ui.OverlayWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Agent REST API 控制器 (精简版)
 * * 核心架构：
 * 1. 快系统 (/chat): 基于视觉的即时问答与单步操作
 * 2. 慢系统 (/task): 基于 Plan-Execute 的复杂任务编排
 * 3. 系统控制: 状态、重置、停止、截图
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final ScreenCapturer screenCapturer;
    private final JavaFXInitializer javaFXInitializer;

    // 任务历史记录
    private final Deque<TaskRecord> taskHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY_SIZE = 50;

    // ==========================================
    // 核心接口 (Core APIs)
    // ==========================================

    /**
     * 1. 智能对话 (快系统)
     * 适用于：视觉问答、单步指令、轻量级交互
     * 底层：Text + Screenshot -> Agent -> Response
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "消息不能为空"));
        }

        log.info("💬 [Chat] 收到消息: {}", message);

        javaFXInitializer.updateState(OverlayWindow.AgentState.THINKING);
        javaFXInitializer.setThinkingText("分析屏幕...");
        javaFXInitializer.addLog("👤 " + message);

        long startTime = System.currentTimeMillis();
        try {
            // 默认总是带截图，提供最强的感知能力
            String response = agentService.chatWithScreenshot(message);
            long duration = System.currentTimeMillis() - startTime;

            javaFXInitializer.updateState(OverlayWindow.AgentState.IDLE);
            javaFXInitializer.setThinkingText("");
            javaFXInitializer.addLog("🤖 " + truncate(response, 100));

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
     * 2. 自动化任务 (慢系统)
     * 适用于：复杂流程、多步操作、需要自我修正的任务
     * 底层：TaskOrchestrator (Planner -> Executor -> Reflector)
     */
    @PostMapping("/task")
    public ResponseEntity<Map<String, Object>> executeTask(@RequestBody Map<String, String> request) {
        String goal = request.get("goal");
        // 兼容旧参数名 "task"
        if (goal == null || goal.isBlank()) goal = request.get("task");

        if (goal == null || goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "任务目标不能为空"));
        }

        log.info("🚀 [Task] 收到任务: {}", goal);

        javaFXInitializer.updateState(OverlayWindow.AgentState.EXECUTING);
        javaFXInitializer.setThinkingText("规划任务中...");
        javaFXInitializer.addLog("🎯 目标: " + goal);

        long startTime = System.currentTimeMillis();
        try {
            // 统一使用 TaskOrchestrator
            TaskOrchestrator orchestrator = agentService.getTaskOrchestrator();
            TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal(goal);
            long duration = System.currentTimeMillis() - startTime;

            javaFXInitializer.updateState(result.isSuccess() ?
                    OverlayWindow.AgentState.SUCCESS : OverlayWindow.AgentState.ERROR);
            javaFXInitializer.setThinkingText("");

            addToHistory("task", goal, result.getMessage(), result.isSuccess(), duration);

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());
            response.put("duration_ms", duration);

            // 附加执行细节
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
    // 系统控制 (System Control)
    // ==========================================

    /**
     * 紧急停止
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stop() {
        // 停止编排器
        var orchestrator = agentService.getTaskOrchestrator();
        if (orchestrator != null) {
            // TODO: 需要在 Orchestrator 中实现 interrupt() 方法
            // orchestrator.interrupt();
        }

        // 视觉状态重置
        javaFXInitializer.updateState(OverlayWindow.AgentState.IDLE);
        javaFXInitializer.setThinkingText("");
        javaFXInitializer.addLog("🛑 用户触发紧急停止");

        return ResponseEntity.ok(Map.of("status", "已发送停止指令"));
    }

    /**
     * 全局重置 (记忆、编排器、历史)
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> reset() {
        // 1. 重置对话记忆
        agentService.resetConversation();

        // 2. 重置编排器状态
        var orchestrator = agentService.getTaskOrchestrator();
        if (orchestrator != null) {
            orchestrator.reset();
        }

        javaFXInitializer.addLog("🔄 系统状态已完全重置");
        return ResponseEntity.ok(Map.of("status", "系统已重置"));
    }

    /**
     * 获取系统全状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();

        // 基础服务状态
        status.put("available", agentService.isAvailable());
        status.put("model", agentService.getModelInfo());
        status.put("ui_active", javaFXInitializer.isInitialized());

        // 编排器状态
        var orchestrator = agentService.getTaskOrchestrator();
        if (orchestrator != null) {
            status.put("orchestrator_state", orchestrator.getState());
            if (orchestrator.getCurrentPlan() != null) {
                status.put("current_plan_progress", orchestrator.getCurrentPlan().getProgressPercent());
            }
        }

        return ResponseEntity.ok(status);
    }

    // ==========================================
    // 辅助工具 (Utilities)
    // ==========================================

    /**
     * 屏幕截图 (调试用)
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

    @PostMapping("/ui/show")
    public void showUI() { javaFXInitializer.showOverlay(); }

    @PostMapping("/ui/hide")
    public void hideUI() { javaFXInitializer.hideOverlay(); }

    // ==========================================
    // 私有辅助方法
    // ==========================================

    private ResponseEntity<Map<String, Object>> handleError(String type, String input, long startTime, Exception e) {
        log.error("{} 执行失败", type, e);
        javaFXInitializer.updateState(OverlayWindow.AgentState.ERROR);
        javaFXInitializer.setThinkingText("");
        javaFXInitializer.addLog("❌ 错误: " + e.getMessage());

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

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
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