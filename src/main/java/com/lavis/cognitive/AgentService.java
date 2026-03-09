package com.lavis.cognitive;

import com.lavis.cognitive.executor.ToolExecutionService;
import com.lavis.cognitive.orchestrator.TaskOrchestrator;
import com.lavis.memory.MemoryManager;
import com.lavis.memory.SessionStore;
import com.lavis.memory.TurnContext;
import com.lavis.perception.ScreenCapturer;
import com.lavis.service.llm.LlmFactory;
import com.lavis.skills.SkillService;
import com.lavis.skills.model.SkillExecutionContext;
import com.lavis.cognitive.memory.ImageContentCleanableChatMemory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import com.lavis.cognitive.message.ToolCallResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * M2 思考模块 - Agent 服务
 * 核心 AI 服务，整合 LLM 模型与工具调用
 * 支持多模态 + 工具调用 + 动态 Skill 挂载
 *
 * 职责：
 * - 管理对话记忆（ChatMemory）
 * - 处理多模态消息（文本 + 截图）
 * - 协调工具调用循环
 * - 动态挂载 Skills 作为工具
 * - 实现 Skill 上下文注入
 */
@Slf4j
@Service
public class AgentService {

    private final ScreenCapturer screenCapturer;
    private final TaskOrchestrator taskOrchestrator;
    private final ToolExecutionService toolExecutionService;
    private final LlmFactory llmFactory;
    private final MemoryManager memoryManager;
    private final SkillService skillService;
    private final MessageListLogger messageListLogger;

    @Value("${agent.retry.max:3}")
    private int maxRetries;

    @Value("${agent.retry.delay.ms:2000}")
    private long retryDelayMs;

    /** 使用的模型别名（可通过configuration切换） */
    @Value("${agent.model.alias:fast-model}")
    private String modelAlias;

    // 不再缓存模型实例，每times都从 LlmFactory 获取（支持动态configuration更新）
    // private ChatLanguageModel chatModel;
    private ChatMemory chatMemory;

        /** when前注入的 Skill 上下文（临时） */
    private volatile SkillExecutionContext activeSkillContext;

    public AgentService(ScreenCapturer screenCapturer,
                        TaskOrchestrator taskOrchestrator,
                        ToolExecutionService toolExecutionService,
                        LlmFactory llmFactory,
                        MemoryManager memoryManager,
                        @Lazy SkillService skillService,
                        MessageListLogger messageListLogger) {
        this.screenCapturer = screenCapturer;
        this.taskOrchestrator = taskOrchestrator;
        this.toolExecutionService = toolExecutionService;
        this.llmFactory = llmFactory;
        this.memoryManager = memoryManager;
        this.skillService = skillService;
        this.messageListLogger = messageListLogger;
    }

    // 工具执lines后etc待 UI 响应的时间（毫seconds）
    // 默认改为 200ms：给 UI 留出轻微缓冲，同时不至于太慢
    @Value("${agent.tool.wait.ms:200}")
    private int toolWaitMs = 200;

    @PostConstruct
    public void init() {
        try {
            // 通过 LlmFactory 获取模型实例（延迟加载，按需验证 API Key）
            if (!llmFactory.isModelAvailable(modelAlias)) {
                log.warn("Model '{}' not configured or API Key missing", modelAlias);
                return;
            }

            // initialize聊days记忆（使用支持 ImageContent 清理的自定义实现）
            this.chatMemory = ImageContentCleanableChatMemory.withMaxMessages(20);

            // initialize调度器
            taskOrchestrator.initialize(null);

            // initialize Skill 集成
            initializeSkillIntegration();
        } catch (Exception e) {
            log.error("AgentService initialization failed", e);
        }
    }

    /**
     * initialize Skill 集成。
     * 1. 加载when前所有 Skill 的 ToolSpecification
     * 2. 注册工具更新监听器（热重载支持）
     * 3. 注册上下文注入回调
     */
    private void initializeSkillIntegration() {
                // 工具注册与更新监听统一放在 ToolExecutionService 中，这里只负责上下文注入
    
                // 注册上下文注入回调
        // 这是解决"Context Gap"的核心：when Skill 被调用时，will 其知识注入到对话中
        skillService.setContextInjectionCallback(this::executeWithSkillContext);

    }

    /**
     * 带 Skill 上下文的执lines。
     * 这是"上下文注入"的核心实现：
     * 1. will  Skill 的知识（Markdown 正文）注入到 System Prompt
     * 2. 执lines Agent 命令
     * 3. 清理临时上下文
     *
     * @param context Skill 执lines上下文（包含知识内容）
     * @param goal    要执lines的目标
     * @return 执lines结果
     */
    private String executeWithSkillContext(SkillExecutionContext context, String goal) {
        // 设置when前活动的 Skill 上下文
        this.activeSkillContext = context;

        try {
            // 执lines带截图的对话（上下文会在 processWithTools 中注入）
            return chatWithScreenshot(goal);
        } finally {
            // 清理临时上下文
            this.activeSkillContext = null;
        }
    }

    /**
     * 发送带截图的消息 (多模态 + 工具调用)
     * 截图会显示鼠标位置（红色十characters）和上times点击位置（绿色圆环），便于 AI 反思
     */
    public String chatWithScreenshot(String message) {
        // 传入 0：表示“使用全局configuration maxToolIterations”。
        // if全局configuration <= 0，则表示本轮工具循环不设置times数上限。
        return chatWithScreenshot(message, 0);
    }

    /**
     * 发送带截图的消息 (多模态 + 工具调用)，支持步进模式
     *
     * @param message  用户消息
     * @param maxSteps 保留参数，用于not 来may的“步进模式”控制。
     *                 when前实现中**不会对工具循环施加硬性步数上限**，
     *                 仅由模型在没有工具调用或显式终止信号时自linesend。
     * @return 执lines结果
     */
    public String chatWithScreenshot(String message, int maxSteps) {
        // 每times从 LlmFactory 获取模型（支持动态configuration更新）
        ChatLanguageModel chatModel = getChatModel();
        if (chatModel == null) {
            return " Agent not initialized, please check API Key configuration";
        }

        // Context Engineering: start新的 Turn
        String sessionKey = memoryManager.getCurrentSessionKey();
        TurnContext turn = TurnContext.begin(sessionKey);

        // start新的 MessageList 日志轮times
        messageListLogger.startNewTurn(turn.getTurnId());

        try {
            return executeWithRetry(() -> {
                // Context Engineering: 使用感知去重截图
                ScreenCapturer.ImageCapture capture = screenCapturer.captureWithDedup();
                String imageId = capture.imageId();
                String base64Image = capture.base64();

                // if图片被复用，base64 may为 null，need从缓存获取
                if (base64Image == null && capture.isReused()) {
                    base64Image = screenCapturer.getLastImageBase64();
                    if (base64Image == null) {
                        // 强制重新截图（清除缓存）
                        screenCapturer.clearDedupCache();
                        capture = screenCapturer.captureWithDedup();
                        imageId = capture.imageId();
                        base64Image = capture.base64();
                    }
                }

                if (base64Image == null) {
                    throw new IllegalStateException("无法获取截图数据");
                }

                // 记录图片到 Turn 上下文
                turn.recordImage(imageId);

                // 构建多模态用户消息
                UserMessage userMessage = UserMessage.from(
                        TextContent.from(message),
                        ImageContent.from(base64Image, "image/jpeg"));

                return processWithTools(userMessage, maxSteps, imageId);
            });
        } finally {
            // Context Engineering: Turn end，触发压缩
            TurnContext endedTurn = TurnContext.end();
            if (endedTurn != null) {
                memoryManager.onTurnEnd(endedTurn);
            }
        }
    }

    /**
     * 核心方法：处理消息并执lines工具调用循环
     *
     * 【关键改进】
     * 1. 工具执lines后重新截图，让模型"看见"屏幕变化
     * 2. 支持 Skill 上下文注入（解决 Context Gap）
     * 3. 动态合并 Skill 工具到工具columns表
     * 4. Context Engineering: 集成感知去重和 imageId 追踪
     *
     * 执lines流程：
     * 1. 构建 System Prompt（如有活动 Skill，注入其知识）
     * 2. 发送初始消息（含截图）给模型
     * 3. 模型决定调用工具（包括 Skill 工具）
     * 4. 执lines工具（Skill 工具会触发上下文注入）
     * 5. etc待 UI 响应 + 重新截图
     * 6. 模型根据新截图决定下一步
     *
     * @param userMessage 用户消息
     * @param maxSteps    最大执lines步数限制
     * @param imageId     初始截图的 imageId（用于追踪）
     */
    private String processWithTools(UserMessage userMessage, int maxSteps, String imageId) {
        // 构建初始消息columns表
        List<ChatMessage> messages = buildInitialMessages(userMessage);

        // 保存用户消息到记忆和数据库
        saveUserMessageToMemory(userMessage, imageId);

        // 执lines工具调用循环
        String result = executeToolCallLoop(messages);

        // 记录 Turn end（在 finally 块之前，确保记录最终的消息数）
        messageListLogger.endTurn(messages.size(), 0, 0);

        return result;
    }

    /**
     * 构建初始消息columns表（包含系统提示、历史消息和用户消息）
     */
    private List<ChatMessage> buildInitialMessages(UserMessage userMessage) {
        List<ChatMessage> messages = new ArrayList<>();

        // 【关键】构建 System Prompt，如有活动 Skill 上下文则注入其知识
        String systemPrompt = buildSystemPromptWithSkillContext();
        messages.add(SystemMessage.from(systemPrompt));

        messages.addAll(chatMemory.messages());
        messages.add(userMessage);

        return messages;
    }

    /**
     * 保存用户消息到记忆和数据库
     */
    private void saveUserMessageToMemory(UserMessage userMessage, String imageId) {
        // 保存用户消息到记忆
        chatMemory.add(userMessage);

        // Context Engineering: 保存用户消息到数据库（带 imageId 追踪）
        try {
            if (imageId != null) {
                memoryManager.saveMessageWithImage(userMessage, estimateTokenCount(userMessage), imageId);
            } else {
                // 向后兼容：if没有 imageId，使用旧方法
                memoryManager.saveMessage(userMessage, estimateTokenCount(userMessage));
            }

            // Perform periodic memory management
            if (chatMemory instanceof ImageContentCleanableChatMemory cleanableMemory) {
                memoryManager.manageMemory(cleanableMemory);
            }
        } catch (Exception e) {
            log.warn("Failed to persist message to database", e);
        }
        }

    /**
     * 执lines工具调用循环。
     *
     * <p>本方法**不设置固定的最大迭代times数**，只要模型持续发起工具调用且not 发出终止信号，
     * 就会继续循环，直到：</p>
     * <ul>
     *     <li>模型不再请求工具调用，仅返回文本回复，或</li>
     *     <li>工具结果中包含显式终止信号（如 {@code complete_tool}）</li>
     * </ul>
     */
    private String executeToolCallLoop(List<ChatMessage> messages) {
        StringBuilder fullResponse = new StringBuilder();

        // 【关键】合并工具columns表：基础工具 + Skill 工具（由 ToolExecutionService 统一管理）
        List<ToolSpecification> allTools = toolExecutionService.getCombinedToolSpecifications();

        int iteration = 0;
        while (true) {
            iteration++;

            IterationOutcome outcome = processSingleIteration(messages, allTools, fullResponse);
            if (outcome.finished()) {
                // 没有工具调用，或收到明确的终止信号（for example complete_tool）
                return outcome.response();
            }
        }
    }

    /**
     * 处理单times迭代：调用模型、保存响应、检查工具调用
     * @return 迭代结果：是否has been 经end，以及when前累计响应
     */
    private IterationOutcome processSingleIteration(List<ChatMessage> messages,
                                                    List<ToolSpecification> allTools,
                                                    StringBuilder fullResponse) {
        // 每times从 LlmFactory 获取模型（支持动态configuration更新）
        ChatLanguageModel chatModel = getChatModel();
        if (chatModel == null) {
            throw new IllegalStateException(" Agent not initialized, please check API Key configuration");
        }
        
        // 调用模型（使用合并后的工具columns表），并统计响应耗时
        long llmStartTime = System.currentTimeMillis();
        Response<AiMessage> response = chatModel.generate(messages, allTools);
        long llmEndTime = System.currentTimeMillis();
        long llmLatencyMs = llmEndTime - llmStartTime;

        AiMessage aiMessage = response.content();

        // 添加 AI 响应到消息columns表
        messages.add(aiMessage);
        // 保存 AI 响应到记忆（包括工具调用请求）
        chatMemory.add(aiMessage);

        // Save AI message to database
        try {
            memoryManager.saveMessage(aiMessage, estimateTokenCount(aiMessage));
        } catch (Exception e) {
            log.warn("Failed to persist AI message to database", e);
        }

        // 检查是否有工具调用请求
        if (!aiMessage.hasToolExecutionRequests()) {
            // 没有工具调用，返回文本响应
            String textResponse = aiMessage.text();
            if (textResponse != null && !textResponse.isBlank()) {
                fullResponse.append(textResponse);
            }
            // 记录 MessageList 到专用日志文件
            messageListLogger.logMessageList(messages, (int) llmLatencyMs, 0);
            return new IterationOutcome(true, fullResponse.toString());
        }

        // 执lines工具调用
        List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
        ToolExecutionResult result = executeToolRequests(toolRequests, messages);

        // 记录 MessageList 到专用日志文件
        messageListLogger.logMessageList(messages, (int) llmLatencyMs, toolRequests.size());
        
        // 更新响应
        fullResponse.append(result.summary());
        if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
            fullResponse.append(aiMessage.text()).append("\n");
        }

        // if工具结果中包含"终止信号"（for example complete_tool），end循环
        if (result.shouldTerminate()) {
            log.info(" Received termination signal from tool call, ending main loop");
            return new IterationOutcome(true, fullResponse.toString());
        }

        return new IterationOutcome(false, fullResponse.toString()); // 继续循环
    }

        /**
         * 执lines工具调用请求columns表（通过统一工具执lines服务路由基础工具和 Skill 工具）
         */
        private ToolExecutionResult executeToolRequests(List<ToolExecutionRequest> toolRequests,
                                                     List<ChatMessage> messages) {
            StringBuilder toolResultsSummary = new StringBuilder();
        boolean hasVisualImpact = false;
        boolean shouldTerminate = false;
        List<String> executedToolNames = new ArrayList<>();

            for (ToolExecutionRequest request : toolRequests) {
                String toolName = request.name();
                String toolArgs = request.arguments();

                log.info(" Calling tool: {}({})", toolName, toolArgs);
                executedToolNames.add(toolName);

                // 【关键】通过统一工具执lines服务路由（基础工具 + Skill 工具）
                String result = toolExecutionService.executeUnified(toolName, toolArgs);
                log.info(" Tool result: {}", result.split("\n")[0]); // 只打印第一lines

                // 检测工具执linesfailed（仅用于日志记录，让模型通过上下文自己判断）
                if (result != null && (result.contains("") || result.contains("failed") ||
                    result.contains("error") || result.contains("exception") || result.contains("Error"))) {
                    log.warn("Tool execution failed: {}", toolName);
                }

                // 添加工具执lines结果
                ToolCallResultMessage toolResult;
                if (toolExecutionService.isVisualImpactTool(toolName)) {
                    // 视觉影响工具：etc待UI响应后截图并包含在结果中
                    String screenshot = captureScreenshotAfterTool(toolName);
                    if (screenshot != null) {
                        toolResult = ToolCallResultMessage.from(request, result, screenshot);
                    } else {
                        toolResult = ToolCallResultMessage.from(request, result);
                    }
                } else {
                    toolResult = ToolCallResultMessage.from(request, result);
                }
                messages.add(toolResult);
                chatMemory.add(toolResult);

                toolResultsSummary.append(String.format("[%s] %s\n", toolName, result.split("\n")[0]));

                // 判断是否是may影响屏幕的操作（统一由 ToolExecutionService 决定）
                if (toolExecutionService.isVisualImpactTool(toolName)) {
                    hasVisualImpact = true;
                }

                // if调用了里程碑completed工具，视为显式终止信号
                if ("complete_tool".equals(toolName)) {
                    shouldTerminate = true;
                }
            }

        return new ToolExecutionResult(toolResultsSummary.toString(), hasVisualImpact, shouldTerminate, executedToolNames);
    }

    /**
     * 工具执lines后截图
     */
    private String captureScreenshotAfterTool(String toolName) {
        try {
            int waitTime = getWaitTimeForTools(List.of(toolName));
            Thread.sleep(waitTime);

            ScreenCapturer.ImageCapture capture = screenCapturer.captureWithDedup(true, true);
            String imageId = capture.imageId();
            String base64Image = capture.base64();

            if (base64Image == null) {
                return null;
            }

            // 记录图片到 Turn 上下文
            TurnContext currentTurn = TurnContext.current();
            if (currentTurn != null) {
                currentTurn.recordImage(imageId);
            }

            return base64Image;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 根据工具类型动态计算etc待时间
     * 
     * @param toolNames 执lines的工具名称columns表
     * @return etc待时间（毫seconds）
     */
    private int getWaitTimeForTools(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return toolWaitMs; // 默认etc待时间
        }
        
        // 取所有工具中need最长etc待时间的工具
        int maxWaitTime = toolWaitMs;
        for (String toolName : toolNames) {
            int waitTime = switch (toolName) {
                // 文本输入操作 - need更长时间让 UI 响应和渲染
                case "type_text_at" -> 1500;
                // 打开应用/网pages - need较长时间加载
                case "openApplication", "openURL", "open_browser" -> 2000;
                // 执lines脚本 - mayneed时间执lines
                case "executeAppleScript", "executeShell" -> 1200;
                // 点击操作 - 中etc待时间
                case "click", "doubleClick", "rightClick" -> 800;
                // 拖拽操作 - need时间completed动画
                case "drag" -> 1000;
                // 滚动操作 - need时间completed滚动动画
                case "scroll" -> 600;
                // 打开文件 - mayneed时间加载
                case "openFile" -> 1500;
                // etc待操作 - 本身就有etc待，截图前不need额外etc待太久
                case "wait" -> 300;
                // 其他操作使用默认值
                default -> toolWaitMs;
            };
            maxWaitTime = Math.max(maxWaitTime, waitTime);
        }
        
        return maxWaitTime;
    }

    /**
     * 单轮工具执lines的封装结果
     */
    private static class ToolExecutionResult {
        private final String summary;
        private final boolean hasVisualImpact;
        /** 是否收到显式终止信号（for example complete_tool） */
        private final boolean shouldTerminate;
        /** 执lines的工具名称columns表（用于动态调整etc待时间） */
        private final List<String> toolNames;

        ToolExecutionResult(String summary, boolean hasVisualImpact, boolean shouldTerminate, List<String> toolNames) {
            this.summary = summary;
            this.hasVisualImpact = hasVisualImpact;
            this.shouldTerminate = shouldTerminate;
            this.toolNames = toolNames;
        }

        String summary() {
            return summary;
        }

        boolean hasVisualImpact() {
            return hasVisualImpact;
        }

        boolean shouldTerminate() {
            return shouldTerminate;
        }

        List<String> toolNames() {
            return toolNames;
        }
    }

    /**
     * 单times迭代的返回结果：是否end + 累计响应
     */
    private record IterationOutcome(boolean finished, String response) {}

    /**
     * 获取调度器
     */
    public TaskOrchestrator getTaskOrchestrator() {
        return taskOrchestrator;
    }

    /**
     * 带重试的执lines
     */
    private String executeWithRetry(ThrowingSupplier<String> action) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                lastException = e;
                String errorMsg = e.getMessage();

                if (errorMsg != null && (errorMsg.contains("429") || errorMsg.contains("RESOURCE_EXHAUSTED"))) {
                    long waitTime = retryDelayMs * attempt * 2;
                    log.warn(" API rate limit/quota exhausted, waiting {}ms before retry ({}/{})", waitTime, attempt, maxRetries);
                    sleep(waitTime);
                } else {
                    log.error(" Execution failed ({}/{}): {}", attempt, maxRetries, errorMsg);
                    if (attempt < maxRetries) {
                        sleep(retryDelayMs);
                    }
                }
            }
        }

        log.error(" Still failed after {} retries", maxRetries, lastException);
        return "处理failed: " + (lastException != null ? lastException.getMessage() : "not 知error");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取 Chat 模型实例（每times从 LlmFactory 获取，支持动态configuration更新）
     * whenconfiguration更新时，LlmFactory 会清除缓存，下times获取时会使用新configuration
     */
    private ChatLanguageModel getChatModel() {
        try {
            if (!llmFactory.isModelAvailable(modelAlias)) {
                log.warn(" Model '{}' not configured or API Key missing", modelAlias);
                return null;
            }
            return llmFactory.getModel(modelAlias);
        } catch (Exception e) {
            log.error(" Failed to get model: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 检查 Agent 是否可用
     */
    public boolean isAvailable() {
        // 每times从 LlmFactory 获取模型（支持动态configuration更新）
        ChatLanguageModel chatModel = getChatModel();
        return chatModel != null && toolExecutionService.getToolCount() > 0;
    }

    /**
     * 获取模型info
     */
    public String getModelInfo() {
        return String.format("Model: %s, Status: %s, Tools: %d items",
                modelAlias,
                isAvailable() ? " Available" : " Not available",
                toolExecutionService.getToolCount());
    }

    /**
     * Get memory statistics
     */
    public MemoryManager.MemoryStats getMemoryStats() {
        return memoryManager.getMemoryStats();
    }

    /**
     * Get session statistics
     */
    public SessionStore.SessionStats getSessionStats() {
        return memoryManager.getSessionStats();
    }

    /**
     * 重置对话历史
     */
    public void resetConversation() {
        if (chatMemory != null) {
            chatMemory.clear();
        }
        memoryManager.resetSession();
        log.info(" Conversation history reset");
    }

    /**
     * Estimate token count for a message
     * Rough approximation: 1 token ≈ 4 characters
     */
    private int estimateTokenCount(ChatMessage message) {
        String text = "";
        if (message instanceof UserMessage userMsg) {
            text = userMsg.hasSingleText() ? userMsg.singleText() : userMsg.toString();
        } else if (message instanceof AiMessage aiMsg) {
            text = aiMsg.text();
            // if text 为 null（只有工具调用），估算工具调用的 token 数
            if (text == null) {
                if (aiMsg.hasToolExecutionRequests()) {
                    // 估算每items工具调用的 token 数（工具名 + 参数）
                    int toolTokenCount = 0;
                    for (var toolRequest : aiMsg.toolExecutionRequests()) {
                        // 工具名大约 10 tokens，参数大约按长度估算
                        String args = toolRequest.arguments() != null ? toolRequest.arguments() : "";
                        toolTokenCount += 10 + (args.length() / 4);
                    }
                    return toolTokenCount;
                }
                return 0;
            }
        } else {
            text = message.toString();
        }
        // 确保 text 不为 null
        if (text == null) {
            text = "";
        }
        return text.length() / 4;
    }

    // ==================== Skill 集成辅助方法 ====================

    /**
     * 构建带 Skill 上下文的 System Prompt。
     * if有活动的 Skill 上下文，will 其知识注入到 System Prompt 中。
     */
    private String buildSystemPromptWithSkillContext() {
        if (activeSkillContext == null) {
            return AgentPrompts.SYSTEM_PROMPT;
        }

        // 注入 Skill 知识到 System Prompt
        String skillInjection = activeSkillContext.toSystemPromptInjection();
        String enhancedPrompt = AgentPrompts.SYSTEM_PROMPT
                + String.format(AgentPrompts.SKILL_CONTEXT_TEMPLATE, skillInjection);

        return enhancedPrompt;
    }

    // Skill 工具数量可通过 ToolExecutionService 的合并视图间接获得，如有need可在此处添加包装方法

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
