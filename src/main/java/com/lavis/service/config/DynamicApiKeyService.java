package com.lavis.service.config;

import com.lavis.config.llm.ModelConfig;
import com.lavis.service.llm.LlmFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 动态 API 配置管理服务
 *
 * 管理运行时 API Key 和 Base URL：
 * - 优先使用用户设置的配置
 * - 回退到环境变量/配置文件中的配置
 * - 提供配置变更时清除模型缓存的功能
 *
 * 支持两种模式：
 * 1. Gemini 官方 - 不设置 baseUrl，使用官方 API
 * 2. 中转站模式 - 设置自定义 baseUrl
 */
@Slf4j
@Service
public class DynamicApiKeyService {

    private final AtomicReference<String> dynamicApiKey = new AtomicReference<>(null);
    private final AtomicReference<String> dynamicBaseUrl = new AtomicReference<>(null);
    /**
     * 运行时模型名称配置
     * 目前按模型类型区分：
     * - CHAT:   主对话模型（对应 fast-model 等 alias）
     * - STT:    语音转文字模型（对应 whisper alias）
     * - TTS:    文字转语音模型（对应 tts alias）
     *
     * 注意：这里只存“模型名称”，具体 alias 仍由配置文件中的 app.llm.models.* 控制
     */
    private final AtomicReference<String> dynamicChatModelName = new AtomicReference<>(null);
    private final AtomicReference<String> dynamicSttModelName = new AtomicReference<>(null);
    private final AtomicReference<String> dynamicTtsModelName = new AtomicReference<>(null);
    private final LlmFactory llmFactory;

    public DynamicApiKeyService(@Lazy LlmFactory llmFactory) {
        this.llmFactory = llmFactory;
    }

    /**
     * 设置动态 API Key
     *
     * @param apiKey 用户提供的 API Key
     */
    public void setApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key cannot be empty");
        }

        String oldKey = dynamicApiKey.get();
        dynamicApiKey.set(apiKey);

        // 如果 API Key 发生变化，清除模型缓存
        if (oldKey == null || !oldKey.equals(apiKey)) {
            log.info("🔑 API Key updated, clearing model cache");
            llmFactory.clearCache();
        }

        log.info("✅ Dynamic API Key set successfully (prefix: {}...)",
                apiKey.length() > 10 ? apiKey.substring(0, 10) : apiKey);
    }

    /**
     * 设置运行时 Chat 模型名称（如 gemini-2.0-flash、qwen-max 等）
     */
    public void setChatModelName(String modelName) {
        String trimmed = modelName != null ? modelName.trim() : null;
        String old = dynamicChatModelName.get();
        dynamicChatModelName.set(trimmed);

        if ((old == null && trimmed != null) || (old != null && !old.equals(trimmed))) {
            log.info("🧠 Chat model-name updated to: {}", trimmed);
            llmFactory.clearCache();
        }
    }

    /**
     * 设置运行时 STT 模型名称（对应 whisper 等语音识别模型）
     */
    public void setSttModelName(String modelName) {
        String trimmed = modelName != null ? modelName.trim() : null;
        String old = dynamicSttModelName.get();
        dynamicSttModelName.set(trimmed);

        if ((old == null && trimmed != null) || (old != null && !old.equals(trimmed))) {
            log.info("🗣 STT model-name updated to: {}", trimmed);
            llmFactory.clearCache();
        }
    }

    /**
     * 设置运行时 TTS 模型名称（对应 tts 等语音合成模型）
     */
    public void setTtsModelName(String modelName) {
        String trimmed = modelName != null ? modelName.trim() : null;
        String old = dynamicTtsModelName.get();
        dynamicTtsModelName.set(trimmed);

        if ((old == null && trimmed != null) || (old != null && !old.equals(trimmed))) {
            log.info("🔊 TTS model-name updated to: {}", trimmed);
            llmFactory.clearCache();
        }
    }

    /**
     * 设置动态 Base URL（中转站地址）
     *
     * @param baseUrl 用户提供的 Base URL，为空则使用 Gemini 官方
     */
    public void setBaseUrl(String baseUrl) {
        String oldUrl = dynamicBaseUrl.get();

        // 允许设置为空（表示使用 Gemini 官方）
        String newUrl = (baseUrl == null || baseUrl.isBlank()) ? null : baseUrl.trim();
        dynamicBaseUrl.set(newUrl);

        // 如果 Base URL 发生变化，清除模型缓存
        boolean changed = (oldUrl == null && newUrl != null) ||
                         (oldUrl != null && !oldUrl.equals(newUrl));
        if (changed) {
            log.info("🔗 Base URL updated, clearing model cache");
            llmFactory.clearCache();
        }

        if (newUrl != null) {
            log.info("✅ Dynamic Base URL set: {}", newUrl);
        } else {
            log.info("✅ Using Gemini official API (no custom base URL)");
        }
    }

    /**
     * 获取当前动态 API Key
     *
     * @return 用户设置的 API Key，如果未设置则返回 null
     */
    public String getApiKey() {
        return dynamicApiKey.get();
    }

    /**
     * 获取当前动态 Base URL
     *
     * @return 用户设置的 Base URL，如果未设置则返回 null（表示使用 Gemini 官方）
     */
    public String getBaseUrl() {
        return dynamicBaseUrl.get();
    }

    public String getChatModelName() {
        return dynamicChatModelName.get();
    }

    public String getSttModelName() {
        return dynamicSttModelName.get();
    }

    public String getTtsModelName() {
        return dynamicTtsModelName.get();
    }

    /**
     * 获取有效的模型名称
     * - 如果用户通过设置面板配置了对应类型的模型名称，则优先使用运行时配置
     * - 否则回退到配置文件中的 model-name
     */
    public String getEffectiveModelName(String configModelName, ModelConfig.ModelType type) {
        String override = switch (type) {
            case CHAT -> dynamicChatModelName.get();
            case STT -> dynamicSttModelName.get();
            case TTS -> dynamicTtsModelName.get();
        };
        if (override != null && !override.isBlank()) {
            return override;
        }
        return configModelName;
    }

    /**
     * 获取有效的 API Key
     * 优先返回动态设置的 Key，如果没有则返回配置文件中的 Key
     *
     * @param configApiKey 配置文件中的 API Key
     * @return 有效的 API Key
     */
    public String getEffectiveApiKey(String configApiKey) {
        String dynamicKey = dynamicApiKey.get();
        if (dynamicKey != null && !dynamicKey.isBlank()) {
            return dynamicKey;
        }
        return configApiKey;
    }

    /**
     * 获取有效的 Base URL
     * 优先返回动态设置的 URL，如果没有则返回配置文件中的 URL
     * 如果都没有，返回 null（表示使用 Gemini 官方 API）
     *
     * @param configBaseUrl 配置文件中的 Base URL
     * @return 有效的 Base URL，null 表示使用 Gemini 官方
     */
    public String getEffectiveBaseUrl(String configBaseUrl) {
        String dynamicUrl = dynamicBaseUrl.get();
        // 动态设置优先（包括显式设置为空的情况）
        // 如果用户设置了动态配置（API Key 或 Base URL），则使用用户设置的 Base URL
        if (dynamicApiKey.get() != null || dynamicUrl != null) {
            // 如果用户设置了 API Key，则使用用户设置的 Base URL（可能为 null）
            return dynamicUrl;
        }
        // 否则使用配置文件中的 Base URL
        return configBaseUrl;
    }

    /**
     * 清除动态配置（API Key 和 Base URL）
     */
    public void clearConfig() {
        String oldKey = dynamicApiKey.get();
        String oldUrl = dynamicBaseUrl.get();
        String oldChatModel = dynamicChatModelName.get();
        String oldSttModel = dynamicSttModelName.get();
        String oldTtsModel = dynamicTtsModelName.get();

        dynamicApiKey.set(null);
        dynamicBaseUrl.set(null);
        dynamicChatModelName.set(null);
        dynamicSttModelName.set(null);
        dynamicTtsModelName.set(null);

        if (oldKey != null || oldUrl != null || oldChatModel != null || oldSttModel != null || oldTtsModel != null) {
            log.info("🔑 Dynamic config cleared (apiKey/baseUrl/model-name), clearing model cache");
            llmFactory.clearCache();
        }

        log.info("✅ Dynamic config cleared");
    }

    /**
     * 清除动态 API Key
     */
    public void clearApiKey() {
        clearConfig();
    }

    /**
     * 检查是否已配置动态 API Key
     */
    public boolean isConfigured() {
        String key = dynamicApiKey.get();
        return key != null && !key.isBlank();
    }

    /**
     * 检查是否使用中转站模式
     */
    public boolean isUsingProxy() {
        String url = dynamicBaseUrl.get();
        return url != null && !url.isBlank();
    }
}
