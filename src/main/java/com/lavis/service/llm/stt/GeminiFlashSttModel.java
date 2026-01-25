package com.lavis.service.llm.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lavis.config.llm.ModelConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Gemini-flash STT (Speech-to-Text) 实现
 * 
 * 使用 Gemini generateContent API 进行音频识别
 * 支持通过 inlineData base64 方式上传音频
 * 
 * 根据官方文档，Gemini 支持音频理解，需要：
 * 1. 添加文本提示（prompt）明确要求转录，例如 "Generate a transcript of the speech."
 * 2. 支持多种音频格式：WAV, MP3, AIFF, AAC, OGG, FLAC
 * 
 * API 文档: 
 * - 官方文档: https://ai.google.dev/gemini-api/docs/audio
 * - 中转站文档: https://docs.newapi.pro/zh/docs/api/ai-model/chat/gemini/geminirelayv1beta-391536411
 */
@Slf4j             
public class GeminiFlashSttModel implements SttModel {

    private final ModelConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Gemini API 端点
    // 官方地址: https://generativelanguage.googleapis.com/v1beta
    // 如果配置文件中没有指定 base-url，则使用官方地址
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final String GEMINI_API_PATH = "/models/%s:generateContent";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    public GeminiFlashSttModel(ModelConfig config) {
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
            log.info("🎤 Starting Gemini-flash STT for file: {}, model: {}",
                    audioFile.getOriginalFilename(), config.getModelName());

            // 1. 确定 API URL（优先使用配置文件中的 base-url）
            String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                    ? config.getBaseUrl() : DEFAULT_BASE_URL;
            
            // 确保 baseUrl 不以 / 结尾
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            
            // 构建完整的 API URL
            // 官方地址格式: https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
            // 中转站格式（根据错误信息，应该是）: https://api.jieai.shop/v1beta/models/{model}:generateContent
            // 判断是否为官方地址
            boolean isOfficialUrl = baseUrl.contains("generativelanguage.googleapis.com");
            String apiUrl;
            if (isOfficialUrl) {
                // 官方地址已经包含 /v1beta，直接拼接 /models/...
                apiUrl = baseUrl + String.format(GEMINI_API_PATH, config.getModelName());
            } else {
                // 中转站处理：如果 baseUrl 包含 /v1，需要移除它，因为中转站期望的是 /v1beta 而不是 /v1/v1beta
                // 例如：https://api.jieai.shop/v1 -> https://api.jieai.shop/v1beta/models/...
                if (baseUrl.endsWith("/v1")) {
                    // 移除 /v1，然后添加 /v1beta
                    String baseWithoutV1 = baseUrl.substring(0, baseUrl.length() - 3); // 移除 "/v1"
                    apiUrl = baseWithoutV1 + "/v1beta" + String.format(GEMINI_API_PATH, config.getModelName());
                } else {
                    // baseUrl 不包含 /v1，直接添加 /v1beta
                    apiUrl = baseUrl + "/v1beta" + String.format(GEMINI_API_PATH, config.getModelName());
                }
            }
            log.info("🔗 Gemini STT API URL: {}", apiUrl);
            log.info("🔑 Using API Key prefix: {}...",
                    config.getApiKey() != null && config.getApiKey().length() > 10
                            ? config.getApiKey().substring(0, 10) : "null");

            // 2. 将音频文件转换为 Base64
            byte[] audioBytes = audioFile.getBytes();
            String audioBase64 = Base64.getEncoder().encodeToString(audioBytes);
            String mimeType = getAudioMimeType(audioFile.getOriginalFilename());
            
            log.info("📁 Audio MIME type: {}, size: {} bytes", mimeType, audioBytes.length);

            // 3. 构建 Gemini generateContent 请求体
            // 根据官方文档，需要添加文本提示来明确要求转录
            // 格式参考: https://ai.google.dev/gemini-api/docs/audio
            // {
            //   "contents": [{
            //     "parts": [
            //       {"text": "Generate a transcript of the speech."},
            //       {
            //         "inlineData": {
            //           "mimeType": "audio/mp3",
            //           "data": "base64_encoded_audio"
            //         }
            //       }
            //     ]
            //   }]
            // }
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode contents = root.putArray("contents");
            ObjectNode content = contents.addObject();
            ArrayNode parts = content.putArray("parts");
            
            // 添加文本提示，明确要求转录（根据官方文档要求）
            ObjectNode textPart = parts.addObject();
            textPart.put("text", "Generate a transcript of the speech.");
            
            // 添加音频数据
            ObjectNode audioPart = parts.addObject();
            ObjectNode inlineData = audioPart.putObject("inlineData");
            inlineData.put("mimeType", mimeType);
            inlineData.put("data", audioBase64);

            String requestJson = root.toString();
            log.debug("📤 Request body length: {} chars", requestJson.length());

            RequestBody requestBody = RequestBody.create(requestJson, JSON_MEDIA_TYPE);

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            // 4. 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.error("❌ Gemini STT API failed: {} - URL: {}", response.code(), apiUrl);
                    log.error("❌ Error response body: {}", responseBody);
                    throw new IOException("Gemini STT transcription failed: " + response.code() + " - " + responseBody);
                }

                log.debug("📝 Gemini response: {}", responseBody);
                return parseGeminiResponse(responseBody);
            }

        } catch (IOException e) {
            log.error("Transcription failed", e);
            throw new RuntimeException("Failed to transcribe audio: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 Gemini generateContent 响应
     * 响应格式示例:
     * {
     *   "candidates": [{
     *     "content": {
     *       "parts": [{
     *         "text": "转录的文本内容"
     *       }]
     *     }
     *   }]
     * }
     */
    private String parseGeminiResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 检查是否有错误
            if (root.has("error")) {
                JsonNode error = root.get("error");
                String errorMessage = error.has("message") ? error.get("message").asText() : "Unknown error";
                throw new RuntimeException("Gemini API error: " + errorMessage);
            }

            // 解析响应内容
            if (root.has("candidates") && root.get("candidates").isArray()) {
                JsonNode candidates = root.get("candidates");
                if (candidates.size() > 0) {
                    JsonNode firstCandidate = candidates.get(0);
                    if (firstCandidate.has("content")) {
                        JsonNode content = firstCandidate.get("content");
                        if (content.has("parts") && content.get("parts").isArray()) {
                            JsonNode parts = content.get("parts");
                            StringBuilder result = new StringBuilder();
                            for (JsonNode part : parts) {
                                if (part.has("text")) {
                                    result.append(part.get("text").asText());
                                }
                            }
                            String text = result.toString().trim();
                            log.info("✅ Transcription successful: {} chars", text.length());
                            return text;
                        }
                    }
                }
            }

            log.warn("⚠️ Could not parse Gemini response, returning raw response");
            return responseBody;

        } catch (IOException e) {
            log.error("Failed to parse Gemini response", e);
            return responseBody;
        }
    }

    /**
     * 根据文件名获取音频 MIME 类型
     * 
     * 根据官方文档，Gemini 支持的音频格式：
     * - WAV: audio/wav
     * - MP3: audio/mp3
     * - AIFF: audio/aiff
     * - AAC: audio/aac
     * - OGG Vorbis: audio/ogg
     * - FLAC: audio/flac
     * 
     * 参考: https://ai.google.dev/gemini-api/docs/audio#supported-audio-formats
     */
    private String getAudioMimeType(String filename) {
        if (filename == null) {
            return "audio/wav"; // 默认格式（官方文档推荐）
        }
        String lowerName = filename.toLowerCase();
        // 官方文档支持的格式
        if (lowerName.endsWith(".wav")) return "audio/wav";
        if (lowerName.endsWith(".mp3")) return "audio/mp3";  // 文档使用 audio/mp3
        if (lowerName.endsWith(".aiff")) return "audio/aiff";
        if (lowerName.endsWith(".aac")) return "audio/aac";
        if (lowerName.endsWith(".ogg")) return "audio/ogg";
        if (lowerName.endsWith(".flac")) return "audio/flac";
        
        // 额外支持的格式（可能也兼容）
        if (lowerName.endsWith(".webm")) return "audio/webm";  // 浏览器常用，可能兼容
        if (lowerName.endsWith(".m4a")) return "audio/mp4";   // M4A 可能兼容
        if (lowerName.endsWith(".opus")) return "audio/ogg";   // Opus 通常使用 OGG 容器
        
        // 默认返回 wav（官方文档推荐格式）
        return "audio/wav";
    }

    @Override
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }
}

