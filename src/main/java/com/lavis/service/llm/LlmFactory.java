package com.lavis.service.llm;

import com.lavis.config.llm.LlmProperties;
import com.lavis.config.llm.ModelConfig;

import com.lavis.service.llm.stt.DashScopeSttModel;
import com.lavis.service.llm.stt.OpenAiSttModel;
import com.lavis.service.llm.stt.SttModel;
import com.lavis.service.llm.tts.DashScopeTtsModel;
import com.lavis.service.llm.tts.OpenAiTtsModel;
import com.lavis.service.llm.tts.TtsModel;
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
 * * 根据配置动态创建和缓存 ChatLanguageModel, SttModel, TtsModel 实例
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmFactory {

    private final LlmProperties llmProperties;

    /**
     * 模型实例缓存
     */
    private final Map<String, ChatLanguageModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, SttModel> sttModelCache = new ConcurrentHashMap<>();
    private final Map<String, TtsModel> ttsModelCache = new ConcurrentHashMap<>();

    /**
     * 获取默认 Chat 模型
     */
    public ChatLanguageModel getModel() {
        return getModel(llmProperties.getDefaultModel());
    }

    /**
     * 获取指定别名的 Chat 模型
     */
    public ChatLanguageModel getModel(String alias) {
        return chatModelCache.computeIfAbsent(alias, this::createChatModel);
    }

    /**
     * 获取默认 STT 模型
     */
    public SttModel getSttModel() {
        return getSttModel(llmProperties.getDefaultSttModel());
    }

    /**
     * 获取指定别名的 STT 模型
     */
    public SttModel getSttModel(String alias) {
        return sttModelCache.computeIfAbsent(alias, this::createSttModel);
    }

    /**
     * 获取默认 TTS 模型
     */
    public TtsModel getTtsModel() {
        return getTtsModel(llmProperties.getDefaultTtsModel());
    }

    /**
     * 获取指定别名的 TTS 模型
     */
    public TtsModel getTtsModel(String alias) {
        return ttsModelCache.computeIfAbsent(alias, this::createTtsModel);
    }

    /**
     * 创建 Chat 模型实例
     */
    private ChatLanguageModel createChatModel(String alias) {
        ModelConfig config = getAndValidateConfig(alias, ModelConfig.ModelType.CHAT);

        log.info("🔧 创建 Chat 模型实例: alias={}, provider={}, model={}",
                alias, config.getProvider(), config.getModelName());

        return switch (config.getProvider()) {
            case OPENAI -> createOpenAiModel(config);
            case GEMINI -> createGeminiModel(config);
            // DashScope 的 Chat 也是 OpenAI 兼容的，所以走 OpenAiModel
            case DASHSCOPE -> createOpenAiModel(config);
        };
    }

    /**
     * 创建 STT 模型实例
     */
    private SttModel createSttModel(String alias) {
        ModelConfig config = getAndValidateConfig(alias, ModelConfig.ModelType.STT);

        log.info("🔧 创建 STT 模型实例: alias={}, provider={}, model={}",
                alias, config.getProvider(), config.getModelName());

        return switch (config.getProvider()) {
            case DASHSCOPE -> new DashScopeSttModel(config);
            case OPENAI -> new OpenAiSttModel(config);
            default -> throw new IllegalArgumentException("不支持的 STT Provider: " + config.getProvider());
        };
    }

    /**
     * 创建 TTS 模型实例
     */
    private TtsModel createTtsModel(String alias) {
        ModelConfig config = getAndValidateConfig(alias, ModelConfig.ModelType.TTS);

        log.info("🔧 创建 TTS 模型实例: alias={}, provider={}, model={}",
                alias, config.getProvider(), config.getModelName());

        return switch (config.getProvider()) {
            case DASHSCOPE -> new DashScopeTtsModel(config);
            case OPENAI -> new OpenAiTtsModel(config);
            default -> throw new IllegalArgumentException("不支持的 TTS Provider: " + config.getProvider());
        };
    }

    private ModelConfig getAndValidateConfig(String alias, ModelConfig.ModelType expectedType) {
        ModelConfig config = llmProperties.getModelConfig(alias);

        if (config == null) {
            throw new IllegalArgumentException(
                    String.format("模型配置 '%s' 不存在。可用的模型: %s",
                            alias, llmProperties.getModels().keySet()));
        }

        validateConfig(alias, config);
        return config;
    }

    private void validateConfig(String alias, ModelConfig config) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalArgumentException(
                    String.format("模型 '%s' 的 api-key 未配置", alias));
        }

        if (config.getModelName() == null || config.getModelName().isBlank()) {
            throw new IllegalArgumentException(
                    String.format("模型 '%s' 的 model-name 未配置", alias));
        }
    }

    private ChatLanguageModel createOpenAiModel(ModelConfig config) {
        var builder = OpenAiChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .maxRetries(config.getMaxRetries());

        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            builder.baseUrl(config.getBaseUrl());
        }

        return builder.build();
    }

    private ChatLanguageModel createGeminiModel(ModelConfig config) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .maxRetries(config.getMaxRetries())
                .build();
    }

    public void clearCache() {
        chatModelCache.clear();
        sttModelCache.clear();
        ttsModelCache.clear();
        log.info("🔄 所有模型缓存已清空");
    }

    public boolean isModelAvailable(String alias) {
        ModelConfig config = llmProperties.getModelConfig(alias);
        if (config == null) {
            return false;
        }
        return config.getApiKey() != null && !config.getApiKey().isBlank()
                && config.getModelName() != null && !config.getModelName().isBlank();
    }

    public java.util.Set<String> getAvailableModels() {
        return llmProperties.getModels().keySet();
    }
}