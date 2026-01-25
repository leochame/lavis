package com.lavis.service.llm.tts;

/**
 * TTS (Text-to-Speech) 模型接口
 */
public interface TtsModel {
    
    /**
     * 将文本转换为语音
     *
     * @param text 要转换的文本
     * @return Base64 编码的音频数据
     */
    String textToSpeech(String text);
    
    /**
     * 检查服务是否可用
     */
    boolean isAvailable();
}
