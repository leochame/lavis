package com.lavis.cognitive;

import com.lavis.cognitive.executor.ToolExecutionService;
import com.lavis.cognitive.orchestrator.TaskOrchestrator;
import com.lavis.perception.ScreenCapturer;
import com.lavis.service.llm.LlmFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * M2 思考模块 - Agent 服务
 * 核心 AI 服务，整合 LLM 模型与工具调用
 * 支持多模态 + 工具调用
 * 
 * 职责：
 * - 管理对话记忆（ChatMemory）
 * - 处理多模态消息（文本 + 截图）
 * - 协调工具调用循环
 * - 初始化 TaskOrchestrator
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final ScreenCapturer screenCapturer;
    private final TaskOrchestrator taskOrchestrator;
    private final ToolExecutionService toolExecutionService;
    private final LlmFactory llmFactory;

    @Value("${agent.retry.max:3}")
    private int maxRetries;

    @Value("${agent.retry.delay.ms:2000}")
    private long retryDelayMs;

    @Value("${agent.max.tool.iterations:10}")
    private int maxToolIterations;

    /** 使用的模型别名（可通过配置切换） */
    @Value("${agent.model.alias:modela}")
    private String modelAlias;

    private ChatLanguageModel chatModel;
    private ChatMemory chatMemory;

    private static final String SYSTEM_PROMPT = """
            你是 Lavis，一个专业的 macOS 自动化助手。你拥有视觉能力和完整的系统控制权。

            ## 核心能力
            - 视觉分析：精确识别屏幕上的 UI 元素、按钮、文本框、菜单
            - 鼠标控制：移动、单击、双击、右键、拖拽、滚动
            - 键盘输入：文本输入、快捷键、特殊按键
            - 系统操作：打开/关闭应用、执行脚本、文件操作

            ## ⚠️ 坐标系统（重要！）
            - 截图中【红色十字】标记显示当前鼠标位置及其坐标
            - 截图中【绿色圆环】标记显示上一次点击位置
            - 使用截图中显示的坐标进行操作

            ## 🎯 锚点定位策略（关键！）
            **禁止**盲目猜测坐标，**必须**基于视觉锚点定位：
            1. 识别目标元素的视觉特征（颜色、文字、图标、位置关系）
            2. 参考红色十字当前位置估算目标坐标
            3. 执行操作后观察绿色圆环是否命中目标
            4. 如果偏离，基于当前位置微调 5-30 像素

            ## 截图中的视觉标记
            - 🔴 **红色十字 + 坐标**：当前鼠标位置
            - 🟢 **绿色圆环 + 标签**：上一次点击位置

            ## 执行规则
            1. **先观察**: 仔细分析**最新的截图**，识别 UI 元素位置
            2. **再规划**: 制定清晰的执行步骤
            3. **后执行**: 调用工具执行操作，**每次只执行一个动作**
            4. **要验证**: 执行后会收到**新的截图**，观察屏幕变化
            5. **会反思**: 根据新截图判断操作是否成功，决定下一步

            ## 关键行为准则
            - 每次操作后，你会收到**更新后的屏幕截图**
            - 始终根据**最新截图**做决策，不要依赖记忆中的旧画面
            - 如果工具返回"成功"但截图显示没变化，可能需要等待加载
            - 如果同一操作重复3次仍无效，尝试不同策略

            ## 重要提示
            - 当用户要求操作时，你必须调用相应的工具来执行
            - 不要只是描述要做什么，而是实际调用工具去做
            - 点击文本框后，等待一下再输入文本
            - 遇到弹窗/对话框，优先处理
            """;

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

            // 初始化聊天记忆
            this.chatMemory = MessageWindowChatMemory.withMaxMessages(20);

            // 初始化调度器（传递 LLM 模型给 Planner 和 Executor）
            taskOrchestrator.initialize(chatModel);

            log.info("✅ AgentService 初始化完成 - 模型: {}, 工具数: {}",
                    modelAlias, toolExecutionService.getToolCount());
        } catch (Exception e) {
            log.error("❌ AgentService 初始化失败", e);
        }
    }

    /**
     * 发送纯文本消息 (支持工具调用)
     * 
     * @deprecated 建议使用 {@link #chatWithScreenshot(String)}，提供更强的视觉感知能力
     */
    @Deprecated(since = "2.0")
    public String chat(String message) {
        if (chatModel == null) {
            return "❌ Agent 未初始化，请检查 API Key 配置";
        }

        log.info("📝 用户消息: {}", message);
        return executeWithRetry(() -> {
            UserMessage userMessage = UserMessage.from(message);
            return processWithTools(userMessage, 0);
        });
    }

    /**
     * 发送带截图的消息 (多模态 + 工具调用)
     * 截图会显示鼠标位置（红色十字）和上次点击位置（绿色圆环），便于 AI 反思
     * 
     * @param message 用户消息
     * @return 执行结果
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

        return executeWithRetry(() -> {
            // 获取带标记的屏幕截图（显示鼠标位置和上次点击位置）
            String base64Image = screenCapturer.captureScreenWithCursorAsBase64();
            log.info("📸 截图大小: {} KB (含鼠标/点击标记)", base64Image.length() * 3 / 4 / 1024);

            // 构建多模态用户消息
            UserMessage userMessage = UserMessage.from(
                    TextContent.from(message),
                    ImageContent.from(base64Image, "image/jpeg"));

            return processWithTools(userMessage, maxSteps);
        });
    }

    /**
     * 核心方法：处理消息并执行工具调用循环
     * 
     * 【关键改进】工具执行后重新截图，让模型"看见"屏幕变化
     * 
     * 执行流程：
     * 1. 发送初始消息（含截图）给模型
     * 2. 模型决定调用工具
     * 3. 执行工具
     * 4. 【新增】等待 UI 响应 + 重新截图
     * 5. 【新增】将新截图作为观察结果注入上下文
     * 6. 模型根据新截图决定下一步
     * 
     * @param userMessage 用户消息
     * @param maxSteps    最大执行步数限制。如果 > 0，则限制单次调用的最大工具调用次数；如果 <= 0，则使用全局配置
     *                    maxToolIterations
     */
    private String processWithTools(UserMessage userMessage, int maxSteps) {
        // 构建消息列表
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.addAll(chatMemory.messages());
        messages.add(userMessage);

        // 保存用户消息到记忆
        chatMemory.add(userMessage);

        StringBuilder fullResponse = new StringBuilder();

        // 工具调用循环 - 使用传入的 maxSteps，如果 <= 0 则使用全局配置（兼容旧代码）
        int limit = (maxSteps > 0) ? maxSteps : this.maxToolIterations;
        log.debug("工具调用循环限制: {} 步", limit);

        for (int iteration = 0; iteration < limit; iteration++) {
            log.info("🔄 工具调用迭代 {}/{}", iteration + 1, maxToolIterations);

            // 调用模型
            Response<AiMessage> response = chatModel.generate(messages, toolExecutionService.getToolSpecifications());
            AiMessage aiMessage = response.content();
            log.info("🤖 Agent 响应: {}", aiMessage);
            // 添加 AI 响应到消息列表
            messages.add(aiMessage);

            // 检查是否有工具调用请求
            if (!aiMessage.hasToolExecutionRequests()) {
                // 没有工具调用，返回文本响应
                String textResponse = aiMessage.text();
                if (textResponse != null && !textResponse.isBlank()) {
                    fullResponse.append(textResponse);
                }

                // 保存 AI 响应到记忆
                chatMemory.add(aiMessage);

                log.info("🤖 Agent 响应: {}", fullResponse);
                return fullResponse.toString();
            }

            // 执行工具调用
            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
            log.info("🔧 执行 {} 个工具调用", toolRequests.size());

            StringBuilder toolResultsSummary = new StringBuilder();
            boolean hasVisualImpact = false; // 是否有可能影响屏幕的操作

            for (ToolExecutionRequest request : toolRequests) {
                String toolName = request.name();
                String toolArgs = request.arguments();

                log.info("  → 调用工具: {}({})", toolName, toolArgs);

                // 通过 ToolExecutionService 执行工具
                String result = toolExecutionService.execute(toolName, toolArgs);
                log.info("  ← 工具结果: {}", result.split("\n")[0]); // 只打印第一行

                // 添加工具执行结果
                ToolExecutionResultMessage toolResult = ToolExecutionResultMessage.from(
                        request,
                        result);
                messages.add(toolResult);

                toolResultsSummary.append(String.format("[%s] %s\n", toolName, result.split("\n")[0]));

                // 判断是否是可能影响屏幕的操作
                if (toolExecutionService.isVisualImpactTool(toolName)) {
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

                    // 重新截图
                    String newScreenshot = screenCapturer.captureScreenWithCursorAsBase64();
                    log.info("📸 重新截图完成，注入新的视觉观察");

                    // 构建观察消息，告诉模型这是操作后的新截图
                    String observationText = String.format("""
                            ## 📷 操作后的屏幕观察

                            上一步执行结果:
                            %s

                            请仔细观察**当前最新截图**，判断：
                            1. 操作是否成功？屏幕是否发生了预期变化？
                            2. 如果成功，下一步应该做什么？
                            3. 如果失败或无变化，需要如何调整？

                            **注意**：始终根据这张最新截图做决策！
                            """, toolResultsSummary.toString());

                    UserMessage observationMessage = UserMessage.from(
                            TextContent.from(observationText),
                            ImageContent.from(newScreenshot, "image/jpeg"));
                    messages.add(observationMessage);
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
     * 重置对话历史
     */
    public void resetConversation() {
        if (chatMemory != null) {
            chatMemory.clear();
        }
        log.info("🔄 对话历史已重置");
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
