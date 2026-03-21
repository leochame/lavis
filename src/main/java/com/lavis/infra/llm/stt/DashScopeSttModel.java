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
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.Protocol;

/**
 * 阿里云 DashScope 原生 STT (语音识别) 实现
 * 
 * 支持多种模型和 API:
 * 1. asr-flash / qwen-audio-turbo -> 多模态生成 API (同步，支持 base64)
 * 2. sensevoice-v1 -> 同步识别 API (recognition)
 * 3. asr-flash-filetrans, fun-asr, paraformer -> 异步转写 API (transcription)
 * 
 * 文档: https://help.aliyun.com/zh/model-studio/qwen-speech-recognition
 */
@Slf4j
public class DashScopeSttModel implements SttModel {

    private final ModelConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 多模态生成 API (asr-flash, qwen-audio 系列)
    private static final String MULTIMODAL_API_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    // 同步语音识别 API (sensevoice, paraformer-realtime)
    private static final String SYNC_RECOGNITION_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/recognition";
    // 异步文件转写 API (filetrans 系列)
    private static final String ASYNC_TRANSCRIPTION_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    public DashScopeSttModel(ModelConfig config) {
        this.config = config;
        // 优化超时设置：
        // 1. 连接超时：10秒（快速失败，避免长时间等待连接）
        // 2. 读取超时：使用配置的超时时间（API 处理音频需要时间）
        // 3. 写入超时：30秒（上传音频文件需要时间，但不应过长）
        int timeoutSeconds = config.getTimeoutSeconds() != null ? config.getTimeoutSeconds() : 60;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)  // 连接超时：快速失败
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)  // 读取超时：API 处理时间
                .writeTimeout(30, TimeUnit.SECONDS)  // 写入超时：上传文件时间
                .connectionPool(new ConnectionPool(5, 30, TimeUnit.SECONDS))  // 连接池优化
                .retryOnConnectionFailure(true)  // 启用连接失败重试
                .protocols(java.util.Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))  // 支持 HTTP/2
                .build();
    }

    @Override
    public String transcribe(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new IllegalArgumentException("Audio file is required");
        }

        try {
            String modelName = config.getModelName();
            log.info("🎤 Starting DashScope ASR for file: {}, model: {}",
                    audioFile.getOriginalFilename(), modelName);

            // 根据模型选择正确的 API 和请求格式
            if (isQwenAudioModel(modelName)) {
                return transcribeWithMultimodalApi(audioFile, modelName);
            } else if (isAsyncModel(modelName)) {
                throw new UnsupportedOperationException(
                    "异步模型 " + modelName + " 需要文件 URL，请使用 sensevoice-v1 或 asr-flash 模型");
            } else {
                return transcribeWithRecognitionApi(audioFile, modelName);
            }

        } catch (IOException e) {
            log.error("Transcription failed", e);
            throw new RuntimeException("Failed to transcribe audio: " + e.getMessage(), e);
        }
    }

    /**
     * 使用多模态生成 API 进行转写 (asr-flash, qwen-audio-turbo 等)
     * 参考官方文档: https://bailian.console.aliyun.com/cn-beijing/#/doc/?type=model&url=2979031
     * 
     * 重要：ASR 专用模型只需要包含音频，不需要文字提示！
     */
    private String transcribeWithMultimodalApi(MultipartFile audioFile, String modelName) throws IOException {
        String apiUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                ? config.getBaseUrl() : MULTIMODAL_API_URL;

        log.info("🔗 Using Multimodal Generation API: {}", apiUrl);
        log.info("🔑 Using API Key prefix: {}...",
                config.getApiKey() != null && config.getApiKey().length() > 10
                        ? config.getApiKey().substring(0, 10) : "null");

        // 1. 将音频文件转换为 Base64 Data URI（记录编码时间）
        long encodeStartTime = System.currentTimeMillis();
        byte[] audioBytes = audioFile.getBytes();
        String audioBase64 = Base64.getEncoder().encodeToString(audioBytes);
        long encodeDuration = System.currentTimeMillis() - encodeStartTime;
        String mimeType = getAudioMimeType(audioFile.getOriginalFilename());
        String dataUri = "data:" + mimeType + ";base64," + audioBase64;
        
        double audioSizeMB = audioBytes.length / 1024.0 / 1024.0;
        double base64SizeMB = audioBase64.length() * 3.0 / 4.0 / 1024.0 / 1024.0;
        log.info("📁 Audio MIME type: {}, original size: {} MB ({} bytes), base64 size: {} MB ({} chars), encode time: {}ms", 
                mimeType, String.format("%.2f", audioSizeMB), audioBytes.length, 
                String.format("%.2f", base64SizeMB), audioBase64.length(), encodeDuration);

        // 2. 构建多模态请求体 (按照官方文档格式)
        // 格式: {
        //   "model": "asr-flash",
        //   "input": {
        //     "messages": [
        //       {"role": "system", "content": [{"text": ""}]},  // 系统消息（用于定制化识别的Context）
        //       {"role": "user", "content": [{"audio": "data:audio/mpeg;base64,..."}]}  // 只有音频，无文字！
        //     ]
        //   },
        //   "parameters": {
        //     "asr_options": {"enable_itn": false}
        //   }
        // }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);

        ObjectNode input = root.putObject("input");
        ArrayNode messages = input.putArray("messages");
        
        // 系统消息（用于定制化识别的 Context，可为空）
        ObjectNode sysMessage = messages.addObject();
        sysMessage.put("role", "system");
        ArrayNode sysContent = sysMessage.putArray("content");
        ObjectNode sysText = sysContent.addObject();
        sysText.put("text", "");  // 空文本，用于定制化识别
        
        // 用户消息 - 重要：只包含音频，不需要文字提示！
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        ArrayNode userContent = userMessage.putArray("content");
        ObjectNode audioContent = userContent.addObject();
        audioContent.put("audio", dataUri);
        
        // ASR 选项参数
        ObjectNode parameters = root.putObject("parameters");
        ObjectNode asrOptions = parameters.putObject("asr_options");
        asrOptions.put("enable_itn", true);  // 启用逆文本正则化（数字/日期等转换）
        // asrOptions.put("language", "zh");  // 可选：指定语种以提升准确率

        String requestJson = root.toString();
        log.debug("📤 Request body length: {} chars", requestJson.length());

        RequestBody requestBody = RequestBody.create(requestJson, JSON_MEDIA_TYPE);

        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build();

        // 3. 发送请求（记录性能指标）
        long requestStartTime = System.currentTimeMillis();
        try (Response response = httpClient.newCall(request).execute()) {
            long requestDuration = System.currentTimeMillis() - requestStartTime;
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("❌ DashScope Multimodal API failed: {} - URL: {} (took {}ms)", 
                        response.code(), apiUrl, requestDuration);
                log.error("❌ Error response body: {}", responseBody);
                throw new IOException("ASR transcription failed: " + response.code() + " - " + responseBody);
            }

            log.info("⏱️ DashScope API request completed in {}ms ({}s)", 
                    requestDuration, String.format("%.2f", requestDuration / 1000.0));
            log.debug("📝 DashScope response: {}", responseBody);
            return parseMultimodalResult(responseBody);
        }
    }

    /**
     * 使用同步识别 API (sensevoice-v1 等传统模型)
     */
    private String transcribeWithRecognitionApi(MultipartFile audioFile, String modelName) throws IOException {
        String apiUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                ? config.getBaseUrl() : SYNC_RECOGNITION_URL;

        log.info("🔗 Using Recognition API: {}", apiUrl);

        long encodeStartTime = System.currentTimeMillis();
        byte[] audioBytes = audioFile.getBytes();
        String audioBase64 = Base64.getEncoder().encodeToString(audioBytes);
        long encodeDuration = System.currentTimeMillis() - encodeStartTime;
        String format = getAudioFormat(audioFile.getOriginalFilename());
        
        double audioSizeMB = audioBytes.length / 1024.0 / 1024.0;
        log.info("📁 Audio format: {}, original size: {} MB ({} bytes), base64 size: {} MB, encode time: {}ms", 
                format, String.format("%.2f", audioSizeMB), audioBytes.length, 
                String.format("%.2f", audioBase64.length() * 3.0 / 4.0 / 1024.0 / 1024.0), encodeDuration);

        // 构建识别请求
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);

        ObjectNode input = root.putObject("input");
        input.put("audio", "data:audio/" + format + ";base64," + audioBase64);

        ObjectNode parameters = root.putObject("parameters");
        parameters.put("format", format);

        RequestBody requestBody = RequestBody.create(root.toString(), JSON_MEDIA_TYPE);

        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build();

        long requestStartTime = System.currentTimeMillis();
        try (Response response = httpClient.newCall(request).execute()) {
            long requestDuration = System.currentTimeMillis() - requestStartTime;
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("❌ DashScope Recognition API failed: {} - URL: {} (took {}ms)", 
                        response.code(), apiUrl, requestDuration);
                log.error("❌ Error response body: {}", responseBody);
                throw new IOException("ASR transcription failed: " + response.code() + " - " + responseBody);
            }

            log.info("⏱️ DashScope Recognition API request completed in {}ms ({}s)", 
                    requestDuration, String.format("%.2f", requestDuration / 1000.0));
            return parseTranscriptionResult(responseBody);
        }
    }

    /**
     * 判断是否为 Qwen Audio 系列模型 (使用多模态 API)
     */
    private boolean isQwenAudioModel(String modelName) {
        return modelName.contains("asr-flash")
                || modelName.contains("qwen-audio")
                || modelName.contains("qwen2-audio");
    }

    /**
     * 判断是否为异步模型
     */
    private boolean isAsyncModel(String modelName) {
        return modelName.contains("filetrans") 
                || modelName.contains("fun-asr") 
                || modelName.contains("paraformer-v2");
    }

    /**
     * 解析多模态 API 响应
     * 响应格式示例:
     * {
     *   "output": {
     *     "choices": [{
     *       "message": {
     *         "role": "assistant",
     *         "content": [{"text": "转录的文本内容"}]
     *       }
     *     }]
     *   },
     *   "usage": {...},
     *   "request_id": "..."
     * }
     */
    private String parseMultimodalResult(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 检查是否有错误
            if (root.has("code") && !root.get("code").asText().isEmpty()) {
                String errorCode = root.get("code").asText();
                String errorMessage = root.has("message") ? root.get("message").asText() : "Unknown error";
                throw new RuntimeException("DashScope error: " + errorCode + " - " + errorMessage);
            }

            // 解析多模态响应格式
            JsonNode output = root.get("output");
            if (output != null && output.has("choices")) {
                JsonNode choices = output.get("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode firstChoice = choices.get(0);
                    JsonNode message = firstChoice.get("message");
                    if (message != null) {
                        // content 可能是数组或字符串
                        JsonNode content = message.get("content");
                        if (content != null) {
                            if (content.isArray()) {
                                StringBuilder result = new StringBuilder();
                                for (JsonNode item : content) {
                                    if (item.has("text")) {
                                        result.append(item.get("text").asText());
                                    }
                                }
                                String text = result.toString().trim();
                                log.info("✅ Transcription successful: {} chars", text.length());
                                return text;
                            } else if (content.isTextual()) {
                                String text = content.asText().trim();
                                log.info("✅ Transcription successful: {} chars", text.length());
                                return text;
                            }
                        }
                    }
                }
            }

            log.warn("⚠️ Could not parse multimodal result, returning raw response");
            return responseBody;

        } catch (IOException e) {
            log.error("Failed to parse multimodal response", e);
            return responseBody;
        }
    }

    /**
     * 解析传统 ASR 响应，提取识别文本
     * 响应格式示例:
     * {
     *   "output": {
     *     "text": "识别的文本内容",
     *     "sentence": {...}
     *   },
     *   "usage": {...},
     *   "request_id": "..."
     * }
     */
    private String parseTranscriptionResult(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 检查是否有错误
            if (root.has("code") && !root.get("code").asText().isEmpty()) {
                String errorCode = root.get("code").asText();
                String errorMessage = root.has("message") ? root.get("message").asText() : "Unknown error";
                throw new RuntimeException("DashScope ASR error: " + errorCode + " - " + errorMessage);
            }

            // 提取输出文本
            JsonNode output = root.get("output");
            if (output != null) {
                // 尝试多种可能的字段名
                if (output.has("text")) {
                    String text = output.get("text").asText();
                    log.info("✅ Transcription successful: {} chars", text.length());
                    return text;
                }
                if (output.has("sentence") && output.get("sentence").has("text")) {
                    String text = output.get("sentence").get("text").asText();
                    log.info("✅ Transcription successful: {} chars", text.length());
                    return text;
                }
                // 某些 ASR 模型可能直接返回 sentences 数组
                if (output.has("sentences") && output.get("sentences").isArray()) {
                    StringBuilder fullText = new StringBuilder();
                    for (JsonNode sentence : output.get("sentences")) {
                        if (sentence.has("text")) {
                            fullText.append(sentence.get("text").asText());
                        }
                    }
                    String text = fullText.toString();
                    log.info("✅ Transcription successful: {} chars", text.length());
                    return text;
                }
            }

            // 如果无法解析，返回原始响应
            log.warn("⚠️ Could not parse transcription result, returning raw response");
            return responseBody;

        } catch (IOException e) {
            log.error("Failed to parse ASR response", e);
            return responseBody;
        }
    }

    /**
     * 根据文件名获取音频 MIME 类型
     * 参考官方示例: "data:" + AUDIO_MIME_TYPE + ";base64," + encoded
     */
    private String getAudioMimeType(String filename) {
        if (filename == null) {
            return "audio/webm"; // 默认格式
        }
        String lowerName = filename.toLowerCase();
        if (lowerName.endsWith(".webm")) return "audio/webm";
        if (lowerName.endsWith(".wav")) return "audio/wav";
        if (lowerName.endsWith(".mp3")) return "audio/mpeg";
        if (lowerName.endsWith(".m4a")) return "audio/mp4";
        if (lowerName.endsWith(".ogg")) return "audio/ogg";
        if (lowerName.endsWith(".flac")) return "audio/flac";
        if (lowerName.endsWith(".aac")) return "audio/aac";
        if (lowerName.endsWith(".opus")) return "audio/opus";
        if (lowerName.endsWith(".pcm")) return "audio/pcm";
        // 默认返回 webm（浏览器录音常用格式）
        return "audio/webm";
    }

    /**
     * 根据文件名获取音频格式（短格式，用于 parameters.format）
     */
    private String getAudioFormat(String filename) {
        if (filename == null) {
            return "webm";
        }
        String lowerName = filename.toLowerCase();
        if (lowerName.endsWith(".webm")) return "webm";
        if (lowerName.endsWith(".wav")) return "wav";
        if (lowerName.endsWith(".mp3")) return "mp3";
        if (lowerName.endsWith(".m4a")) return "m4a";
        if (lowerName.endsWith(".ogg")) return "ogg";
        if (lowerName.endsWith(".flac")) return "flac";
        if (lowerName.endsWith(".aac")) return "aac";
        if (lowerName.endsWith(".opus")) return "opus";
        if (lowerName.endsWith(".pcm")) return "pcm";
        return "webm";
    }

    @Override
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }
}
