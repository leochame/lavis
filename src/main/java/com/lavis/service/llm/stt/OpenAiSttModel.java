package com.lavis.service.llm.stt;

import com.lavis.config.llm.ModelConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Whisper STT 实现
 */
@Slf4j
public class OpenAiSttModel implements SttModel {

    private final ModelConfig config;
    private final OkHttpClient httpClient;
    
    // OpenAI 官方地址: https://api.openai.com/v1
    // 如果配置文件中没有指定 base-url，则使用官方地址
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String WHISPER_API_ENDPOINT = "/audio/transcriptions";

    public OpenAiSttModel(ModelConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String transcribe(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new IllegalArgumentException("Audio file is required");
        }

        try {
            log.info("Starting Whisper transcription for file: {}, model: {}", 
                audioFile.getOriginalFilename(), config.getModelName());

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
            String endpointPath = WHISPER_API_ENDPOINT.substring(1); // Remove leading slash
            if (!apiUrl.contains(endpointPath)) {
                apiUrl += endpointPath;
            }
            
            log.info("🔗 STT API URL: {}", apiUrl);
            log.info("🔑 Using API Key prefix: {}...", 
                config.getApiKey() != null && config.getApiKey().length() > 10 
                    ? config.getApiKey().substring(0, 10) : "null");

            // 创建 multipart 请求
            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.getOriginalFilename(),
                            RequestBody.create(audioFile.getBytes()))
                    .addFormDataPart("model", config.getModelName())
                    .addFormDataPart("response_format", "json")
                    .build();

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                    .post(requestBody)
                    .build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No error body";
                    log.error("❌ Whisper API failed: {} - URL: {}", response.code(), apiUrl);
                    log.error("❌ Error response body: {}", errorBody);
                    throw new IOException("Whisper transcription failed: " + response.code() + " - " + errorBody);
                }

                // 解析响应
                String responseBody = response.body().string();
                log.info("Whisper response: {}", responseBody);

                // 简单解析 JSON 响应
                if (responseBody.contains("\"text\"")) {
                    int textStart = responseBody.indexOf("\"text\": \"") + 8;
                    int textEnd = responseBody.indexOf("\"", textStart);
                    if (textEnd > textStart) {
                        // Handle escaped characters if necessary, but simple substring for now
                        String text = responseBody.substring(textStart, textEnd);
                        // Unescape common JSON escapes if needed (e.g. \n, \")
                        return text.replace("\\n", "\n").replace("\\\"", "\"");
                    }
                }

                return responseBody;
            }

        } catch (IOException e) {
            log.error("Transcription failed", e);
            throw new RuntimeException("Failed to transcribe audio: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }
}
