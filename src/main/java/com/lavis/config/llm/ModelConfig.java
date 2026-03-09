package com.lavis.config.llm;

import lombok.Data;

/**
 * 单items LLM 模型的configuration POJO
 * * 支持的 provider:
 * - OPENAI: OpenAI 兼容接口（包括阿里云 DashScope Chat、Azure OpenAI etc）
 * - GEMINI: Google Gemini 原生接口
 * - DASHSCOPE: 阿里云 DashScope 原生接口 (用于语音服务)
 */
@Data
public class ModelConfig {

    /**
     * 模型类型
     */
    public enum ModelType {
        CHAT,   // 对话模型
        STT,    // 语音转文characters
        TTS     // 文characters转语音
    }

    /**
     * 模型提供商类型
     */
    public enum Provider {
        OPENAI,     // OpenAI 兼容接口
        GEMINI,     // Google Gemini 原生接口
        DASHSCOPE   // 阿里云原生接口 (主要用于 STT/TTS)
    }

    /**
     * 模型类型
     */
    private ModelType type = ModelType.CHAT;

    /**
     * 模型提供商
     */
    private Provider provider = Provider.OPENAI;

    /**
     * API 基础 URL
     */
    private String baseUrl;

    /**
     * API Key
     */
    private String apiKey;

    /**
     * 模型名称
     * for example: qwen-max, paraformer-realtime-v1, cosyvoice-v1
     */
    private String modelName;

    /**
     * 温度参数 (0.0 - 2.0)
     */
    private Double temperature = 0.4;

    /**
     * 请求超时时间（seconds）
     */
    private Integer timeoutSeconds = 60;

    /**
     * 最大重试times数
     */
    private Integer maxRetries = 3;

    /**
     * TTS 语音音色 (for example: longxiaochun, Cherry)
     */
    private String voice = "longxiaochun";

    /**
     * TTS 音频格式 (for example: mp3, wav)
     */
    private String format = "mp3";
}