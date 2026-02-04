package com.lavis.service.chat;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一的聊天响应模型
 *
 * 统一了 /chat 和 /voice-chat 的响应格式
 */
public record ChatResponse(
    /**
     * 是否成功
     */
    boolean success,

    /**
     * 用户输入的文本（标准化后）
     */
    String userText,

    /**
     * Agent 的响应文本
     */
    String agentText,

    /**
     * 请求 ID（用于追踪和日志）
     */
    String requestId,

    /**
     * 总耗时（毫秒）
     */
    long durationMs,

    /**
     * TTS 音频是否正在异步推送中
     */
    boolean audioPending,

    /**
     * TaskOrchestrator 状态
     */
    String orchestratorState
) {
    /**
     * 转换为响应 Map（用于 HTTP 响应）
     */
    public Map<String, Object> toResponseMap(boolean includeLegacyFields) {
        Map<String, Object> response = new HashMap<>();

        // 核心字段
        response.put("success", success);
        response.put("user_text", userText);
        response.put("agent_text", agentText);
        response.put("request_id", requestId);
        response.put("duration_ms", durationMs);
        response.put("audio_pending", audioPending);

        // 向后兼容：/chat 接口的旧格式
        if (includeLegacyFields) {
            response.put("response", agentText);
        }

        // TaskOrchestrator 相关字段（可选）
        if (orchestratorState != null) {
            response.put("orchestrator_state", orchestratorState);
        }

        return response;
    }

    /**
     * 默认转换（包含向后兼容字段）
     */
    public Map<String, Object> toResponseMap() {
        return toResponseMap(true);
    }

    /**
     * 创建快速路径的响应（chatWithScreenshot）
     */
    public static ChatResponse fastPath(String userText, String agentText, String requestId, long durationMs) {
        return new ChatResponse(
            true,
            userText,
            agentText,
            requestId,
            durationMs,
            false,
            null
        );
    }

    /**
     * 创建复杂路径的响应（TaskOrchestrator）
     */
    public static ChatResponse orchestratorPath(
        String userText,
        String agentText,
        String requestId,
        long durationMs,
        boolean success,
        String orchestratorState
    ) {
        return new ChatResponse(
            success,
            userText,
            agentText,
            requestId,
            durationMs,
            false,
            orchestratorState
        );
    }

    /**
     * 创建错误响应
     */
    public static ChatResponse error(String userText, String errorMessage, String requestId, long durationMs) {
        return new ChatResponse(
            false,
            userText,
            errorMessage,
            requestId,
            durationMs,
            false,
            null
        );
    }
}
