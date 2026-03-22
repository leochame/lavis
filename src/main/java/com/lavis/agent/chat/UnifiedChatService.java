package com.lavis.agent.chat;

import com.lavis.agent.AgentService;
import com.lavis.agent.loop.TaskOrchestrator;
import com.lavis.infra.llm.LlmFactory;
import com.lavis.infra.tts.AsyncTtsService;
import com.lavis.infra.tts.TtsDecisionService;
import com.lavis.entry.ws.AgentWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 统一的聊days服务
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
     * 处理统一的聊days请求
     * 
     * @param request 标准化的聊days请求
     * @return 标准化的聊days响应
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

            // 处理 TTS（ifneed）- has been 禁用TTS功能
            // if (request.needsTts() && response.success()) {
            //     handleTts(response, request);
            // }

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
        
        long agentStartTime = System.currentTimeMillis();
        String agentText = agentService.chatWithScreenshot(request.text());
        long agentDuration = System.currentTimeMillis() - agentStartTime;
        long totalDuration = System.currentTimeMillis() - startTime;
        
        log.info("[UnifiedChat] Fast path completed - Agent: {}ms ({}s), Total: {}ms ({}s)", 
                agentDuration, String.format("%.2f", agentDuration / 1000.0),
                totalDuration, String.format("%.2f", totalDuration / 1000.0));
        
        return ChatResponse.fastPath(request.text(), agentText, requestId, totalDuration);
    }

    /**
     * 复杂路径：使用 TaskOrchestrator（适合复杂任务、need规划执lines）
     */
    private ChatResponse processWithOrchestrator(ChatRequest request, String requestId, long startTime) throws Exception {
        log.info("[UnifiedChat] Using orchestrator path (TaskOrchestrator-compatible, via AgentService)");
        
        // 并lines检查是否need语音反馈（if启用 TTS）- has been 禁用TTS功能
        CompletableFuture<Boolean> voiceFeedbackFuture = null;
        // if (request.needsTts()) {
        //     voiceFeedbackFuture = asyncTtsService.checkNeedsVoiceFeedbackAsync(
        //         request.text(), ttsDecisionService
        //     );
        // }

        // 执lines任务
        // 注意：这里为了与现有架构最小耦合、避免循环依赖，
        // 不再调用 TaskOrchestrator 的 ReAct 循环，而是复用 AgentService 的工具执lines能力。
        //
        // 对外仍然保留 "orchestrator 路径" 的语义，仅内部实现改为基于 AgentTools 的统一引擎。
        long agentStartTime = System.currentTimeMillis();
        String agentText = agentService.chatWithScreenshot(request.text());
        long agentDuration = System.currentTimeMillis() - agentStartTime;
        long totalDuration = System.currentTimeMillis() - startTime;
        
        log.info("[UnifiedChat] Orchestrator path completed - Agent: {}ms ({}s), Total: {}ms ({}s)", 
                agentDuration, String.format("%.2f", agentDuration / 1000.0),
                totalDuration, String.format("%.2f", totalDuration / 1000.0));

        // 创建响应
        ChatResponse response = ChatResponse.orchestratorPath(
            request.text(),
            agentText,
            requestId,
            totalDuration,
            true,
            TaskOrchestrator.OrchestratorState.COMPLETED.name()
        );

        // if启用了 TTS，标记音频待推送（实际推送在 handleTts 中处理）- has been 禁用TTS功能
        // if (request.needsTts() && voiceFeedbackFuture != null) {
        //     boolean needsVoiceFeedback = voiceFeedbackFuture.join();
        //     if (needsVoiceFeedback) {
        //         // 标记为待推送，实际推送由 handleTts 处理
        //         response = new ChatResponse(
        //             response.success(),
        //             response.userText(),
        //             response.agentText(),
        //             response.requestId(),
        //             response.durationMs(),
        //             true, // audioPending
        //             response.orchestratorState()
        //         );
        //     }
        // }

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
     * @param useOrchestrator 是否使用 TaskOrchestrator（可选，默认 false）
     * @param needsTts 是否need TTS（可选，默认 false）
     */
    public ChatRequest normalizeTextInput(String text, String wsSessionId, Boolean useOrchestrator, Boolean needsTts) {
        // 默认值：统一使用 false（快速路径，不need TTS）
        boolean useOrch = useOrchestrator != null ? useOrchestrator : false;
        boolean needsTtsFlag = needsTts != null ? needsTts : false;
        
        return ChatRequest.textInput(text, wsSessionId, useOrch, needsTtsFlag);
    }

    /**
     * 标准化音频输入（need先进lines STT 转换）
     * 
     * @param audioFile 音频文件
     * @param wsSessionId WebSocket session ID
     * @param useOrchestrator 是否使用 TaskOrchestrator（可选，默认 false）
     * @param needsTts 是否need TTS（可选，默认 false）
     * @return 标准化的请求（包含转录后的文本）
     * @throws Exception if STT 转换failed，抛出包含友好error消息的exception
     */
    public ChatRequest normalizeAudioInput(MultipartFile audioFile, String wsSessionId, Boolean useOrchestrator, Boolean needsTts) throws Exception {
        // STT 转换（性能监控）
        long sttStartTime = System.currentTimeMillis();
        double audioSizeMB = audioFile.getSize() / 1024.0 / 1024.0;
        log.info("🎤 Starting STT transcription - Audio: {} MB ({} bytes), filename: {}", 
                String.format("%.2f", audioSizeMB), audioFile.getSize(), audioFile.getOriginalFilename());
        
        try {
        String transcribedText = llmFactory.getSttModel().transcribe(audioFile);
        long sttDuration = System.currentTimeMillis() - sttStartTime;
        
            log.info(" STT completed in {}ms ({}s) - Audio: {} bytes, Transcribed: {} chars, Rate: {} MB/s",
                sttDuration, String.format("%.2f", sttDuration / 1000.0),
                    audioFile.getSize(), transcribedText.length(),
                    String.format("%.2f", audioSizeMB / (sttDuration / 1000.0)));
        log.info("User transcribed: {}", transcribedText);

        // 默认值：统一使用 false（快速路径，不need TTS），与文本输入保持一致
        // 唯一差异：这里多了一items STT 转换步骤
        boolean useOrch = useOrchestrator != null ? useOrchestrator : false;
        boolean needsTtsFlag = needsTts != null ? needsTts : false;
        
        return ChatRequest.audioInput(transcribedText, wsSessionId, useOrch, needsTtsFlag);
        } catch (RuntimeException e) {
            long sttDuration = System.currentTimeMillis() - sttStartTime;
            String errorMessage = e.getMessage();
            
            // 提取友好的error消息
            if (errorMessage != null) {
                if (errorMessage.contains("500") || errorMessage.contains("服务器error")) {
                    errorMessage = "语音识别服务暂时不可用，请稍后重试";
                } else if (errorMessage.contains("504")
                        || errorMessage.contains("gateway time-out")
                        || errorMessage.contains("网关超时")
                        || errorMessage.contains("cloudflare")) {
                    errorMessage = "语音识别上游网关超时（504），请稍后重试或切换 STT 备选模型";
                } else if (errorMessage.contains("429")) {
                    errorMessage = "请求过于频繁，请稍后再试";
                } else if (errorMessage.contains("401") || errorMessage.contains("403")) {
                    errorMessage = "API 密钥invalid或权限不足，请检查configuration";
                } else if (errorMessage.contains("timeout") || errorMessage.contains("超时")) {
                    errorMessage = "语音识别请求超时，请检查网络连接或稍后重试";
                } else if (errorMessage.contains("connection") || errorMessage.contains("连接")) {
                    errorMessage = "无法连接到语音识别服务，请检查网络连接";
                }
            } else {
                errorMessage = "语音识别failed，请稍后重试";
            }
            
            log.error(" STT transcription failed after {}ms: {}", sttDuration, errorMessage, e);
            throw new RuntimeException("语音识别failed: " + errorMessage, e);
        }
    }
}
