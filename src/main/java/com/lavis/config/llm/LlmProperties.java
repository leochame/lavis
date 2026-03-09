package com.lavis.config.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * LLM configuration属性类
 * 
 * 读取 application.yml 中的 app.llm.models configuration
 * 
 * configuration示例:
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
     * 模型configuration映射
     * Key: 模型别名 (如 fast-model)
     * Value: 模型configuration
     */
    private Map<String, ModelConfig> models = new HashMap<>();
    
    /**
     * 默认模型别名
     * when调用方not 指定模型时使用
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
     * 获取指定别名的模型configuration
     * 
     * @param alias 模型别名
     * @return 模型configuration，if不存在则返回 null
     */
    public ModelConfig getModelConfig(String alias) {
        return models.get(alias);
    }
    
    /**
     * 检查是否存在指定别名的模型configuration
     */
    public boolean hasModel(String alias) {
        return models.containsKey(alias);
    }
}

