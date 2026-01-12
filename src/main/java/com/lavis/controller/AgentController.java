package com.lavis.controller;

import com.lavis.cognitive.AgentService;
import com.lavis.cognitive.ReflectionLoop;
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
 * Agent REST API 控制器
 * 提供 HTTP 接口与 Agent 交互
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final ReflectionLoop reflectionLoop;
    private final ScreenCapturer screenCapturer;
    private final JavaFXInitializer javaFXInitializer;
    
    // 任务历史记录 (最多保留 50 条)
    private final Deque<TaskRecord> taskHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY_SIZE = 50;

    /**
     * 发送消息给 Agent
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "消息不能为空"));
        }

        log.info("📝 收到聊天请求: {}", message);
        
        javaFXInitializer.updateState(OverlayWindow.AgentState.THINKING);
        javaFXInitializer.addLog("👤 " + message);

        long startTime = System.currentTimeMillis();
        try {
            String response = agentService.chat(message);
            long duration = System.currentTimeMillis() - startTime;
            
            javaFXInitializer.updateState(OverlayWindow.AgentState.IDLE);
            javaFXInitializer.addLog("🤖 " + truncate(response, 100));
            
            // 记录历史
            addToHistory("chat", message, response, true, duration);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("response", response);
            result.put("duration_ms", duration);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("处理消息失败", e);
            javaFXInitializer.updateState(OverlayWindow.AgentState.ERROR);
            javaFXInitializer.addLog("❌ 错误: " + e.getMessage());
            addToHistory("chat", message, e.getMessage(), false, System.currentTimeMillis() - startTime);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 发送带截图的消息给 Agent
     */
    @PostMapping("/chat-with-screenshot")
    public ResponseEntity<Map<String, Object>> chatWithScreenshot(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "消息不能为空"));
        }

        log.info("📷 收到带截图的聊天请求: {}", message);
        
        javaFXInitializer.updateState(OverlayWindow.AgentState.THINKING);
        javaFXInitializer.setThinkingText("分析屏幕...");
        javaFXInitializer.addLog("👤 " + message);

        long startTime = System.currentTimeMillis();
        try {
            String response = agentService.chatWithScreenshot(message);
            long duration = System.currentTimeMillis() - startTime;
            
            javaFXInitializer.updateState(OverlayWindow.AgentState.IDLE);
            javaFXInitializer.setThinkingText("");
            javaFXInitializer.addLog("🤖 " + truncate(response, 100));
            
            addToHistory("vision", message, response, true, duration);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("response", response);
            result.put("duration_ms", duration);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("处理消息失败", e);
            javaFXInitializer.updateState(OverlayWindow.AgentState.ERROR);
            javaFXInitializer.setThinkingText("");
            javaFXInitializer.addLog("❌ 错误: " + e.getMessage());
            addToHistory("vision", message, e.getMessage(), false, System.currentTimeMillis() - startTime);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 执行自动化任务 (带反思循环)
     */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> executeTask(@RequestBody Map<String, String> request) {
        String task = request.get("task");
        if (task == null || task.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "任务描述不能为空"));
        }

        log.info("🚀 收到执行任务请求: {}", task);
        
        javaFXInitializer.updateState(OverlayWindow.AgentState.EXECUTING);
        javaFXInitializer.setThinkingText("执行任务中...");
        javaFXInitializer.addLog("🎯 任务: " + task);

        long startTime = System.currentTimeMillis();
        try {
            ReflectionLoop.ReflectionResult result = reflectionLoop.executeWithReflection(
                task,
                javaFXInitializer::addLog
            );
            long duration = System.currentTimeMillis() - startTime;
            
            javaFXInitializer.updateState(result.isSuccess() ? 
                OverlayWindow.AgentState.SUCCESS : OverlayWindow.AgentState.ERROR);
            javaFXInitializer.setThinkingText("");
            
            addToHistory("execute", task, result.getMessage(), result.isSuccess(), duration);

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());
            response.put("iterations", result.getIterations());
            response.put("actionHistory", result.getActionHistory());
            response.put("duration_ms", duration);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("执行任务失败", e);
            javaFXInitializer.updateState(OverlayWindow.AgentState.ERROR);
            javaFXInitializer.setThinkingText("");
            addToHistory("execute", task, e.getMessage(), false, System.currentTimeMillis() - startTime);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取当前屏幕截图 (Base64)
     */
    @GetMapping("/screenshot")
    public ResponseEntity<Map<String, Object>> getScreenshot() {
        try {
            String base64 = screenCapturer.captureScreenAsBase64();
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("image", base64);
            result.put("width", screenCapturer.getScreenSize().width);
            result.put("height", screenCapturer.getScreenSize().height);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("截图失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取 Agent 状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("available", agentService.isAvailable());
        status.put("model", agentService.getModelInfo());
        status.put("uiInitialized", javaFXInitializer.isInitialized());
        status.put("historyCount", taskHistory.size());
        return ResponseEntity.ok(status);
    }

    /**
     * 重置对话历史
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetConversation() {
        agentService.resetConversation();
        javaFXInitializer.addLog("🔄 对话已重置");
        return ResponseEntity.ok(Map.of("status", "对话历史已重置"));
    }

    /**
     * 获取任务历史
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestParam(defaultValue = "20") int limit) {
        List<TaskRecord> records = new ArrayList<>();
        int count = 0;
        for (TaskRecord record : taskHistory) {
            if (count >= limit) break;
            records.add(record);
            count++;
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("total", taskHistory.size());
        result.put("records", records);
        return ResponseEntity.ok(result);
    }

    /**
     * 清空任务历史
     */
    @DeleteMapping("/history")
    public ResponseEntity<Map<String, String>> clearHistory() {
        taskHistory.clear();
        javaFXInitializer.addLog("🗑️ 历史记录已清空");
        return ResponseEntity.ok(Map.of("status", "历史记录已清空"));
    }

    /**
     * 显示 Overlay UI
     */
    @PostMapping("/ui/show")
    public ResponseEntity<Map<String, String>> showUI() {
        javaFXInitializer.showOverlay();
        return ResponseEntity.ok(Map.of("status", "UI已显示"));
    }

    /**
     * 隐藏 Overlay UI
     */
    @PostMapping("/ui/hide")
    public ResponseEntity<Map<String, String>> hideUI() {
        javaFXInitializer.hideOverlay();
        return ResponseEntity.ok(Map.of("status", "UI已隐藏"));
    }

    /**
     * 添加到历史记录
     */
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
        
        // 限制历史大小
        while (taskHistory.size() > MAX_HISTORY_SIZE) {
            taskHistory.removeLast();
        }
    }

    /**
     * 截断字符串
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    /**
     * 任务记录
     */
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
