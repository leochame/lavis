package com.lavis.service.llm.stt;

import org.springframework.web.multipart.MultipartFile;

/**
 * STT (Speech-to-Text) 模型接口
 */
public interface SttModel {
    
    /**
     * 转录音频文件为文本
     *
     * @param audioFile 音频文件
     * @return 识别的文本
     */
    String transcribe(MultipartFile audioFile);
    
    /**
     * 检查服务是否可用
     */
    boolean isAvailable();
}
