package com.lavis.service.config;

import com.lavis.service.llm.LlmFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 动态 API Key 管理服务
 *
 * 管理运行时 API Key：
 * - 优先使用用户设置的 API Key
 * - 回退到环境变量/配置文件中的 API Key
 * - 提供 API Key 变更时清除模型缓存的功能
 */
@Slf4j
@Service
public class DynamicApiKeyService {

    private final AtomicReference<String> dynamicApiKey = new AtomicReference<>(null);
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
     * 获取当前动态 API Key
     *
     * @return 用户设置的 API Key，如果未设置则返回 null
     */
    public String getApiKey() {
        return dynamicApiKey.get();
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
     * 清除动态 API Key
     */
    public void clearApiKey() {
        String oldKey = dynamicApiKey.get();
        dynamicApiKey.set(null);

        if (oldKey != null) {
            log.info("🔑 Dynamic API Key cleared, clearing model cache");
            llmFactory.clearCache();
        }

        log.info("✅ Dynamic API Key cleared");
    }

    /**
     * 检查是否已配置动态 API Key
     */
    public boolean isConfigured() {
        String key = dynamicApiKey.get();
        return key != null && !key.isBlank();
    }
}
