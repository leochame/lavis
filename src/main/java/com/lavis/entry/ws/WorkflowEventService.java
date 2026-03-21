package com.lavis.entry.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
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
     * 发送任务开始事件
     */
    public void onTaskStarted(String taskId, String goal) {
        broadcast("task_started", Map.of(
            "taskId", taskId,
            "goal", goal,
            "timestamp", Instant.now().toEpochMilli()
        ));
    }

    /**
     * 发送任务完成事件
     */
    public void onTaskCompleted(String taskId, String summary) {
        broadcast("task_completed", Map.of(
            "taskId", taskId,
            "summary", summary,
            "timestamp", Instant.now().toEpochMilli()
        ));
    }

    /**
     * 发送任务失败事件
     */
    public void onTaskFailed(String taskId, String reason) {
        broadcast("task_failed", Map.of(
            "taskId", taskId,
            "reason", reason,
            "timestamp", Instant.now().toEpochMilli()
        ));
    }

    /**
     * 发送迭代进度事件
     */
    public void onIterationProgress(int current, int max, String intent) {
        broadcast("iteration_progress", Map.of(
            "current", current,
            "max", max,
            "intent", intent != null ? intent : "",
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
     */
    public void onExecutionError(String errorMessage, String errorType, String taskId) {
        Map<String, Object> data = new HashMap<>();
        data.put("errorMessage", errorMessage);
        data.put("errorType", errorType != null ? errorType : "UNKNOWN_ERROR");
        data.put("taskId", taskId);
        data.put("timestamp", Instant.now().toEpochMilli());

        broadcast("execution_error", data);
        log.error("❌ 发送执行错误事件: {}", errorMessage);
    }

    /**
     * 发送任务执行异常事件
     */
    public void onTaskExecutionException(String errorMessage, String taskId) {
        Map<String, Object> data = new HashMap<>();
        data.put("errorMessage", errorMessage);
        data.put("errorType", "TASK_EXECUTION_EXCEPTION");
        data.put("taskId", taskId != null ? taskId : "unknown");
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
}
