package com.lavis;

import com.lavis.service.llm.LlmFactory;
import com.lavis.config.llm.LlmProperties;
import com.lavis.service.llm.stt.SttModel;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 STT 超时配置是否正确加载
 */
@Slf4j
@SpringBootTest
@TestPropertySource(locations = "classpath:application.properties")
public class SttTimeoutConfigTest {

    @Autowired
    private LlmProperties llmProperties;

    @Autowired
    private LlmFactory llmFactory;

    @Test
    public void testSttTimeoutConfig() {
        log.info("=== 测试 STT 超时配置 ===");
        
        // 1. 验证配置是否正确加载
        var whisperConfig = llmProperties.getModelConfig("whisper");
        assertNotNull(whisperConfig, "whisper 模型配置应该存在");
        
        Integer timeoutSeconds = whisperConfig.getTimeoutSeconds();
        log.info("配置的超时时间: {} 秒", timeoutSeconds);
        
        // 2. 验证超时时间是否为 60 秒
        assertEquals(60, timeoutSeconds, "STT 超时时间应该为 60 秒");
        
        // 3. 验证 STT 模型是否正确初始化
        SttModel sttModel = llmFactory.getSttModel();
        assertNotNull(sttModel, "STT 模型应该存在");
        assertTrue(sttModel.isAvailable(), "STT 模型应该可用");
        
        log.info("✅ STT 超时配置测试通过: {} 秒", timeoutSeconds);
    }

    @Test
    public void testSttModelType() {
        log.info("=== 测试 STT 模型类型 ===");
        
        var whisperConfig = llmProperties.getModelConfig("whisper");
        assertNotNull(whisperConfig);
        
        log.info("模型类型: {}", whisperConfig.getType());
        log.info("提供商: {}", whisperConfig.getProvider());
        log.info("模型名称: {}", whisperConfig.getModelName());
        log.info("Base URL: {}", whisperConfig.getBaseUrl());
        log.info("超时时间: {} 秒", whisperConfig.getTimeoutSeconds());
        log.info("最大重试次数: {}", whisperConfig.getMaxRetries());
        
        assertEquals(com.lavis.config.llm.ModelConfig.ModelType.STT, whisperConfig.getType());
        assertEquals(com.lavis.config.llm.ModelConfig.Provider.GEMINI, whisperConfig.getProvider());
    }
}

