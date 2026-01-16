package com.lavis.config.llm;

import lombok.Data;

/**
 * 单个 LLM 模型的配置 POJO
 * 
 * 支持的 provider:
 * - OPENAI: OpenAI 兼容接口（包括阿里云 DashScope、Azure OpenAI 等）
 * - GEMINI: Google Gemini 原生接口
 */
@Data
public class ModelConfig {
    
    /**
     * 模型提供商类型
     */
    public enum Provider {
        OPENAI,  // OpenAI 兼容接口
        GEMINI   // Google Gemini 原生接口
    }
    
    /**
     * 模型提供商
     */
    private Provider provider = Provider.OPENAI;
    
    /**
     * API 基础 URL（仅 OPENAI provider 需要，用于支持第三方兼容接口）
     * 例如: https://dashscope.aliyuncs.com/compatible-mode/v1
     */
    private String baseUrl;
    
    /**
     * API Key
     */
    private String apiKey;
    
    /**
     * 模型名称
     * 例如: qwen-max, gemini-2.0-flash
     */
    private String modelName;
    
    /**
     * 温度参数 (0.0 - 2.0)
     */
    private Double temperature = 0.4;
    
    /**
     * 请求超时时间（秒）
     */
    private Integer timeoutSeconds = 60;
    
    /**
     * 最大重试次数
     */
    private Integer maxRetries = 3;
}

