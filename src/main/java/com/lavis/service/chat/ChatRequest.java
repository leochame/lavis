package com.lavis.service.chat;

/**
 * 统一的聊天请求模型
 * 
 * 用于标准化文本和音频输入，支持统一的处理流程
 */
public record ChatRequest(
    /**
     * 标准化后的用户文本（文本输入直接使用，音频输入需要 STT 转换）
     */
    String text,
    
    /**
     * 输入类型："text" | "audio"
     */
    String inputType,
    
    /**
     * WebSocket session ID（用于 TTS 推送）
     */
    String wsSessionId,
    
    /**
     * 是否使用 TaskOrchestrator（复杂任务路径）
     * false: 使用 chatWithScreenshot（快速路径）
     * true: 使用 TaskOrchestrator（规划执行路径）
     */
    boolean useOrchestrator,
    
    /**
     * 是否需要 TTS 语音反馈
     */
    boolean needsTts
) {
    /**
     * 创建文本输入的请求
     */
    public static ChatRequest textInput(String text, String wsSessionId, boolean useOrchestrator, boolean needsTts) {
        return new ChatRequest(text, "text", wsSessionId, useOrchestrator, needsTts);
    }
    
    /**
     * 创建音频输入的请求（需要先进行 STT 转换）
     */
    public static ChatRequest audioInput(String transcribedText, String wsSessionId, boolean useOrchestrator, boolean needsTts) {
        return new ChatRequest(transcribedText, "audio", wsSessionId, useOrchestrator, needsTts);
    }
}

