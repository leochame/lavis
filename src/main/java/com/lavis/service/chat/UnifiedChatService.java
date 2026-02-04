package com.lavis.service.chat;

import com.lavis.cognitive.AgentService;
import com.lavis.cognitive.orchestrator.TaskOrchestrator;
import com.lavis.service.llm.LlmFactory;
import com.lavis.service.tts.AsyncTtsService;
import com.lavis.service.tts.TtsDecisionService;
import com.lavis.websocket.AgentWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 统一的聊天服务
 * 
 * 统一处理文本和音频输入，支持快速路径和复杂任务路径的切换
 * 
 * 架构：
 * 1. 输入标准化：文本/音频 → 标准化文本
 * 2. 处理引擎选择：根据 useOrchestrator 参数选择处理路径
 * 3. TTS 处理：统一处理语音反馈
 * 4. 响应标准化：统一响应格式
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnifiedChatService {

    private final AgentService agentService;
    private final LlmFactory llmFactory;
    private final TtsDecisionService ttsDecisionService;
    private final AsyncTtsService asyncTtsService;
    private final AgentWebSocketHandler webSocketHandler;

    /**
     * 处理统一的聊天请求
     * 
     * @param request 标准化的聊天请求
     * @return 标准化的聊天响应
     */
    public ChatResponse process(ChatRequest request) throws Exception {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        log.info("[UnifiedChat] Processing request: type={}, useOrchestrator={}, needsTts={}, text={}",
                request.inputType(), request.useOrchestrator(), request.needsTts(), 
                request.text().length() > 100 ? request.text().substring(0, 100) + "..." : request.text());

        try {
            ChatResponse response;
            
            // 根据 useOrchestrator 选择处理路径
            if (request.useOrchestrator()) {
                response = processWithOrchestrator(request, requestId, startTime);
            } else {
                response = processWithFastPath(request, requestId, startTime);
            }

            // 处理 TTS（如果需要）
            if (request.needsTts() && response.success()) {
                handleTts(response, request);
            }

            return response;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[UnifiedChat] Processing failed", e);
            return ChatResponse.error(request.text(), e.getMessage(), requestId, duration);
        }
    }

    /**
     * 快速路径：使用 chatWithScreenshot（适合简单问答、单步命令）
     */
    private ChatResponse processWithFastPath(ChatRequest request, String requestId, long startTime) throws Exception {
        log.info("[UnifiedChat] Using fast path (chatWithScreenshot)");
        
        String agentText = agentService.chatWithScreenshot(request.text());
        long duration = System.currentTimeMillis() - startTime;
        
        log.info("[UnifiedChat] Fast path completed in {}ms", duration);
        
        return ChatResponse.fastPath(request.text(), agentText, requestId, duration);
    }

    /**
     * 复杂路径：使用 TaskOrchestrator（适合复杂任务、需要规划执行）
     */
    private ChatResponse processWithOrchestrator(ChatRequest request, String requestId, long startTime) throws Exception {
        log.info("[UnifiedChat] Using orchestrator path (TaskOrchestrator-compatible, via AgentService)");
        
        // 并行检查是否需要语音反馈（如果启用 TTS）
        CompletableFuture<Boolean> voiceFeedbackFuture = null;
        if (request.needsTts()) {
            voiceFeedbackFuture = asyncTtsService.checkNeedsVoiceFeedbackAsync(
                request.text(), ttsDecisionService
            );
        }

        // 执行任务
        // 注意：这里为了与现有架构最小耦合、避免循环依赖，
        // 不再调用 TaskOrchestrator 的 ReAct 循环，而是复用 AgentService 的工具执行能力。
        //
        // 对外仍然保留 "orchestrator 路径" 的语义，仅内部实现改为基于 AgentTools 的统一引擎。
        String agentText = agentService.chatWithScreenshot(request.text());
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("[UnifiedChat] Orchestrator path completed in {}ms", duration);

        // 创建响应
        ChatResponse response = ChatResponse.orchestratorPath(
            request.text(),
            agentText,
            requestId,
            duration,
            true,
            TaskOrchestrator.OrchestratorState.COMPLETED.name()
        );

        // 如果启用了 TTS，标记音频待推送（实际推送在 handleTts 中处理）
        if (request.needsTts() && voiceFeedbackFuture != null) {
            boolean needsVoiceFeedback = voiceFeedbackFuture.join();
            if (needsVoiceFeedback) {
                // 标记为待推送，实际推送由 handleTts 处理
                response = new ChatResponse(
                    response.success(),
                    response.userText(),
                    response.agentText(),
                    response.requestId(),
                    response.durationMs(),
                    true, // audioPending
                    response.orchestratorState()
                );
            }
        }

        return response;
    }

    /**
     * 处理 TTS 语音反馈
     */
    private void handleTts(ChatResponse response, ChatRequest request) {
        if (!response.audioPending() || !response.success()) {
            return;
        }

        String sessionId = resolveSessionId(request.wsSessionId());
        if (sessionId != null && webSocketHandler.isSessionActive(sessionId)) {
            asyncTtsService.generateAndPush(sessionId, response.agentText(), response.requestId());
            log.info("[UnifiedChat] TTS started for requestId: {}", response.requestId());
        } else {
            log.warn("[UnifiedChat] Voice feedback needed but no active WebSocket session");
        }
    }

    /**
     * 解析 WebSocket session ID
     */
    private String resolveSessionId(String wsSessionId) {
        if (wsSessionId != null && !wsSessionId.isBlank()) {
            return wsSessionId;
        }
        return webSocketHandler.getFirstSessionId();
    }

    /**
     * 标准化文本输入
     * 
     * @param text 用户文本
     * @param wsSessionId WebSocket session ID
     * @param useOrchestrator 是否使用 TaskOrchestrator
     * @param needsTts 是否需要 TTS
     */
    public ChatRequest normalizeTextInput(String text, String wsSessionId, Boolean useOrchestrator, Boolean needsTts) {
        // 默认值：文本输入通常使用快速路径，不需要 TTS
        boolean useOrch = useOrchestrator != null ? useOrchestrator : false;
        boolean needsTtsFlag = needsTts != null ? needsTts : false;
        
        return ChatRequest.textInput(text, wsSessionId, useOrch, needsTtsFlag);
    }

    /**
     * 标准化音频输入（需要先进行 STT 转换）
     * 
     * @param audioFile 音频文件
     * @param wsSessionId WebSocket session ID
     * @param useOrchestrator 是否使用 TaskOrchestrator
     * @return 标准化的请求（包含转录后的文本）
     */
    public ChatRequest normalizeAudioInput(MultipartFile audioFile, String wsSessionId, Boolean useOrchestrator) throws Exception {
        // STT 转换
        long sttStartTime = System.currentTimeMillis();
        String transcribedText = llmFactory.getSttModel().transcribe(audioFile);
        long sttDuration = System.currentTimeMillis() - sttStartTime;
        
        log.info("✅ STT completed in {}ms ({}s) - Audio: {} bytes, Transcribed: {} chars",
                sttDuration, String.format("%.2f", sttDuration / 1000.0),
                audioFile.getSize(), transcribedText.length());
        log.info("User transcribed: {}", transcribedText);

        // 默认值：音频输入通常使用复杂路径（TaskOrchestrator），需要 TTS
        boolean useOrch = useOrchestrator != null ? useOrchestrator : true;
        
        return ChatRequest.audioInput(transcribedText, wsSessionId, useOrch);
    }
}

