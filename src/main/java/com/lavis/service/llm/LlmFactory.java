package com.lavis.service.llm;

import com.lavis.config.llm.LlmProperties;
import com.lavis.config.llm.ModelConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 模型工厂服务
 * 
 * 根据配置动态创建和缓存 ChatLanguageModel 实例
 * 
 * 使用方式:
 * <pre>
 * // 获取指定别名的模型
 * ChatLanguageModel model = llmFactory.getModel("modela");
 * 
 * // 获取默认模型
 * ChatLanguageModel defaultModel = llmFactory.getModel();
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmFactory {
    
    private final LlmProperties llmProperties;
    
    /**
     * 模型实例缓存
     * 避免重复创建相同配置的模型实例
     */
    private final Map<String, ChatLanguageModel> modelCache = new ConcurrentHashMap<>();
    
    /**
     * 获取默认模型
     * 
     * @return ChatLanguageModel 实例
     * @throws IllegalArgumentException 如果默认模型未配置
     */
    public ChatLanguageModel getModel() {
        return getModel(llmProperties.getDefaultModel());
    }
    
    /**
     * 根据别名获取模型实例
     * 
     * @param alias 模型别名（对应 YAML 中的 key，如 modela, modelb）
     * @return ChatLanguageModel 实例
     * @throws IllegalArgumentException 如果指定别名的模型未配置
     */
    public ChatLanguageModel getModel(String alias) {
        // 从缓存获取
        return modelCache.computeIfAbsent(alias, this::createModel);
    }
    
    /**
     * 创建模型实例
     */
    private ChatLanguageModel createModel(String alias) {
        ModelConfig config = llmProperties.getModelConfig(alias);
        
        if (config == null) {
            throw new IllegalArgumentException(
                String.format("模型配置 '%s' 不存在。可用的模型: %s", 
                    alias, llmProperties.getModels().keySet()));
        }
        
        // 验证必要配置
        validateConfig(alias, config);
        
        log.info("🔧 创建 LLM 模型实例: alias={}, provider={}, model={}", 
            alias, config.getProvider(), config.getModelName());
        
        return switch (config.getProvider()) {
            case OPENAI -> createOpenAiModel(config);
            case GEMINI -> createGeminiModel(config);
        };
    }
    
    /**
     * 验证配置完整性
     */
    private void validateConfig(String alias, ModelConfig config) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalArgumentException(
                String.format("模型 '%s' 的 api-key 未配置", alias));
        }
        
        if (config.getModelName() == null || config.getModelName().isBlank()) {
            throw new IllegalArgumentException(
                String.format("模型 '%s' 的 model-name 未配置", alias));
        }
        
        // OpenAI provider 需要 baseUrl（除非是官方 API）
        if (config.getProvider() == ModelConfig.Provider.OPENAI 
                && (config.getBaseUrl() == null || config.getBaseUrl().isBlank())) {
            log.warn("⚠️ 模型 '{}' 使用 OPENAI provider 但未配置 base-url，将使用 OpenAI 官方 API", alias);
        }
    }
    
    /**
     * 创建 OpenAI 兼容模型
     * 支持 OpenAI 官方 API 及第三方兼容接口（如阿里云 DashScope）
     */
    private ChatLanguageModel createOpenAiModel(ModelConfig config) {
        var builder = OpenAiChatModel.builder()
            .apiKey(config.getApiKey())
            .modelName(config.getModelName())
            .temperature(config.getTemperature())
            .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
            .maxRetries(config.getMaxRetries());
        
        // 注入 baseUrl 以支持第三方兼容模型
        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            builder.baseUrl(config.getBaseUrl());
        }
        
        return builder.build();
    }
    
    /**
     * 创建 Google Gemini 模型
     */
    private ChatLanguageModel createGeminiModel(ModelConfig config) {
        return GoogleAiGeminiChatModel.builder()
            .apiKey(config.getApiKey())
            .modelName(config.getModelName())
            .temperature(config.getTemperature())
            .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
            .maxRetries(config.getMaxRetries())
            .build();
    }
    
    /**
     * 清除模型缓存
     * 用于配置变更后重新加载
     */
    public void clearCache() {
        modelCache.clear();
        log.info("🔄 LLM 模型缓存已清空");
    }
    
    /**
     * 清除指定模型的缓存
     */
    public void clearCache(String alias) {
        modelCache.remove(alias);
        log.info("🔄 LLM 模型 '{}' 缓存已清空", alias);
    }
    
    /**
     * 检查模型是否可用（配置存在且有效）
     */
    public boolean isModelAvailable(String alias) {
        ModelConfig config = llmProperties.getModelConfig(alias);
        if (config == null) {
            return false;
        }
        return config.getApiKey() != null && !config.getApiKey().isBlank()
            && config.getModelName() != null && !config.getModelName().isBlank();
    }
    
    /**
     * 获取所有已配置的模型别名
     */
    public java.util.Set<String> getAvailableModels() {
        return llmProperties.getModels().keySet();
    }
}

