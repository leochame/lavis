package com.lavis.config.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * LLM 配置属性类
 * 
 * 读取 application.yml 中的 app.llm.models 配置
 * 
 * 配置示例:
 * <pre>
 * app:
 *   llm:
 *     models:
 *       fast-model:
 *         provider: OPENAI
 *         base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *         api-key: ${ALIYUN_KEY}
 *         model-name: qwen-max
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {
    
    /**
     * 模型配置映射
     * Key: 模型别名 (如 fast-model)
     * Value: 模型配置
     */
    private Map<String, ModelConfig> models = new HashMap<>();
    
    /**
     * 默认模型别名
     * 当调用方未指定模型时使用
     */
    private String defaultModel = "default";

    /**
     * 默认 STT 模型别名
     */
    private String defaultSttModel = "whisper";

    /**
     * 默认 TTS 模型别名
     */
    private String defaultTtsModel = "tts";
    
    /**
     * 获取指定别名的模型配置
     * 
     * @param alias 模型别名
     * @return 模型配置，如果不存在则返回 null
     */
    public ModelConfig getModelConfig(String alias) {
        return models.get(alias);
    }
    
    /**
     * 检查是否存在指定别名的模型配置
     */
    public boolean hasModel(String alias) {
        return models.containsKey(alias);
    }
}

