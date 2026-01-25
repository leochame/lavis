package com.lavis.websocket;

import com.lavis.cognitive.model.PlanStep;
import com.lavis.cognitive.model.TaskPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流事件服务
 * 负责向前端推送实时工作流状态更新
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEventService {

    private final AgentWebSocketHandler webSocketHandler;

    /**
     * 发送计划创建事件
     */
    public void onPlanCreated(TaskPlan plan) {
        try {
            broadcast("plan_created", Map.of(
                "planId", plan != null ? plan.getPlanId() : "unknown",
                "userGoal", plan != null && plan.getUserGoal() != null ? plan.getUserGoal() : "未知目标",
                "steps", plan != null ? formatSteps(plan.getSteps()) : List.of(),
                "totalSteps", plan != null && plan.getSteps() != null ? plan.getSteps().size() : 0
            ));
        } catch (Exception e) {
            log.error("❌ 发送计划创建事件时出错: {}", e.getMessage(), e);
            // 发送错误事件
            onExecutionError("发送计划创建事件失败: " + e.getMessage(), "PLAN_CREATED_ERROR", 
                    plan != null ? plan.getPlanId() : "unknown");
        }
    }

    /**
     * 发送步骤开始事件
     */
    public void onStepStarted(TaskPlan plan, PlanStep step) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("planId", plan != null ? plan.getPlanId() : "unknown");
            data.put("stepId", step != null ? step.getId() : 0);
            data.put("description", step != null && step.getDescription() != null ? step.getDescription() : "未知步骤");
            if (step != null && step.getType() != null) {
                data.put("type", step.getType().name());
            }
            data.put("progress", plan != null ? plan.getProgressPercent() : 0);
            broadcast("step_started", data);
        } catch (Exception e) {
            log.error("❌ 发送步骤开始事件时出错: {}", e.getMessage(), e);
            // 发送错误事件
            onExecutionError("发送步骤开始事件失败: " + e.getMessage(), "STEP_STARTED_ERROR", 
                    plan != null ? plan.getPlanId() : "unknown");
        }
    }

    /**
     * 发送步骤完成事件
     */
    public void onStepCompleted(TaskPlan plan, PlanStep step) {
        broadcast("step_completed", Map.of(
            "planId", plan.getPlanId(),
            "stepId", step.getId(),
            "status", step.getStatus().name(),
            "resultSummary", step.getResultSummary() != null ? step.getResultSummary() : "",
            "progress", plan.getProgressPercent(),
            "executionTimeMs", step.getExecutionTimeMs()
        ));
    }

    /**
     * 发送步骤失败事件
     */
    public void onStepFailed(TaskPlan plan, PlanStep step, String reason) {
        Map<String, Object> data = new HashMap<>();
        data.put("planId", plan.getPlanId());
        data.put("stepId", step.getId());
        data.put("status", "FAILED");
        data.put("reason", reason);
        data.put("progress", plan.getProgressPercent());
        
        if (step.getPostMortem() != null) {
            data.put("postMortem", Map.of(
                "lastScreenState", step.getPostMortem().getLastScreenState() != null 
                    ? step.getPostMortem().getLastScreenState() : "",
                "failureReason", step.getPostMortem().getFailureReason() != null 
                    ? step.getPostMortem().getFailureReason().name() : "UNKNOWN",
                "suggestedRecovery", step.getPostMortem().getSuggestedRecovery() != null 
                    ? step.getPostMortem().getSuggestedRecovery() : ""
            ));
        }
        
        broadcast("step_failed", data);
    }

    /**
     * 发送计划完成事件
     */
    public void onPlanCompleted(TaskPlan plan) {
        broadcast("plan_completed", Map.of(
            "planId", plan.getPlanId(),
            "userGoal", plan.getUserGoal(),
            "status", plan.getStatus().name(),
            "progress", 100
        ));
    }

    /**
     * 发送计划失败事件
     */
    public void onPlanFailed(TaskPlan plan, String reason) {
        broadcast("plan_failed", Map.of(
            "planId", plan.getPlanId(),
            "userGoal", plan.getUserGoal(),
            "status", "FAILED",
            "reason", reason,
            "progress", plan.getProgressPercent()
        ));
    }

    /**
     * 发送思考/分析事件 (AI 正在分析屏幕)
     */
    public void onThinking(String context) {
        broadcast("thinking", Map.of(
            "context", context,
            "timestamp", Instant.now().toEpochMilli()
        ));
    }

    /**
     * 发送动作执行事件
     */
    public void onActionExecuted(String actionType, String description, boolean success) {
        broadcast("action_executed", Map.of(
            "actionType", actionType,
            "description", description,
            "success", success,
            "timestamp", Instant.now().toEpochMilli()
        ));
    }

    /**
     * 发送截图前隐藏窗口请求
     */
    public void requestHideWindow() {
        broadcast("hide_window", Map.of(
            "action", "hide",
            "reason", "screenshot"
        ));
    }

    /**
     * 发送截图后显示窗口请求
     */
    public void requestShowWindow() {
        broadcast("show_window", Map.of(
            "action", "show",
            "reason", "screenshot_complete"
        ));
    }

    /**
     * 发送语音播报事件（TTS通知）
     * 用于在任务完成时向用户播报拟人化的完成消息
     */
    public void onVoiceAnnouncement(String text) {
        Map<String, Object> data = new HashMap<>();
        data.put("text", text);
        data.put("timestamp", Instant.now().toEpochMilli());
        
        broadcast("voice_announcement", data);
        log.info("🎙️ 发送语音播报: {}", text);
    }

    /**
     * 发送日志消息
     */
    public void sendLog(String level, String message) {
        broadcast("log", Map.of(
            "level", level,
            "message", message,
            "timestamp", Instant.now().toEpochMilli()
        ));
    }

    /**
     * 发送执行错误事件
     * 用于通知前端执行过程中发生的异常错误
     */
    public void onExecutionError(String errorMessage, String errorType, String planId) {
        Map<String, Object> data = new HashMap<>();
        data.put("errorMessage", errorMessage);
        data.put("errorType", errorType != null ? errorType : "UNKNOWN_ERROR");
        data.put("planId", planId);
        data.put("timestamp", Instant.now().toEpochMilli());
        
        broadcast("execution_error", data);
        log.error("❌ 发送执行错误事件: {}", errorMessage);
    }

    /**
     * 发送任务执行异常事件（用于 TaskOrchestrator 的 catch 块）
     */
    public void onTaskExecutionException(String errorMessage, String planId) {
        Map<String, Object> data = new HashMap<>();
        data.put("errorMessage", errorMessage);
        data.put("errorType", "TASK_EXECUTION_EXCEPTION");
        data.put("planId", planId != null ? planId : "unknown");
        data.put("timestamp", Instant.now().toEpochMilli());
        
        broadcast("execution_error", data);
        log.error("❌ 发送任务执行异常事件: {}", errorMessage);
    }

    /**
     * 广播消息
     */
    private void broadcast(String type, Map<String, Object> data) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.put("data", data);
        message.put("timestamp", Instant.now().toEpochMilli());
        
        webSocketHandler.broadcast(message);
        log.debug("📤 广播 WebSocket 事件: {} (连接数: {})", type, webSocketHandler.getConnectionCount());
    }

    /**
     * 格式化步骤列表
     */
    private List<Map<String, Object>> formatSteps(List<PlanStep> steps) {
        return steps.stream().map(step -> {
            try {
                Map<String, Object> map = new HashMap<>();
                map.put("id", step.getId());
                map.put("description", step.getDescription() != null ? step.getDescription() : "");
                if (step.getType() != null) {
                    map.put("type", step.getType().name());
                }
                if (step.getStatus() != null) {
                    map.put("status", step.getStatus().name());
                } else {
                    map.put("status", "PENDING");
                }
                return map;
            } catch (Exception e) {
                log.error("❌ 格式化步骤时出错: {}", e.getMessage(), e);
                // 返回最小化的安全数据
                Map<String, Object> safeMap = new HashMap<>();
                safeMap.put("id", step != null ? step.getId() : 0);
                safeMap.put("description", step != null && step.getDescription() != null ? step.getDescription() : "未知步骤");
                safeMap.put("status", "PENDING");
                return safeMap;
            }
        }).toList();
    }
}

