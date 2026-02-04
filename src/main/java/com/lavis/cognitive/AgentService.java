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
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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

    @Value("${agent.retry.max:3}")
    private int maxRetries;

    @Value("${agent.retry.delay.ms:2000}")
    private long retryDelayMs;

    @Value("${agent.max.tool.iterations:10}")
    private int maxToolIterations;

    /** 使用的模型别名（可通过配置切换） */
    @Value("${agent.model.alias:fast-model}")
    private String modelAlias;

    private ChatLanguageModel chatModel;
    private ChatMemory chatMemory;

    /** 动态 Skill 工具列表（实时更新） */
    private final List<ToolSpecification> skillToolSpecifications = new CopyOnWriteArrayList<>();

    /** 当前注入的 Skill 上下文（临时） */
    private volatile SkillExecutionContext activeSkillContext;

    public AgentService(ScreenCapturer screenCapturer,
                        TaskOrchestrator taskOrchestrator,
                        ToolExecutionService toolExecutionService,
                        LlmFactory llmFactory,
                        MemoryManager memoryManager,
                        @Lazy SkillService skillService) {
        this.screenCapturer = screenCapturer;
        this.taskOrchestrator = taskOrchestrator;
        this.toolExecutionService = toolExecutionService;
        this.llmFactory = llmFactory;
        this.memoryManager = memoryManager;
        this.skillService = skillService;
    }

    // 工具执行后等待 UI 响应的时间（毫秒）
    @Value("${agent.tool.wait.ms:500}")
    private int toolWaitMs = 500;

    @PostConstruct
    public void init() {
        try {
            // 通过 LlmFactory 获取模型实例（延迟加载，按需验证 API Key）
            if (!llmFactory.isModelAvailable(modelAlias)) {
                log.warn("⚠️ 模型 '{}' 未配置或 API Key 缺失，Agent 功能将不可用", modelAlias);
                return;
            }

            this.chatModel = llmFactory.getModel(modelAlias);

            // 初始化聊天记忆（使用支持 ImageContent 清理的自定义实现）
            this.chatMemory = ImageContentCleanableChatMemory.withMaxMessages(20);

            // 初始化调度器（传递 LLM 模型给 Planner 和 Executor）
            taskOrchestrator.initialize(chatModel);

            // 初始化 Skill 集成
            initializeSkillIntegration();

            log.info("✅ AgentService 初始化完成 - 模型: {}, 工具数: {}, Skill工具数: {}",
                    modelAlias, toolExecutionService.getToolCount(), skillToolSpecifications.size());
        } catch (Exception e) {
            log.error("❌ AgentService 初始化失败", e);
        }
    }

    /**
     * 初始化 Skill 集成。
     * 1. 加载当前所有 Skill 的 ToolSpecification
     * 2. 注册工具更新监听器（热重载支持）
     * 3. 注册上下文注入回调
     */
    private void initializeSkillIntegration() {
        // 加载当前 Skill 工具
        skillToolSpecifications.clear();
        skillToolSpecifications.addAll(skillService.getToolSpecifications());
        log.info("📦 加载了 {} 个 Skill 工具", skillToolSpecifications.size());

        // 注册工具更新监听器（热重载支持）
        skillService.addToolUpdateListener(newTools -> {
            skillToolSpecifications.clear();
            skillToolSpecifications.addAll(newTools);
            log.info("🔄 Skill 工具列表已更新，当前数量: {}", skillToolSpecifications.size());
        });

        // 注册上下文注入回调
        // 这是解决"Context Gap"的核心：当 Skill 被调用时，将其知识注入到对话中
        skillService.setContextInjectionCallback(this::executeWithSkillContext);

        log.info("✅ Skill 集成初始化完成");
    }

    /**
     * 带 Skill 上下文的执行。
     * 这是"上下文注入"的核心实现：
     * 1. 将 Skill 的知识（Markdown 正文）注入到 System Prompt
     * 2. 执行 Agent 命令
     * 3. 清理临时上下文
     *
     * @param context Skill 执行上下文（包含知识内容）
     * @param goal    要执行的目标
     * @return 执行结果
     */
    private String executeWithSkillContext(SkillExecutionContext context, String goal) {
        log.info("🎯 执行带 Skill 上下文的命令: skill={}, goal={}", context.getSkillName(), goal);

        // 设置当前活动的 Skill 上下文
        this.activeSkillContext = context;

        try {
            // 执行带截图的对话（上下文会在 processWithTools 中注入）
            return chatWithScreenshot(goal);
        } finally {
            // 清理临时上下文
            this.activeSkillContext = null;
        }
    }

    /**
     * 发送带截图的消息 (多模态 + 工具调用)
     * 截图会显示鼠标位置（红色十字）和上次点击位置（绿色圆环），便于 AI 反思
     */
    public String chatWithScreenshot(String message) {
        return chatWithScreenshot(message, 0); // 默认使用全局配置（0 表示无限制）
    }

    /**
     * 发送带截图的消息 (多模态 + 工具调用)，支持步进模式
     *
     * @param message  用户消息
     * @param maxSteps 最大执行步数限制。如果 > 0，则限制单次调用的最大工具调用次数；如果 <= 0，则使用全局配置
     *                 maxToolIterations
     * @return 执行结果
     */
    public String chatWithScreenshot(String message, int maxSteps) {
        if (chatModel == null) {
            return "❌ Agent 未初始化，请检查 API Key 配置";
        }

        log.info("📷 用户消息 (带截图, 步数限制 {}): {}", maxSteps > 0 ? maxSteps : "无限制", message);

        // Context Engineering: 开始新的 Turn
        String sessionKey = memoryManager.getCurrentSessionKey();
        TurnContext turn = TurnContext.begin(sessionKey);

        try {
            return executeWithRetry(() -> {
                // Context Engineering: 使用感知去重截图
                ScreenCapturer.ImageCapture capture = screenCapturer.captureWithDedup();
                String imageId = capture.imageId();
                String base64Image = capture.base64();
                
                // 如果图片被复用，base64 可能为 null，需要从缓存获取
                if (base64Image == null && capture.isReused()) {
                    base64Image = screenCapturer.getLastImageBase64();
                    if (base64Image == null) {
                        log.warn("图片复用但缓存数据丢失，强制重新截图");
                        // 强制重新截图（清除缓存）
                        screenCapturer.clearDedupCache();
                        capture = screenCapturer.captureWithDedup();
                        imageId = capture.imageId();
                        base64Image = capture.base64();
                    } else {
                        log.debug("图片复用，使用缓存的 base64 数据: {}", imageId);
                    }
                }
                
                if (base64Image == null) {
                    throw new IllegalStateException("无法获取截图数据");
                }
                
                log.info("📸 截图完成: imageId={}, 复用={}, 大小: {} KB",
                        imageId, capture.isReused(), base64Image.length() * 3 / 4 / 1024);

                // 记录图片到 Turn 上下文
                turn.recordImage(imageId);

                // 构建多模态用户消息
                UserMessage userMessage = UserMessage.from(
                        TextContent.from(message),
                        ImageContent.from(base64Image, "image/jpeg"));

                return processWithTools(userMessage, maxSteps, imageId);
            });
        } finally {
            // Context Engineering: Turn 结束，触发压缩
            TurnContext endedTurn = TurnContext.end();
            if (endedTurn != null) {
                memoryManager.onTurnEnd(endedTurn);
            }
        }
    }

    /**
     * 核心方法：处理消息并执行工具调用循环
     *
     * 【关键改进】
     * 1. 工具执行后重新截图，让模型"看见"屏幕变化
     * 2. 支持 Skill 上下文注入（解决 Context Gap）
     * 3. 动态合并 Skill 工具到工具列表
     * 4. Context Engineering: 集成感知去重和 imageId 追踪
     *
     * 执行流程：
     * 1. 构建 System Prompt（如有活动 Skill，注入其知识）
     * 2. 发送初始消息（含截图）给模型
     * 3. 模型决定调用工具（包括 Skill 工具）
     * 4. 执行工具（Skill 工具会触发上下文注入）
     * 5. 等待 UI 响应 + 重新截图
     * 6. 模型根据新截图决定下一步
     *
     * @param userMessage 用户消息
     * @param maxSteps    最大执行步数限制
     * @param imageId     初始截图的 imageId（用于追踪）
     */
    private String processWithTools(UserMessage userMessage, int maxSteps, String imageId) {
        // 构建消息列表
        List<ChatMessage> messages = new ArrayList<>();

        // 【关键】构建 System Prompt，如有活动 Skill 上下文则注入其知识
        String systemPrompt = buildSystemPromptWithSkillContext();
        messages.add(SystemMessage.from(systemPrompt));

        messages.addAll(chatMemory.messages());
        messages.add(userMessage);

        // 保存用户消息到记忆
        chatMemory.add(userMessage);

        // Context Engineering: 保存用户消息到数据库（带 imageId 追踪）
        try {
            if (imageId != null) {
                memoryManager.saveMessageWithImage(userMessage, estimateTokenCount(userMessage), imageId);
                log.debug("用户消息已保存到数据库: imageId={}", imageId);
            } else {
                // 向后兼容：如果没有 imageId，使用旧方法
                memoryManager.saveMessage(userMessage, estimateTokenCount(userMessage));
                log.warn("用户消息保存时缺少 imageId，使用旧方法");
            }

            // Perform periodic memory management
            if (chatMemory instanceof ImageContentCleanableChatMemory cleanableMemory) {
                MemoryManager.MemoryManagementResult result = memoryManager.manageMemory(cleanableMemory);
                if (result.imagesCleanedCount() > 0 || result.compressionPerformed()) {
                    log.info("Memory management: {} images cleaned, compression: {}",
                            result.imagesCleanedCount(), result.compressionPerformed());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to persist message to database", e);
        }

        StringBuilder fullResponse = new StringBuilder();

        // 【关键】合并工具列表：基础工具 + Skill 工具
        List<ToolSpecification> allTools = buildCombinedToolSpecifications();
        log.debug("可用工具总数: {} (基础: {}, Skill: {})",
                allTools.size(), toolExecutionService.getToolCount(), skillToolSpecifications.size());

        // 工具调用循环 - 使用传入的 maxSteps，如果 <= 0 则使用全局配置（兼容旧代码）
        int limit = (maxSteps > 0) ? maxSteps : this.maxToolIterations;
        log.debug("工具调用循环限制: {} 步", limit);

        for (int iteration = 0; iteration < limit; iteration++) {
            log.info("🔄 工具调用迭代 {}/{}", iteration + 1, limit);

            // 调用模型（使用合并后的工具列表）
            Response<AiMessage> response = chatModel.generate(messages, allTools);
            AiMessage aiMessage = response.content();
            log.info("🤖 Agent 响应: {}", aiMessage);
            // 添加 AI 响应到消息列表
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

                log.info("🤖 Agent 响应: {}", fullResponse);
                return fullResponse.toString();
            }

            // 执行工具调用
            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
            log.info("🔧 执行 {} 个工具调用", toolRequests.size());

            StringBuilder toolResultsSummary = new StringBuilder();
            boolean hasVisualImpact = false; // 是否有可能影响屏幕的操作
            boolean hasError = false; // 是否有工具执行失败

            for (ToolExecutionRequest request : toolRequests) {
                String toolName = request.name();
                String toolArgs = request.arguments();

                log.info("  → 调用工具: {}({})", toolName, toolArgs);

                // 【关键】判断是基础工具还是 Skill 工具
                String result = executeToolOrSkill(toolName, toolArgs);
                log.info("  ← 工具结果: {}", result.split("\n")[0]); // 只打印第一行

                // 检测工具执行失败（仅用于日志记录，让模型通过上下文自己判断）
                if (result != null && (result.contains("❌") || result.contains("失败") ||
                    result.contains("错误") || result.contains("异常") || result.contains("Error"))) {
                    hasError = true;
                    log.warn("⚠️ 工具执行失败: {}", result.split("\n")[0]);
                }

                // 添加工具执行结果
                ToolExecutionResultMessage toolResult = ToolExecutionResultMessage.from(
                        request,
                        result);
                messages.add(toolResult);
                // 保存工具执行结果到记忆
                chatMemory.add(toolResult);

                toolResultsSummary.append(String.format("[%s] %s\n", toolName, result.split("\n")[0]));

                // 判断是否是可能影响屏幕的操作
                if (isVisualImpactTool(toolName)) {
                    hasVisualImpact = true;
                }
            }

            fullResponse.append(toolResultsSummary);

            // 如果 AI 也有文本响应，添加到结果
            if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
                fullResponse.append(aiMessage.text()).append("\n");
            }

            // 【关键改进】工具执行后重新截图，注入新的视觉观察
            if (hasVisualImpact) {
                try {
                    // 等待 UI 响应
                    log.info("⏳ 等待 UI 响应 {}ms...", toolWaitMs);
                    Thread.sleep(toolWaitMs);

                    // Context Engineering: 使用感知去重重新截图
                    ScreenCapturer.ImageCapture newCapture = screenCapturer.captureWithDedup();
                    String newImageId = newCapture.imageId();
                    String newScreenshot = newCapture.base64();
                    
                    // 如果图片被复用，base64 可能为 null，需要从缓存获取
                    if (newScreenshot == null && newCapture.isReused()) {
                        newScreenshot = screenCapturer.getLastImageBase64();
                        if (newScreenshot == null) {
                            log.warn("重新截图时图片复用但缓存数据丢失，强制重新截图");
                            // 强制重新截图（清除缓存）
                            screenCapturer.clearDedupCache();
                            newCapture = screenCapturer.captureWithDedup();
                            newImageId = newCapture.imageId();
                            newScreenshot = newCapture.base64();
                        } else {
                            log.debug("重新截图时图片复用，使用缓存的 base64 数据: {}", newImageId);
                        }
                    }
                    
                    if (newScreenshot == null) {
                        log.warn("重新截图失败，无法获取图片数据");
                        continue; // 跳过本次观察
                    }
                    
                    log.info("📸 重新截图完成: imageId={}, 复用={}, 注入新的视觉观察",
                            newImageId, newCapture.isReused());

                    // 记录图片到 Turn 上下文
                    TurnContext currentTurn = TurnContext.current();
                    if (currentTurn != null) {
                        currentTurn.recordImage(newImageId);
                    }

                    // 构建观察消息，告诉模型这是操作后的新截图
                    // 提示模型自己检查是否重复操作
                    String observationText = String.format("""
                            ## Screen Observation After Operation

                            Last Step Execution Result:
                            %s

                            Please carefully observe current latest screenshot and judge:
                            1. Was operation successful? Did screen change as expected?
                            2. If successful, what should be done next?
                            3. If failed or no change, how should it be adjusted?

                            **Important Self-Check Before Next Action:**
                            - Review your conversation history: Have you already tried this same operation or similar operations multiple times?
                            - If you notice you've executed the same tool with similar parameters 2-3 times without visible success, you MUST try a different approach:
                              * Adjust coordinates (try 5-30 pixels offset)
                              * Try a different action type (e.g., double-click instead of click)
                              * Check if there's a popup, dialog, or loading state blocking the operation
                              * Wait longer or check if the target element is actually accessible
                            - Do NOT repeat the same operation if it hasn't worked after 2-3 attempts

                            **Note**: Always make decisions based on this latest screenshot and your action history
                            """, toolResultsSummary.toString());

                    UserMessage observationMessage = UserMessage.from(
                            TextContent.from(observationText),
                            ImageContent.from(newScreenshot, "image/jpeg"));
                    messages.add(observationMessage);
                    // 保存观察消息到记忆
                    chatMemory.add(observationMessage);
                    
                    // Context Engineering: 保存观察消息到数据库（带 imageId 追踪）
                    try {
                        memoryManager.saveMessageWithImage(observationMessage, 
                                estimateTokenCount(observationMessage), newImageId);
                        log.debug("观察消息已保存到数据库: imageId={}", newImageId);
                    } catch (Exception e) {
                        log.warn("保存观察消息到数据库失败: {}", e.getMessage());
                    }
                } catch (Exception e) {
                    log.warn("截图失败，继续执行: {}", e.getMessage());
                }
            }
        }

        log.warn("⚠️ 达到最大工具调用次数 {}", maxToolIterations);
        return fullResponse + "\n(达到最大迭代次数)";
    }

    /**
     * 获取调度器
     */
    public TaskOrchestrator getTaskOrchestrator() {
        return taskOrchestrator;
    }

    /**
     * 带重试的执行
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
                    log.warn("⏳ API 限流/配额耗尽，等待 {}ms 后重试 ({}/{})", waitTime, attempt, maxRetries);
                    sleep(waitTime);
                } else {
                    log.error("❌ 执行失败 ({}/{}): {}", attempt, maxRetries, errorMsg);
                    if (attempt < maxRetries) {
                        sleep(retryDelayMs);
                    }
                }
            }
        }

        log.error("❌ 重试 {} 次后仍然失败", maxRetries, lastException);
        return "处理失败: " + (lastException != null ? lastException.getMessage() : "未知错误");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 检查 Agent 是否可用
     */
    public boolean isAvailable() {
        return chatModel != null && toolExecutionService.getToolCount() > 0;
    }

    /**
     * 获取模型信息
     */
    public String getModelInfo() {
        return String.format("模型: %s, 状态: %s, 工具: %d 个",
                modelAlias,
                isAvailable() ? "✅ 可用" : "❌ 不可用",
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
        log.info("🔄 对话历史已重置");
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
        } else {
            text = message.toString();
        }
        return text.length() / 4;
    }

    // ==================== Skill 集成辅助方法 ====================

    /**
     * 构建带 Skill 上下文的 System Prompt。
     * 如果有活动的 Skill 上下文，将其知识注入到 System Prompt 中。
     */
    private String buildSystemPromptWithSkillContext() {
        if (activeSkillContext == null) {
            return AgentPrompts.SYSTEM_PROMPT;
        }

        // 注入 Skill 知识到 System Prompt
        String skillInjection = activeSkillContext.toSystemPromptInjection();
        String enhancedPrompt = AgentPrompts.SYSTEM_PROMPT
                + String.format(AgentPrompts.SKILL_CONTEXT_TEMPLATE, skillInjection);

        log.info("📚 已注入 Skill 上下文: {}", activeSkillContext.getSkillName());
        return enhancedPrompt;
    }

    /**
     * 构建合并后的工具规格列表（基础工具 + Skill 工具）
     */
    private List<ToolSpecification> buildCombinedToolSpecifications() {
        List<ToolSpecification> combined = new ArrayList<>();

        // 添加基础工具
        combined.addAll(toolExecutionService.getToolSpecifications());

        // 添加 Skill 工具
        combined.addAll(skillToolSpecifications);

        return combined;
    }

    /**
     * 执行工具或 Skill。
     * 根据工具名称判断是基础工具还是 Skill 工具，分别调用对应的执行器。
     */
    private String executeToolOrSkill(String toolName, String argsJson) {
        // 首先检查是否是基础工具
        if (toolExecutionService.hasTool(toolName)) {
            return toolExecutionService.execute(toolName, argsJson);
        }

        // 检查是否是 Skill 工具
        if (isSkillTool(toolName)) {
            log.info("🎯 执行 Skill 工具: {}", toolName);
            var result = skillService.executeByToolName(toolName, argsJson);
            return result.isSuccess() ? result.getOutput() : "❌ " + result.getError();
        }

        return "❌ 未知工具: " + toolName;
    }

    /**
     * 判断是否是 Skill 工具
     */
    private boolean isSkillTool(String toolName) {
        return skillToolSpecifications.stream()
                .anyMatch(spec -> spec.name().equals(toolName));
    }

    /**
     * 判断工具是否可能影响屏幕显示
     */
    private boolean isVisualImpactTool(String toolName) {
        // 基础工具的判断
        if (toolExecutionService.hasTool(toolName)) {
            return toolExecutionService.isVisualImpactTool(toolName);
        }

        // Skill 工具默认认为有视觉影响（因为可能执行 agent: 命令）
        if (isSkillTool(toolName)) {
            return true;
        }

        return true; // 未知工具默认有影响
    }

    /**
     * 获取当前 Skill 工具数量
     */
    public int getSkillToolCount() {
        return skillToolSpecifications.size();
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
