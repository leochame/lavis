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
        broadcast("plan_created", Map.of(
            "planId", plan.getPlanId(),
            "userGoal", plan.getUserGoal(),
            "steps", formatSteps(plan.getSteps()),
            "totalSteps", plan.getSteps().size()
        ));
    }

    /**
     * 发送步骤开始事件
     */
    public void onStepStarted(TaskPlan plan, PlanStep step) {
        broadcast("step_started", Map.of(
            "planId", plan.getPlanId(),
            "stepId", step.getId(),
            "description", step.getDescription(),
            "type", step.getType().name(),
            "progress", plan.getProgressPercent()
        ));
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
            Map<String, Object> map = new HashMap<>();
            map.put("id", step.getId());
            map.put("description", step.getDescription());
            map.put("type", step.getType().name());
            map.put("status", step.getStatus().name());
            map.put("complexity", step.getComplexity());
            if (step.getDefinitionOfDone() != null) {
                map.put("definitionOfDone", step.getDefinitionOfDone());
            }
            return map;
        }).toList();
    }
}

