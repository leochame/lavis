package com.lavis.infra.llm.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lavis.entry.config.llm.ModelConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Gemini TTS (Text-to-Speech) 实现
 *
 * 使用 Gemini streamGenerateContent API 进lines语音合成
 * API 端点: https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:streamGenerateContent
 *
 * 请求格式: 使用 generateContent API，设置 responseModalities: ["AUDIO"]
 * 响应格式: 流式返回 Base64 编码的 PCM 音频数据
 *
 * 参考文档: https://ai.google.dev/gemini-api/docs/text-to-speech
 */
@Slf4j
public class GeminiTtsModel implements TtsModel {

    private final ModelConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Gemini API 端点
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final String TTS_API_PATH = "/models/%s:streamGenerateContent";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    // 音频格式参数 (Gemini TTS 返回 24kHz PCM)
    private static final float SAMPLE_RATE = 24000f;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1;
    private static final int FRAME_SIZE = 2;
    private static final boolean BIG_ENDIAN = false;

    public GeminiTtsModel(ModelConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(5, 30, TimeUnit.SECONDS))
                .retryOnConnectionFailure(true)
                .protocols(java.util.Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build();
    }

    @Override
    public String textToSpeech(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text is required");
        }

        long totalStartTime = System.currentTimeMillis();
        try {
            log.info("🎙️ Starting Gemini TTS for text ({} chars), model: {}, voice: {}",
                    text.length(), config.getModelName(), config.getVoice());

            // 1. 确定 API URL
            String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                    ? config.getBaseUrl() : DEFAULT_BASE_URL;

            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }

            // 构建完整的 API URL
            boolean isOfficialUrl = baseUrl.contains("generativelanguage.googleapis.com");
            String apiUrl;
            if (isOfficialUrl) {
                apiUrl = baseUrl + String.format(TTS_API_PATH, config.getModelName());
            } else {
                // 中转站处理
                if (baseUrl.endsWith("/v1")) {
                    String baseWithoutV1 = baseUrl.substring(0, baseUrl.length() - 3);
                    apiUrl = baseWithoutV1 + "/v1beta" + String.format(TTS_API_PATH, config.getModelName());
                } else {
                    apiUrl = baseUrl + "/v1beta" + String.format(TTS_API_PATH, config.getModelName());
                }
            }

            // 添加 API key 作为查询参数（Gemini 官方 API 格式）
            apiUrl = apiUrl + "?key=" + config.getApiKey() + "&alt=sse";

            log.info("🔗 Gemini TTS API URL: {}", apiUrl.replaceAll("key=[^&]+", "key=***"));

            // 2. 构建请求体
            String requestJson = buildRequestBody(text);
            log.debug("📤 Request body: {}", requestJson);

            RequestBody requestBody = RequestBody.create(requestJson, JSON_MEDIA_TYPE);

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .addHeader("User-Agent", "Lavis-Agent/1.0 (Java/OkHttp)")
                    .post(requestBody)
                    .build();

            // 3. 发送请求并处理流式响应
            int maxRetries = config.getMaxRetries() != null ? config.getMaxRetries() : 3;
            IOException lastException = null;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    if (attempt > 0) {
                        long backoffMs = (long) Math.pow(2, attempt - 1) * 1000;
                        log.warn(" Retrying Gemini TTS request (attempt {}/{}) after {}ms",
                                attempt, maxRetries, backoffMs);
                        Thread.sleep(backoffMs);
                    }

                    long requestStartTime = System.currentTimeMillis();
                    try (Response response = httpClient.newCall(request).execute()) {
                        long requestDuration = System.currentTimeMillis() - requestStartTime;
                        log.info("🌐 API request completed in {}ms", requestDuration);

                        if (!response.isSuccessful()) {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            int statusCode = response.code();

                            if (statusCode >= 400 && statusCode < 500) {
                                log.error(" Gemini TTS API failed (client error): {} - {}", statusCode, responseBody);
                                throw new IOException("Gemini TTS failed: " + statusCode + " - " + responseBody);
                            } else {
                                log.warn(" Gemini TTS API server error: {} (attempt {}/{})",
                                        statusCode, attempt + 1, maxRetries + 1);
                                if (attempt < maxRetries) {
                                    lastException = new IOException("Server error: " + statusCode);
                                    continue;
                                }
                                throw new IOException("Gemini TTS failed after retries: " + statusCode);
                            }
                        }

                        // 解析流式响应
                        byte[] pcmData = parseStreamResponse(response);

                        if (pcmData.length == 0) {
                            throw new RuntimeException("No audio data received from TTS API");
                        }

                        log.info(" TTS audio generated successfully, PCM size: {} bytes", pcmData.length);

                        // 转换为 WAV 格式
                        byte[] wavData = pcmToWav(pcmData);

                        long totalDuration = System.currentTimeMillis() - totalStartTime;
                        log.info("⏱️ Total TTS processing time: {}ms", totalDuration);

                        return Base64.getEncoder().encodeToString(wavData);
                    }
                } catch (SocketException | SocketTimeoutException e) {
                    lastException = e;
                    if (attempt < maxRetries) {
                        log.warn(" Connection error (attempt {}/{}): {}",
                                attempt + 1, maxRetries, e.getMessage());
                        httpClient.connectionPool().evictAll();
                        continue;
                    }
                    throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("TTS interrupted", e);
                }
            }

            if (lastException != null) {
                throw lastException;
            }
            throw new IOException("Failed to generate speech after " + (maxRetries + 1) + " attempts");

        } catch (IOException e) {
            log.error(" TTS generation failed", e);
            throw new RuntimeException("Failed to generate speech: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 Gemini TTS 请求体
     *
     * 格式参考: https://ai.google.dev/gemini-api/docs/text-to-speech
     */
    private String buildRequestBody(String text) {
        ObjectNode root = objectMapper.createObjectNode();

        // contents
        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        ObjectNode textPart = parts.addObject();
        textPart.put("text", text);

        // generationConfig
        ObjectNode generationConfig = root.putObject("generationConfig");

        // responseModalities: ["AUDIO"] - 关键configuration，指定返回音频
        ArrayNode responseModalities = generationConfig.putArray("responseModalities");
        responseModalities.add("AUDIO");

        // speechConfig
        ObjectNode speechConfig = generationConfig.putObject("speechConfig");

        // voiceConfig
        ObjectNode voiceConfig = speechConfig.putObject("voiceConfig");
        ObjectNode prebuiltVoiceConfig = voiceConfig.putObject("prebuiltVoiceConfig");

        // 设置语音音色，默认使用 Kore
        String voiceName = config.getVoice() != null ? config.getVoice() : "Kore";
        prebuiltVoiceConfig.put("voiceName", voiceName);

        return root.toString();
    }

    /**
     * 解析流式 SSE 响应，提取音频数据
     */
    private byte[] parseStreamResponse(Response response) throws IOException {
        ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();

        if (response.body() == null) {
            throw new IOException("Empty response body");
        }

        String responseText = response.body().string();
        log.debug("📝 Raw response length: {} chars", responseText.length());

        // SSE 格式: data: {...}\n\n
        String[] lines = responseText.split("\n");

        for (String line : lines) {
            if (line.startsWith("data: ")) {
                String jsonData = line.substring(6).trim();
                if (jsonData.isEmpty() || jsonData.equals("[DONE]")) {
                    continue;
                }

                try {
                    JsonNode root = objectMapper.readTree(jsonData);

                    // 检查error
                    if (root.has("error")) {
                        String errorMsg = root.get("error").has("message")
                                ? root.get("error").get("message").asText()
                                : "Unknown error";
                        throw new IOException("Gemini API error: " + errorMsg);
                    }

                    // 解析音频数据
                    // 路径: candidates[0].content.parts[0].inlineData.data
                    if (root.has("candidates") && root.get("candidates").isArray()) {
                        JsonNode candidates = root.get("candidates");
                        if (candidates.size() > 0) {
                            JsonNode candidate = candidates.get(0);
                            if (candidate.has("content")) {
                                JsonNode content = candidate.get("content");
                                if (content.has("parts") && content.get("parts").isArray()) {
                                    for (JsonNode part : content.get("parts")) {
                                        if (part.has("inlineData")) {
                                            JsonNode inlineData = part.get("inlineData");
                                            if (inlineData.has("data")) {
                                                String base64Audio = inlineData.get("data").asText();
                                                byte[] audioBytes = Base64.getDecoder().decode(base64Audio);
                                                audioBuffer.write(audioBytes);
                                                log.debug(" Received audio chunk: {} bytes", audioBytes.length);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn(" Failed to parse SSE data: {}", e.getMessage());
                }
            }
        }

        return audioBuffer.toByteArray();
    }

    /**
     * will  PCM 数据转换为 WAV 格式
     */
    private byte[] pcmToWav(byte[] pcmData) {
        try {
            AudioFormat format = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    SAMPLE_RATE,
                    SAMPLE_SIZE_BITS,
                    CHANNELS,
                    FRAME_SIZE,
                    SAMPLE_RATE,
                    BIG_ENDIAN
            );

            ByteArrayInputStream bais = new ByteArrayInputStream(pcmData);
            AudioInputStream ais = new AudioInputStream(bais, format, pcmData.length / FRAME_SIZE);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, baos);

            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Failed to convert PCM to WAV", e);
            return pcmData;
        }
    }

    @Override
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }
}
