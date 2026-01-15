package com.lavis.cognitive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lavis.cognitive.orchestrator.TaskOrchestrator;
import com.lavis.perception.ScreenCapturer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;

/**
 * M2 思考模块 - Agent 服务
 * 核心 AI 服务，整合 Gemini 模型与工具调用
 * 支持多模态 + 工具调用
 *
 * 【重要改进】
 * - 集成 TaskContext 进行上下文管理
 * - 支持死循环检测和自动停止
 * - 工具执行结果包含详细偏差信息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentTools agentTools;
    private final ScreenCapturer screenCapturer;
    private final TaskOrchestrator taskOrchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.0-flash}")
    private String modelName;

    @Value("${agent.retry.max:3}")
    private int maxRetries;

    @Value("${agent.retry.delay.ms:2000}")
    private long retryDelayMs;

    @Value("${agent.max.tool.iterations:10}")
    private int maxToolIterations;

    private ChatLanguageModel chatModel;
    private List<ToolSpecification> toolSpecifications;
    private Map<String, Method> toolMethods;
    private ChatMemory chatMemory;

    // 当前任务上下文
    private TaskContext currentTaskContext;

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
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("⚠️ Gemini API Key 未配置，请设置 gemini.api.key");
            return;
        }

        try {
            // 初始化 Gemini 模型
//            this.chatModel = GoogleAiGeminiChatModel.builder()
//                    .apiKey(apiKey)
//                    .modelName(modelName)
//                    .temperature(0.4)
//                    .timeout(Duration.ofSeconds(60))
//                    .maxRetries(maxRetries)
//                    .build();
            this.chatModel = OpenAiChatModel.builder()
                    .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature(0.4)
                    .timeout(Duration.ofSeconds(60))
                    .maxRetries(maxRetries)
                    .build();


            // 初始化工具规格
            this.toolSpecifications = ToolSpecifications.toolSpecificationsFrom(agentTools);

            // 建立工具名称到方法的映射
            this.toolMethods = new HashMap<>();
            for (Method method : AgentTools.class.getDeclaredMethods()) {
                if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                    toolMethods.put(method.getName(), method);
                }
            }

            // 初始化聊天记忆
            this.chatMemory = MessageWindowChatMemory.withMaxMessages(20);
            
            // 初始化调度器（传递 LLM 模型给 Planner 和 Executor）
            taskOrchestrator.initialize(chatModel);

            log.info("✅ AgentService 初始化完成 - 模型: {}, 工具数: {}", modelName, toolSpecifications.size());
            log.info("📦 可用工具: {}", toolMethods.keySet());
        } catch (Exception e) {
            log.error("❌ AgentService 初始化失败", e);
        }
    }

    /**
     * 发送纯文本消息 (支持工具调用)
     */
    public String chat(String message) {
        if (chatModel == null) {
            return "❌ Agent 未初始化，请检查 API Key 配置";
        }

        log.info("📝 用户消息: {}", message);
        return executeWithRetry(() -> {
            UserMessage userMessage = UserMessage.from(message);
            return processWithTools(userMessage, 0); // 使用全局配置
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
     * @param message 用户消息
     * @param maxSteps 最大执行步数限制。如果 > 0，则限制单次调用的最大工具调用次数；如果 <= 0，则使用全局配置 maxToolIterations
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
                ImageContent.from(base64Image, "image/jpeg")
            );

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
     * @param maxSteps 最大执行步数限制。如果 > 0，则限制单次调用的最大工具调用次数；如果 <= 0，则使用全局配置 maxToolIterations
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

            // 【死循环检测】检查是否应该停止
            if (currentTaskContext != null && currentTaskContext.shouldStop()) {
                String stopReason = "🛑 检测到死循环或失败率过高，任务自动停止。\n" +
                        currentTaskContext.generateContextSummary();
                log.warn(stopReason);
                fullResponse.append(stopReason);
                return fullResponse.toString();
            }

            // 调用模型
            Response<AiMessage> response = chatModel.generate(messages, toolSpecifications);
            AiMessage aiMessage = response.content();

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

                // 标记任务完成
                if (currentTaskContext != null) {
                    currentTaskContext.markCompleted();
                }

                log.info("🤖 Agent 响应: {}", fullResponse);
                return fullResponse.toString();
            }

            // 执行工具调用
            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
            log.info("🔧 执行 {} 个工具调用", toolRequests.size());

            StringBuilder toolResultsSummary = new StringBuilder();
            boolean hasVisualImpact = false;  // 是否有可能影响屏幕的操作

            for (ToolExecutionRequest request : toolRequests) {
                String toolName = request.name();
                String toolArgs = request.arguments();

                log.info("  → 调用工具: {}({})", toolName, toolArgs);

                // 执行工具（工具会自动记录到 TaskContext）
                String result = executeToolMethod(toolName, toolArgs);
                log.info("  ← 工具结果: {}", result.split("\n")[0]);  // 只打印第一行

                // 添加工具执行结果
                ToolExecutionResultMessage toolResult = ToolExecutionResultMessage.from(
                    request,
                    result
                );
                messages.add(toolResult);

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
                        ImageContent.from(newScreenshot, "image/jpeg")
                    );
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
     * 判断工具是否可能影响屏幕显示
     */
    private boolean isVisualImpactTool(String toolName) {
        // 这些工具执行后可能会改变屏幕内容
        return switch (toolName) {
            case "click", "doubleClick", "rightClick", "drag" -> true;
            case "typeText", "pressEnter", "pressTab", "pressEscape" -> true;
            case "openApplication", "quitApplication", "openURL", "openFile" -> true;
            case "scroll", "paste", "selectAll", "save", "undo" -> true;
            case "executeAppleScript", "executeShell", "revealInFinder" -> true;
            // 【修复】wait 工具通常用于等待屏幕状态变化（如页面加载），需要重新截图以观察变化
            case "wait" -> true;
            // 这些工具只是获取信息，不改变屏幕
            case "moveMouse", "getMouseInfo", "verifyClickPosition", "captureScreen" -> false;
            case "getActiveApp", "getActiveWindowTitle", "copy" -> false;
            case "showNotification" -> false;
            default -> true;  // 未知工具默认认为有影响
        };
    }

    /**
     * 通过反射执行工具方法
     */
    private String executeToolMethod(String toolName, String argsJson) {
        try {
            Method method = toolMethods.get(toolName);
            if (method == null) {
                return "错误: 未找到工具 " + toolName;
            }

            // 解析参数
            JsonNode argsNode = objectMapper.readTree(argsJson);
            Class<?>[] paramTypes = method.getParameterTypes();
            java.lang.reflect.Parameter[] params = method.getParameters();
            Object[] args = new Object[paramTypes.length];

            for (int i = 0; i < params.length; i++) {
                String paramName = params[i].getName();
                JsonNode valueNode = argsNode.get(paramName);

                if (valueNode == null) {
                    // 尝试用参数位置匹配
                    Iterator<JsonNode> elements = argsNode.elements();
                    int idx = 0;
                    while (elements.hasNext() && idx <= i) {
                        if (idx == i) {
                            valueNode = elements.next();
                            break;
                        }
                        elements.next();
                        idx++;
                    }
                }

                if (valueNode != null) {
                    args[i] = convertValue(valueNode, paramTypes[i]);
                } else {
                    args[i] = getDefaultValue(paramTypes[i]);
                }
            }

            // 调用方法
            Object result = method.invoke(agentTools, args);
            return result != null ? result.toString() : "执行完成";

        } catch (Exception e) {
            log.error("工具执行失败: {} - {}", toolName, e.getMessage(), e);
            return "工具执行错误: " + e.getMessage();
        }
    }

    /**
     * 转换 JSON 值到 Java 类型
     */
    private Object convertValue(JsonNode node, Class<?> type) {
        if (type == int.class || type == Integer.class) {
            return node.asInt();
        } else if (type == long.class || type == Long.class) {
            return node.asLong();
        } else if (type == double.class || type == Double.class) {
            return node.asDouble();
        } else if (type == boolean.class || type == Boolean.class) {
            return node.asBoolean();
        } else if (type == String.class) {
            return node.asText();
        }
        return node.asText();
    }

    /**
     * 获取默认值
     */
    private Object getDefaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == boolean.class) return false;
        return null;
    }

    /**
     * 执行自动化任务 (传统模式 - 带上下文管理和反思循环)
     */
    public String executeTask(String task) {
        if (chatModel == null) {
            return "❌ Agent 未初始化，请检查 API Key 配置";
        }

        log.info("🚀 开始执行任务: {}", task);

        // 创建任务上下文
        this.currentTaskContext = new TaskContext(task);
        agentTools.setTaskContext(currentTaskContext);

        String prompt = String.format("""
            ## 任务
            %s
            
            ## 要求
            请立即开始执行这个任务。使用你的工具来完成操作。
            
            ## 重要提示
            - 每次操作后会返回详细的执行结果和偏差信息
            - 如果发现同一位置多次点击无效，请尝试调整坐标或使用不同操作
            - 注意观察截图中的绿色圆环（上次点击位置）确认是否命中目标
            - 如果出现"重复操作"警告，请改变策略
            """, task);

        try {
            return chatWithScreenshot(prompt);
        } finally {
            // 任务结束后清理
            if (currentTaskContext != null) {
                log.info("📊 任务统计: 总操作 {} 次，重复 {} 次",
                        currentTaskContext.getTotalActions(),
                        currentTaskContext.getRepeatedActions());
            }
        }
    }
    
    /**
     * 【新架构】使用 Plan-Execute 模式执行复杂任务
     * 
     * 这是新的双层大脑架构的入口：
     * 1. Planner 负责将用户目标拆解为步骤
     * 2. Executor 逐步执行每个步骤（独立上下文，自我修正）
     * 3. 步骤间的上下文保持"干净"，只记录高层状态
     * 
     * @param userGoal 用户目标（自然语言描述）
     * @return 执行结果
     */
    public String executePlanTask(String userGoal) {
        if (chatModel == null) {
            return "❌ Agent 未初始化，请检查 API Key 配置";
        }
        
        log.info("🚀 [Plan-Execute 模式] 开始执行目标: {}", userGoal);
        
        try {
            TaskOrchestrator.OrchestratorResult result = taskOrchestrator.executeGoal(userGoal);
            
            StringBuilder response = new StringBuilder();
            response.append(result.toString()).append("\n\n");
            
            if (result.getPlan() != null) {
                response.append(result.getPlan().generateSummary());
            }
            
            return response.toString();
            
        } catch (Exception e) {
            log.error("Plan-Execute 执行失败", e);
            return "❌ 执行失败: " + e.getMessage();
        }
    }
    
    /**
     * 获取调度器
     */
    public TaskOrchestrator getTaskOrchestrator() {
        return taskOrchestrator;
    }

    /**
     * 获取当前任务上下文
     */
    public TaskContext getCurrentTaskContext() {
        return currentTaskContext;
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
        return chatModel != null && toolSpecifications != null;
    }

    /**
     * 获取模型信息
     */
    public String getModelInfo() {
        return String.format("模型: %s, 状态: %s, 工具: %d 个",
            modelName,
            isAvailable() ? "✅ 可用" : "❌ 不可用",
            toolMethods != null ? toolMethods.size() : 0);
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
