package com.lavis.service.llm.tts;

import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.utils.Constants;
import com.lavis.config.llm.ModelConfig;
import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 阿里云 DashScope 原生 TTS 实现 (使用 SDK)
 * 
 * 基于 qwen3-tts-flash 模型，使用 MultiModalConversation API
 * 文档: https://bailian.console.aliyun.com
 * 
 * 支持的音色 (Voice):
 * - Cherry: 女声，自然流畅
 * - Dylan: 男声
 * - Jada: 女声
 * - Sunny: 男声
 */
@Slf4j
public class DashScopeTtsModel implements TtsModel {

    private final ModelConfig config;
    
    // DashScope API 基础 URL (北京地域)
    private static final String DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/api/v1";
    
    // 音频格式参数 (SDK 返回 PCM 格式)
    private static final float SAMPLE_RATE = 24000f;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1;
    private static final int FRAME_SIZE = 2;
    private static final boolean BIG_ENDIAN = false;
    
    // DashScope TTS API 最大输入长度限制（字节数）
    private static final int MAX_TEXT_BYTES = 600;

    public DashScopeTtsModel(ModelConfig config) {
        this.config = config;
        // 设置 DashScope API 基础 URL
        Constants.baseHttpApiUrl = DASHSCOPE_BASE_URL;
    }

    @Override
    public String textToSpeech(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text is required");
        }

        try {
            // 检查并截断文本以确保不超过 API 限制
            String processedText = truncateTextIfNeeded(text);
            
            int charCount = processedText.length();
            int byteCount = processedText.getBytes(StandardCharsets.UTF_8).length;
            log.info("🎙️ Starting DashScope TTS (SDK) for text ({} chars, {} bytes), model: {}, voice: {}", 
                charCount, byteCount, config.getModelName(), config.getVoice());

            // 收集所有音频数据
            ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
            
            // 调用流式 TTS API
            streamCall(processedText, audioBuffer);
            
            byte[] pcmData = audioBuffer.toByteArray();
            
            if (pcmData.length == 0) {
                throw new RuntimeException("No audio data received from TTS API");
            }
            
            log.info("✅ TTS audio generated successfully, PCM size: {} bytes", pcmData.length);
            
            // 根据配置的输出格式处理音频
            String format = config.getFormat() != null ? config.getFormat().toLowerCase() : "mp3";
            byte[] outputData;
            
            if ("pcm".equals(format) || "raw".equals(format)) {
                // 直接返回 PCM 数据
                outputData = pcmData;
            } else if ("wav".equals(format)) {
                // 转换为 WAV 格式
                outputData = pcmToWav(pcmData);
            } else {
                // 默认返回 PCM (前端可以直接播放 PCM)
                // 注意: 如果需要 MP3 格式，需要额外的编码库
                outputData = pcmData;
                log.warn("⚠️ MP3 encoding not supported in SDK mode, returning PCM data");
            }
            
            return Base64.getEncoder().encodeToString(outputData);

        } catch (Exception e) {
            log.error("❌ TTS generation failed", e);
            throw new RuntimeException("Failed to generate speech: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 DashScope SDK 进行流式 TTS 调用
     */
    private void streamCall(String text, ByteArrayOutputStream audioBuffer) 
            throws ApiException, NoApiKeyException, UploadFileException {
        
        MultiModalConversation conv = new MultiModalConversation();
        
        // 解析音色配置
        AudioParameters.Voice voice = parseVoice(config.getVoice());
        
        // 检测语言类型 (简单检测)
        String languageType = detectLanguage(text);
        
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .apiKey(config.getApiKey())
                .model(config.getModelName())
                .text(text)
                .voice(voice)
                .languageType(languageType)
                .build();
        
        log.debug("TTS request: model={}, voice={}, language={}", 
            config.getModelName(), voice, languageType);
        
        Flowable<MultiModalConversationResult> result = conv.streamCall(param);
        
        result.blockingForEach(r -> {
            try {
                if (r.getOutput() != null && r.getOutput().getAudio() != null) {
                    String base64Data = r.getOutput().getAudio().getData();
                    if (base64Data != null && !base64Data.isEmpty()) {
                        byte[] audioBytes = Base64.getDecoder().decode(base64Data);
                        audioBuffer.write(audioBytes);
                    }
                }
            } catch (Exception e) {
                log.error("Error processing audio chunk", e);
            }
        });
    }

    /**
     * 解析音色配置字符串为 SDK 枚举
     */
    private AudioParameters.Voice parseVoice(String voiceName) {
        if (voiceName == null || voiceName.isBlank()) {
            return AudioParameters.Voice.CHERRY; // 默认音色
        }
        
        return switch (voiceName.toLowerCase()) {
            case "cherry" -> AudioParameters.Voice.CHERRY;
            case "dylan" -> AudioParameters.Voice.DYLAN;
            case "jada" -> AudioParameters.Voice.JADA;
            case "sunny" -> AudioParameters.Voice.SUNNY;
            default -> {
                log.warn("Unknown voice '{}', using default Cherry", voiceName);
                yield AudioParameters.Voice.CHERRY;
            }
        };
    }

    /**
     * 截断文本以确保不超过 API 的字节长度限制
     * 使用 UTF-8 编码计算字节数，从末尾逐个字符减少，确保不会损坏字符
     */
    private String truncateTextIfNeeded(String text) {
        if (text == null) {
            return text;
        }
        
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        
        if (textBytes.length <= MAX_TEXT_BYTES) {
            return text;
        }
        
        // 需要截断：从末尾逐个字符减少，直到字节数符合要求
        String truncated = text;
        int originalChars = text.length();
        int originalBytes = textBytes.length;
        
        // 从末尾逐个字符减少
        while (truncated.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES && truncated.length() > 0) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }
        
        // 如果截断后为空，至少保留一些内容（使用字节截断作为后备）
        if (truncated.isEmpty() && text.length() > 0) {
            // 使用字节截断，但确保在字符边界
            int truncatePos = MAX_TEXT_BYTES;
            while (truncatePos > 0 && (textBytes[truncatePos] & 0xC0) == 0x80) {
                truncatePos--;
            }
            if (truncatePos > 0) {
                truncated = new String(textBytes, 0, truncatePos, StandardCharsets.UTF_8);
            } else {
                // 最后的后备：至少保留前 MAX_TEXT_BYTES 个字节
                truncated = new String(textBytes, 0, Math.min(MAX_TEXT_BYTES, textBytes.length), StandardCharsets.UTF_8);
            }
        }
        
        int truncatedChars = truncated.length();
        int truncatedBytes = truncated.getBytes(StandardCharsets.UTF_8).length;
        
        log.warn("⚠️ Text truncated due to API limit ({} bytes max): {} chars ({} bytes) -> {} chars ({} bytes)", 
            MAX_TEXT_BYTES, originalChars, originalBytes, truncatedChars, truncatedBytes);
        
        return truncated;
    }

    /**
     * 简单检测文本语言
     * 建议与文本语种一致，以获得正确的发音和自然的语调
     */
    private String detectLanguage(String text) {
        if (text == null || text.isEmpty()) {
            return "Chinese";
        }
        
        // 简单检测：如果包含中文字符则认为是中文
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return "Chinese";
            }
        }
        
        return "English";
    }

    /**
     * 将 PCM 数据转换为 WAV 格式
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
            // 返回原始 PCM 数据
            return pcmData;
        }
    }

    @Override
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }
}
