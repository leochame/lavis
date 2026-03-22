package com.lavis.infra.llm.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lavis.entry.config.llm.ModelConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Gemini-flash STT (Speech-to-Text) 实现
 * 
 * 使用 Gemini generateContent API 进行音频识别
 * 支持通过 inlineData base64 方式上传音频
 *
 * 根据官方文档，Gemini 支持音频理解，需要：
 * 1. 添加文本提示（prompt）明确要求转录，for example "Transcribe the audio. Output only the exact words spoken, nothing else."
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
        // 配置连接池和超时设置，提高网络稳定性
        // 针对连接重置问题，采用以下策略：
        // 1. 减少连接池保持时间，避免复用已失效的连接
        // 2. 启用连接失败重试
        // 3. 设置合理的超时时间
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(5, 30, TimeUnit.SECONDS)) // 连接池：最多5个连接，保持30秒（减少保持时间，避免复用失效连接）
                .retryOnConnectionFailure(true) // 启用连接失败重试
                .protocols(java.util.Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)) // 明确支持的协议
                .build();
    }

    @Override
    public String transcribe(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new IllegalArgumentException("Audio file is required");
        }

        long totalStartTime = System.currentTimeMillis();
        try {
            long fileSize = audioFile.getSize();
            log.info("🎤 Starting Gemini-flash STT for file: {} ({} bytes, {} MB), model: {}",
                    audioFile.getOriginalFilename(), fileSize, 
                    String.format("%.2f", fileSize / (1024.0 * 1024.0)), config.getModelName());

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
                // for example：https://api.jieai.shop/v1 -> https://api.jieai.shop/v1beta/models/...
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
            long encodeStartTime = System.currentTimeMillis();
            byte[] audioBytes = audioFile.getBytes();
            String audioBase64 = Base64.getEncoder().encodeToString(audioBytes);
            long encodeDuration = System.currentTimeMillis() - encodeStartTime;
            String mimeType = getAudioMimeType(audioFile);
            
            // 计算 Base64 编码后的数据大小（用于诊断）
            int base64Size = audioBase64.length();
            double sizeMB = audioBytes.length / (1024.0 * 1024.0);
            double base64SizeMB = base64Size / (1024.0 * 1024.0);
            
            log.info("📁 Audio MIME type: {}, original size: {} MB ({} bytes), base64 size: {} MB ({} chars), encode time: {}ms", 
                    mimeType, String.format("%.2f", sizeMB), audioBytes.length, 
                    String.format("%.2f", base64SizeMB), base64Size, encodeDuration);
            
            // 注意：如果文件太大，可能会触发 Cloudflare 524 超时
            if (sizeMB > 10) {
                log.warn(" Large audio file detected ({} MB). This may cause timeout issues (524 error).", 
                        String.format("%.2f", sizeMB));
                log.warn("   Consider: splitting the audio, using a shorter clip, or compressing the audio.");
            } else if (sizeMB > 5) {
                log.warn(" Audio file is moderately large ({} MB). Response time may be slower.", 
                        String.format("%.2f", sizeMB));
            } else if (sizeMB > 1) {
                log.info("ℹ️ Audio file size: {} MB - Expected processing time: ~{}s", 
                        String.format("%.2f", sizeMB), 
                        String.format("%.1f", sizeMB * 2)); // 粗略估算：每MB约2秒
            }

            // 3. 构建 Gemini generateContent 请求体
            // 根据官方文档，需要添加文本提示来明确要求转录
            // 格式参考: https://ai.google.dev/gemini-api/docs/audio
            // {
            //   "contents": [{
            //     "parts": [
            //       {"text": "Transcribe the audio. Output only the exact words spoken, nothing else."},
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
            // 使用更明确的指令，避免模型误解为"生成内容"而非"转录语音"
            ObjectNode textPart = parts.addObject();
            textPart.put("text", "Transcribe the audio. Output only the exact words spoken, nothing else.");
            
            // 添加音频数据
            ObjectNode audioPart = parts.addObject();
            ObjectNode inlineData = audioPart.putObject("inlineData");
            inlineData.put("mimeType", mimeType);
            inlineData.put("data", audioBase64);

            String requestJson = root.toString();
            log.debug("📤 Request body length: {} chars", requestJson.length());

            RequestBody requestBody = RequestBody.create(requestJson, JSON_MEDIA_TYPE);

            // 构建请求，添加必要的请求头以避免连接重置问题
            // 1. User-Agent: 某些代理服务要求此头部
            // 2. Accept: 明确接受的响应类型
            // 3. Connection: 对于大请求，使用 close 避免连接复用问题
            Request.Builder requestBuilder = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("User-Agent", "Lavis-Agent/1.0 (Java/OkHttp)")
                    .post(requestBody);
            
            // 对于大请求（>100KB），使用 Connection: close 避免连接复用导致的问题
            if (base64SizeMB > 0.1) {
                requestBuilder.addHeader("Connection", "close");
                log.debug(" Large request detected ({} MB), using Connection: close", String.format("%.2f", base64SizeMB));
            }
            
            Request request = requestBuilder.build();

            // 4. 发送请求（带重试逻辑）
            int maxRetries = config.getMaxRetries() != null ? config.getMaxRetries() : 3;
            IOException lastException = null;
            long requestDuration = 0;
            
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    if (attempt > 0) {
                        // 指数退避：1s, 2s, 4s
                        long backoffMs = (long) Math.pow(2, attempt - 1) * 1000;
                        log.warn(" Retrying Gemini STT request (attempt {}/{}) after {}ms", 
                                attempt, maxRetries, backoffMs);
                        Thread.sleep(backoffMs);
                    }

                    long requestStartTime = System.currentTimeMillis();
                    try (Response response = httpClient.newCall(request).execute()) {
                        requestDuration = System.currentTimeMillis() - requestStartTime;
                        log.info("🌐 API request completed in {}ms ({}s)", 
                                requestDuration, String.format("%.2f", requestDuration / 1000.0));
                        String responseBody = response.body() != null ? response.body().string() : "";

                        if (!response.isSuccessful()) {
                            int statusCode = response.code();
                            
                            // HTTP error码：4xx 不重试（除了 524），5xx 重试
                            if (statusCode >= 400 && statusCode < 500) {
                                log.error(" Gemini STT API failed (client error): {} - URL: {}", statusCode, apiUrl);
                                log.error(" Error response body: {}", responseBody);
                                throw new IOException("Gemini STT transcription failed: " + statusCode + " - " + responseBody);
                            } else {
                            // 5xx 服务器错误，可以重试
                                log.warn(" Gemini STT API server error: {} (attempt {}/{}) - URL: {}", 
                                        statusCode, attempt + 1, maxRetries + 1, apiUrl);
                                log.warn(" Error response body: {}", responseBody);
                                
                                // 对于 500 错误，尝试解析错误信息
                                String errorMessage = parseErrorMessage(responseBody, statusCode);
                                
                                if (attempt < maxRetries) {
                                    lastException = new IOException("Server error: " + statusCode + " - " + errorMessage);
                                    continue;
                                } else {
                                    // 所有重试都失败，抛出友好的错误消息
                                    String friendlyMessage = String.format(
                                        "语音识别服务暂时不可用（服务器错误 %d）。错误信息：%s。请稍后重试。",
                                        statusCode, errorMessage
                                    );
                                    throw new IOException("Gemini STT transcription failed: " + statusCode + " - " + errorMessage);
                                }
                            }
                        }

                        log.debug("📝 Gemini response: {}", responseBody);
                        long parseStartTime = System.currentTimeMillis();
                        String transcribedText = parseGeminiResponse(responseBody);
                        long parseDuration = System.currentTimeMillis() - parseStartTime;
                        long totalDuration = System.currentTimeMillis() - totalStartTime;
                        log.info("⏱️ Total STT processing time: {}ms ({}s) - Breakdown: encode={}ms, network={}ms, parse={}ms", 
                                totalDuration, String.format("%.2f", totalDuration / 1000.0),
                                encodeDuration, requestDuration, parseDuration);
                        return transcribedText;
                    }
                } catch (SocketException | SocketTimeoutException e) {
                    // 连接错误：可以重试
                    lastException = e;
                    if (attempt < maxRetries) {
                        log.warn(" Connection error (attempt {}/{}): {} - {}", 
                                attempt + 1, maxRetries, e.getClass().getSimpleName(), e.getMessage());
                        // 对于连接重置，在重试前清除连接池中的连接
                        if (e.getMessage() != null && e.getMessage().contains("reset")) {
                            log.debug(" Clearing connection pool due to connection reset");
                            httpClient.connectionPool().evictAll(); // 清除所有连接
                        }
                        continue;
                    } else {
                        log.error(" Connection failed after {} attempts: {} - {}", 
                                maxRetries + 1, e.getClass().getSimpleName(), e.getMessage());
                        throw e;
                    }
                } catch (IOException e) {
                    // 其他 IO 错误：检查是否可重试
                    String errorMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    String errorClass = e.getClass().getSimpleName();
                    boolean isRetryable = errorMsg.contains("connection") || 
                                         errorMsg.contains("reset") || 
                                         errorMsg.contains("timeout") ||
                                         errorMsg.contains("broken pipe") ||
                                         errorMsg.contains("handshake");
                    
                    if (isRetryable && attempt < maxRetries) {
                        lastException = e;
                        log.warn(" Network error (attempt {}/{}): {} - {}", 
                                attempt + 1, maxRetries, errorClass, e.getMessage());
                        // 对于连接重置或握手错误，清除连接池
                        if (errorMsg.contains("reset") || errorMsg.contains("handshake")) {
                            log.debug(" Clearing connection pool due to {} error", errorClass);
                            httpClient.connectionPool().evictAll();
                        }
                        continue;
                    } else {
                        // 不可重试或已达到最大重试次数
                        log.error(" Network error (non-retryable or max retries reached): {} - {}", 
                                errorClass, e.getMessage());
                        throw e;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Transcription interrupted", e);
                }
            }
            
            // 如果所有重试都失败了
            if (lastException != null) {
                throw lastException;
            }
            throw new IOException("Failed to transcribe audio after " + (maxRetries + 1) + " attempts");

        } catch (IOException e) {
            log.error("Transcription failed after attempts", e);
            // 提取友好的错误消息
            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.contains("500")) {
                errorMessage = "语音识别服务暂时不可用，请稍后重试";
            } else if (errorMessage != null && errorMessage.contains("429")) {
                errorMessage = "请求过于频繁，请稍后再试";
            } else if (errorMessage != null && errorMessage.contains("401") || errorMessage.contains("403")) {
                errorMessage = "API 密钥无效或权限不足";
            }
            throw new RuntimeException("Failed to transcribe audio: " + errorMessage, e);
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

            // 检查是否有error
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
                            log.info(" Transcription successful: {} chars", text.length());
                            return text;
                        }
                    }
                }
            }

            log.warn(" Could not parse Gemini response, returning raw response");
            return responseBody;

        } catch (IOException e) {
            log.error("Failed to parse Gemini response", e);
            return responseBody;
        }
    }

    /**
     * 解析错误响应消息，提取友好的错误信息
     */
    private String parseErrorMessage(String responseBody, int statusCode) {
        try {
            if (responseBody != null) {
                String lowered = responseBody.toLowerCase();
                // Cloudflare/edge HTML 5xx page
                if (lowered.contains("gateway time-out")
                        || lowered.contains("error code 504")
                        || lowered.contains("cloudflare")
                        || lowered.contains("<!doctype html")) {
                    return "上游网关超时（Cloudflare 504），请稍后重试或切换 STT 备选模型";
                }
            }
            if (responseBody != null && !responseBody.isBlank()) {
                JsonNode root = objectMapper.readTree(responseBody);
                if (root.has("error")) {
                    JsonNode error = root.get("error");
                    if (error.has("message")) {
                        String message = error.get("message").asText();
                        // 对于上游错误，提供更友好的消息
                        if (message.contains("upstream error") || message.contains("do_request_failed")) {
                            return "上游服务暂时不可用，请稍后重试";
                        }
                        return message;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse error response: {}", e.getMessage());
        }
        return "服务器错误 " + statusCode;
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
    private String getAudioMimeType(MultipartFile audioFile) {
        String contentType = audioFile != null ? audioFile.getContentType() : null;
        if (contentType != null && !contentType.isBlank()) {
            String normalized = contentType.toLowerCase().trim();
            if (normalized.startsWith("audio/wav") || normalized.startsWith("audio/x-wav")) return "audio/wav";
            if (normalized.startsWith("audio/mp3") || normalized.startsWith("audio/mpeg")) return "audio/mp3";
            if (normalized.startsWith("audio/aiff")) return "audio/aiff";
            if (normalized.startsWith("audio/aac")) return "audio/aac";
            if (normalized.startsWith("audio/ogg")) return "audio/ogg";
            if (normalized.startsWith("audio/flac")) return "audio/flac";
            if (normalized.startsWith("audio/webm")) return "audio/webm";
            if (normalized.startsWith("audio/mp4") || normalized.startsWith("audio/m4a")) return "audio/mp4";
        }

        String filename = audioFile != null ? audioFile.getOriginalFilename() : null;
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
