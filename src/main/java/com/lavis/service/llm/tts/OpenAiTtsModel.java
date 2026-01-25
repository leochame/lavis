package com.lavis.service.llm.tts;

import com.lavis.config.llm.ModelConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI TTS 实现
 * 
 * 支持 OpenAI TTS API 和兼容接口（如阿里云 DashScope）
 * API 文档: https://platform.openai.com/docs/api-reference/audio/createSpeech
 */
@Slf4j
public class OpenAiTtsModel implements TtsModel {

    private final ModelConfig config;
    private final OkHttpClient httpClient;

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String TTS_API_ENDPOINT = "/audio/speech";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    public OpenAiTtsModel(ModelConfig config) {
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
            log.info("🎙️ Starting TTS for text ({}chars), model: {}, voice: {}", 
                text.length(), config.getModelName(), config.getVoice());

            // Determine API URL (support custom base URL)
            String baseUrl = DEFAULT_BASE_URL;
            if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
                baseUrl = config.getBaseUrl();
            }
            
            // Construct full API endpoint URL
            String apiUrl = baseUrl;
            if (!apiUrl.endsWith("/")) {
                apiUrl += "/";
            }
            // Only append endpoint if it's not already in the URL
            if (!apiUrl.contains(TTS_API_ENDPOINT.substring(1))) {
                apiUrl += TTS_API_ENDPOINT.substring(1); // Remove leading slash since we already added one
            }

            // 构建 JSON 请求体 (OpenAI TTS API 需要 JSON 格式)
            String voice = config.getVoice() != null ? config.getVoice() : "alloy";
            String format = config.getFormat() != null ? config.getFormat() : "mp3";
            
            // 转义文本中的特殊字符
            String escapedText = escapeJson(text);
            
            String jsonBody = String.format(
                "{\"model\":\"%s\",\"input\":\"%s\",\"voice\":\"%s\",\"response_format\":\"%s\"}",
                config.getModelName(),
                escapedText,
                voice,
                format
            );

            RequestBody requestBody = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No error body";
                    log.error("TTS API failed: {} - {}", response.code(), errorBody);
                    throw new IOException("TTS generation failed: " + response.code() + " - " + errorBody);
                }

                // 获取音频数据
                byte[] audioBytes = response.body().bytes();
                log.info("✅ TTS audio generated successfully, size: {} bytes", audioBytes.length);

                // 转换为 Base64
                return java.util.Base64.getEncoder().encodeToString(audioBytes);
            }

        } catch (IOException e) {
            log.error("Text to speech failed", e);
            throw new RuntimeException("Failed to generate speech: " + e.getMessage(), e);
        }
    }

    /**
     * 转义 JSON 字符串中的特殊字符
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    @Override
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }
}
