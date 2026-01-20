package com.lavis.service.llm.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lavis.config.llm.ModelConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云 DashScope 原生 TTS 实现
 * 文档: https://help.aliyun.com/zh/model-studio/developer-reference/api-details
 */
@Slf4j
public class DashScopeTtsModel implements TtsModel {

    private final ModelConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 阿里云语音合成 API 地址
    private static final String DEFAULT_API_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/synthesis";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    public DashScopeTtsModel(ModelConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String textToSpeech(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text is required");
        }

        try {
            log.info("🎙️ Starting DashScope TTS for text ({} chars), model: {}, voice: {}", 
                text.length(), config.getModelName(), config.getVoice());

            String apiUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank() 
                    ? config.getBaseUrl() : DEFAULT_API_URL;

            // 1. 构建请求体 JSON
            // 结构: {"model": "...", "input": {"text": "..."}, "parameters": {"voice": "...", "format": "..."}}
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", config.getModelName());
            
            ObjectNode input = root.putObject("input");
            input.put("text", text);
            
            ObjectNode parameters = root.putObject("parameters");
            // 使用配置中的 voice，如果没有则默认 (如 'longxiaochun')
            parameters.put("text_type", "PlainText");
            parameters.put("format", config.getFormat() != null ? config.getFormat() : "mp3");
            
            // 部分模型 (如 CosyVoice) 使用 'voice' 参数，部分旧模型使用其他参数
            // 这里适配通用的 DashScope 格式
            if (config.getVoice() != null && !config.getVoice().isBlank()) {
                parameters.put("voice", config.getVoice()); 
            }

            RequestBody requestBody = RequestBody.create(root.toString(), JSON_MEDIA_TYPE);

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            // 2. 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("❌ DashScope TTS failed: {} - {}", response.code(), errorBody);
                    throw new IOException("TTS failed: " + response.code() + " - " + errorBody);
                }

                // 3. 处理响应
                // 成功时 Content-Type 通常是 audio/mpeg 等
                byte[] audioBytes = response.body().bytes();
                log.info("✅ TTS audio generated successfully, size: {} bytes", audioBytes.length);

                return Base64.getEncoder().encodeToString(audioBytes);
            }

        } catch (IOException e) {
            log.error("TTS generation failed", e);
            throw new RuntimeException("Failed to generate speech: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }
}