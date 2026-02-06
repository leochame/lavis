package com.lavis.service.config;

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
        if (dynamicApiKey.get() != null) {
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

        dynamicApiKey.set(null);
        dynamicBaseUrl.set(null);

        if (oldKey != null || oldUrl != null) {
            log.info("🔑 Dynamic config cleared, clearing model cache");
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
