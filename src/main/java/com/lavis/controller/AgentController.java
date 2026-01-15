package com.lavis.controller;

import com.lavis.cognitive.AgentService;
import com.lavis.cognitive.computeruse.ComputerUseAgent;
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
 * Agent REST API 控制器
 * 提供 HTTP 接口与 Agent 交互
 * 
 * 【架构升级】统一使用 TaskOrchestrator 作为任务执行入口
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final ScreenCapturer screenCapturer;
    private final JavaFXInitializer javaFXInitializer;
    private final ComputerUseAgent computerUseAgent;
    
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
     * 执行自动化任务
     * 
     * 【架构升级】统一使用 TaskOrchestrator 执行，实现 M-E-R 闭环
     * 这个接口现在等同于 /execute-plan，保留是为了向后兼容
     */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> executeTask(@RequestBody Map<String, String> request) {
        String task = request.get("task");
        if (task == null || task.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "任务描述不能为空"));
        }

        log.info("🚀 收到执行任务请求: {}", task);
        
        javaFXInitializer.updateState(OverlayWindow.AgentState.EXECUTING);
        javaFXInitializer.setThinkingText("规划任务中...");
        javaFXInitializer.addLog("🎯 任务: " + task);

        long startTime = System.currentTimeMillis();
        try {
            // 【统一入口】使用 TaskOrchestrator 执行任务
            TaskOrchestrator orchestrator = agentService.getTaskOrchestrator();
            TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal(task);
            long duration = System.currentTimeMillis() - startTime;
            
            javaFXInitializer.updateState(result.isSuccess() ? 
                OverlayWindow.AgentState.SUCCESS : OverlayWindow.AgentState.ERROR);
            javaFXInitializer.setThinkingText("");
            
            addToHistory("execute", task, result.getMessage(), result.isSuccess(), duration);

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());
            response.put("partial", result.isPartial());
            response.put("duration_ms", duration);
            
            // 添加计划详情
            if (result.getPlan() != null) {
                response.put("plan_summary", result.getPlan().generateSummary());
                response.put("total_steps", result.getPlan().getSteps().size());
                response.put("progress_percent", result.getPlan().getProgressPercent());
            }
            
            // 添加 GlobalContext 信息
            if (orchestrator.getGlobalContext() != null) {
                response.put("execution_summary", orchestrator.getGlobalContext().getExecutionSummary());
            }
            
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
     * 【新架构】使用 Plan-Execute 模式执行复杂任务
     * 
     * 这是双层大脑架构的 API：
     * - Planner 负责拆解任务为步骤
     * - Executor 逐步执行（独立上下文，自我修正）
     */
    @PostMapping("/execute-plan")
    public ResponseEntity<Map<String, Object>> executePlanTask(@RequestBody Map<String, String> request) {
        String goal = request.get("goal");
        if (goal == null || goal.isBlank()) {
            goal = request.get("task");
        }
        if (goal == null || goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "目标描述不能为空"));
        }

        log.info("🚀 [Plan-Execute] 收到任务请求: {}", goal);
        
        javaFXInitializer.updateState(OverlayWindow.AgentState.EXECUTING);
        javaFXInitializer.setThinkingText("规划任务中...");
        javaFXInitializer.addLog("🎯 [Plan-Execute] 目标: " + goal);

        long startTime = System.currentTimeMillis();
        try {
            String result = agentService.executePlanTask(goal);
            long duration = System.currentTimeMillis() - startTime;
            
            boolean success = result.startsWith("✅");
            javaFXInitializer.updateState(success ? 
                OverlayWindow.AgentState.SUCCESS : OverlayWindow.AgentState.ERROR);
            javaFXInitializer.setThinkingText("");
            javaFXInitializer.addLog(success ? "✅ 任务完成" : "⚠️ 任务部分完成或失败");
            
            addToHistory("plan-execute", goal, result, success, duration);

            Map<String, Object> response = new HashMap<>();
            response.put("success", success);
            response.put("result", result);
            response.put("duration_ms", duration);
            
            // 获取计划详情
            var orchestrator = agentService.getTaskOrchestrator();
            if (orchestrator != null && orchestrator.getCurrentPlan() != null) {
                response.put("plan_summary", orchestrator.getCurrentPlan().generateSummary());
                response.put("execution_summary", orchestrator.getExecutionSummary());
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Plan-Execute 任务失败", e);
            javaFXInitializer.updateState(OverlayWindow.AgentState.ERROR);
            javaFXInitializer.setThinkingText("");
            addToHistory("plan-execute", goal, e.getMessage(), false, System.currentTimeMillis() - startTime);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 重置调度器状态
     */
    @PostMapping("/orchestrator/reset")
    public ResponseEntity<Map<String, String>> resetOrchestrator() {
        var orchestrator = agentService.getTaskOrchestrator();
        if (orchestrator != null) {
            orchestrator.reset();
        }
        javaFXInitializer.addLog("🔄 调度器已重置");
        return ResponseEntity.ok(Map.of("status", "调度器已重置"));
    }
    
    /**
     * 获取调度器状态
     */
    @GetMapping("/orchestrator/status")
    public ResponseEntity<Map<String, Object>> getOrchestratorStatus() {
        var orchestrator = agentService.getTaskOrchestrator();
        Map<String, Object> status = new HashMap<>();
        
        if (orchestrator != null) {
            status.put("state", orchestrator.getState().name());
            status.put("summary", orchestrator.getExecutionSummary());
            
            if (orchestrator.getCurrentPlan() != null) {
                var plan = orchestrator.getCurrentPlan();
                status.put("plan_id", plan.getPlanId());
                status.put("goal", plan.getUserGoal());
                status.put("total_steps", plan.getSteps().size());
                status.put("progress_percent", plan.getProgressPercent());
                status.put("plan_status", plan.getStatus().name());
            }
        } else {
            status.put("state", "NOT_INITIALIZED");
        }
        
        return ResponseEntity.ok(status);
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
    
    // ==================== Gemini Computer Use API ====================
    
    /**
     * 使用 Gemini Computer Use 模式执行任务
     * 
     * 这是基于 Google Gemini Computer Use API 的实现：
     * - 使用预定义的 Computer Use 操作（click_at, type_text_at, scroll_document 等）
     * - 坐标使用归一化范围（0-1000）
     * - 支持 safety_decision 安全确认机制
     * 
     * @see <a href="https://ai.google.dev/gemini-api/docs/computer-use">Gemini Computer Use</a>
     */
    @PostMapping("/computer-use")
    public ResponseEntity<Map<String, Object>> executeComputerUseTask(@RequestBody Map<String, Object> request) {
        String task = (String) request.get("task");
        if (task == null || task.isBlank()) {
            task = (String) request.get("query");
        }
        if (task == null || task.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "任务描述不能为空"));
        }
        
        @SuppressWarnings("unchecked")
        List<String> excludedFunctions = request.containsKey("excluded_functions") 
                ? (List<String>) request.get("excluded_functions") 
                : List.of();
        
        log.info("🖥️ [Computer Use] 收到任务请求: {}", task);
        
        javaFXInitializer.updateState(OverlayWindow.AgentState.EXECUTING);
        javaFXInitializer.setThinkingText("Computer Use 执行中...");
        javaFXInitializer.addLog("🖥️ [Computer Use] 任务: " + task);
        
        long startTime = System.currentTimeMillis();
        try {
            ComputerUseAgent.AgentResult result = computerUseAgent.executeTask(task, excludedFunctions);
            long duration = System.currentTimeMillis() - startTime;
            
            javaFXInitializer.updateState(result.isSuccess() ? 
                    OverlayWindow.AgentState.SUCCESS : 
                    (result.isCancelled() ? OverlayWindow.AgentState.IDLE : OverlayWindow.AgentState.ERROR));
            javaFXInitializer.setThinkingText("");
            
            addToHistory("computer-use", task, 
                    result.isSuccess() ? result.getReasoning() : result.getErrorMessage(), 
                    result.isSuccess(), duration);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("cancelled", result.isCancelled());
            response.put("reasoning", result.getReasoning());
            response.put("error", result.getErrorMessage());
            response.put("duration_ms", duration);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Computer Use 任务失败", e);
            javaFXInitializer.updateState(OverlayWindow.AgentState.ERROR);
            javaFXInitializer.setThinkingText("");
            addToHistory("computer-use", task, e.getMessage(), false, System.currentTimeMillis() - startTime);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 中断 Computer Use 执行
     */
    @PostMapping("/computer-use/interrupt")
    public ResponseEntity<Map<String, String>> interruptComputerUse() {
        computerUseAgent.interrupt();
        javaFXInitializer.addLog("⚠️ Computer Use 执行已中断");
        return ResponseEntity.ok(Map.of("status", "Computer Use 执行已中断"));
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
