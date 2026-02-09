package com.lavis.service.llm;

import com.lavis.config.llm.LlmProperties;
import com.lavis.config.llm.ModelConfig;

import com.lavis.service.config.DynamicApiKeyService;
import com.lavis.service.llm.stt.DashScopeSttModel;
import com.lavis.service.llm.stt.GeminiFlashSttModel;
import com.lavis.service.llm.stt.OpenAiSttModel;
import com.lavis.service.llm.stt.SttModel;
import com.lavis.service.llm.tts.DashScopeTtsModel;
import com.lavis.service.llm.tts.GeminiTtsModel;
import com.lavis.service.llm.tts.OpenAiTtsModel;
import com.lavis.service.llm.tts.TtsModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
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
public class LlmFactory {

    private final LlmProperties llmProperties;
    private final DynamicApiKeyService dynamicApiKeyService;

    public LlmFactory(LlmProperties llmProperties, @Lazy DynamicApiKeyService dynamicApiKeyService) {
        this.llmProperties = llmProperties;
        this.dynamicApiKeyService = dynamicApiKeyService;
    }

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
            case GEMINI -> {
                // 如果配置了 baseUrl（中转站），使用 OpenAI 兼容接口
                // 因为 LangChain4j 的 GoogleAiGeminiChatModel 不支持自定义 baseUrl
                if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
                    log.info("🔄 Gemini 模型配置了自定义 baseUrl，使用 OpenAI 兼容接口（中转站）");
                    yield createOpenAiModel(config);
                } else {
                    yield createGeminiModel(config);
                }
            }
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
            case GEMINI -> new GeminiFlashSttModel(config);
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
            case GEMINI -> new GeminiTtsModel(config);
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

        // 应用动态 API Key（如果已设置）
        ModelConfig effectiveConfig = applyDynamicApiKey(config);

        validateConfig(alias, effectiveConfig);
        return effectiveConfig;
    }

    /**
     * 应用动态配置（API Key 和 Base URL）到配置
     * 如果用户设置了动态配置，则覆盖配置文件中的值
     */
    private ModelConfig applyDynamicApiKey(ModelConfig config) {
        if (dynamicApiKeyService == null) {
            return config;
        }

        String effectiveApiKey = dynamicApiKeyService.getEffectiveApiKey(config.getApiKey());
        String effectiveBaseUrl = dynamicApiKeyService.getEffectiveBaseUrl(config.getBaseUrl());
        String effectiveModelName = dynamicApiKeyService.getEffectiveModelName(
                config.getModelName(), config.getType());

        // 如果配置没有变化，直接返回原配置
        boolean apiKeyChanged = effectiveApiKey != null && !effectiveApiKey.equals(config.getApiKey());
        boolean baseUrlChanged = (effectiveBaseUrl == null && config.getBaseUrl() != null) ||
                                (effectiveBaseUrl != null && !effectiveBaseUrl.equals(config.getBaseUrl()));
        boolean modelChanged = (effectiveModelName == null && config.getModelName() != null) ||
                               (effectiveModelName != null && !effectiveModelName.equals(config.getModelName()));

        if (!apiKeyChanged && !baseUrlChanged && !modelChanged) {
            return config;
        }

        // 创建新的配置对象，避免修改原始配置
        ModelConfig newConfig = new ModelConfig();
        newConfig.setType(config.getType());
        newConfig.setProvider(config.getProvider());
        newConfig.setBaseUrl(effectiveBaseUrl);  // 使用动态 Base URL
        newConfig.setApiKey(effectiveApiKey);    // 使用动态 API Key
        newConfig.setModelName(effectiveModelName); // 使用动态模型名称（如有）
        newConfig.setTemperature(config.getTemperature());
        newConfig.setTimeoutSeconds(config.getTimeoutSeconds());
        newConfig.setMaxRetries(config.getMaxRetries());
        newConfig.setVoice(config.getVoice());
        newConfig.setFormat(config.getFormat());

        if (apiKeyChanged) {
            log.debug("🔑 Using dynamic API Key for model");
        }
        if (baseUrlChanged) {
            log.debug("🔗 Using dynamic Base URL: {}", effectiveBaseUrl != null ? effectiveBaseUrl : "Gemini Official");
        }
        if (modelChanged) {
            log.debug("🧠 Using dynamic model-name: {}", effectiveModelName);
        }
        return newConfig;
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

    /**
     * 创建 Gemini 模型实例（使用 Google 官方 API）
     * 注意：此方法仅在未配置 baseUrl 时使用
     * 如果配置了 baseUrl（中转站），会自动使用 OpenAI 兼容接口
     */
    private ChatLanguageModel createGeminiModel(ModelConfig config) {
        var builder = GoogleAiGeminiChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .maxRetries(config.getMaxRetries());

        return builder.build();
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
        // 考虑动态 API Key
        String effectiveApiKey = dynamicApiKeyService != null
                ? dynamicApiKeyService.getEffectiveApiKey(config.getApiKey())
                : config.getApiKey();
        return effectiveApiKey != null && !effectiveApiKey.isBlank()
                && config.getModelName() != null && !config.getModelName().isBlank();
    }

    public java.util.Set<String> getAvailableModels() {
        return llmProperties.getModels().keySet();
    }
}