package com.lavis.cognitive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lavis.perception.AXDumper;
import com.lavis.perception.ScreenCapturer;
import com.lavis.perception.UIElement;
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
 * 
 * 特性:
 * - 多模态支持 (截图 + 文本)
 * - UI 元素感知 (通过 AXDumper 获取结构化数据)
 * - 工具调用循环
 * - 智能坐标映射 (优先使用元素 ID)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentTools agentTools;
    private final ScreenCapturer screenCapturer;
    private final AXDumper axDumper;
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

    private static final String SYSTEM_PROMPT = """
        你是 Lavis，一个专业的 macOS 自动化助手。你拥有视觉能力和完整的系统控制权。
        
        ## 核心能力
        - 视觉分析：精确识别屏幕上的 UI 元素、按钮、文本框、菜单
        - 鼠标控制：移动、单击、双击、右键、拖拽、滚动
        - 键盘输入：文本输入、快捷键、特殊按键
        - 系统操作：打开/关闭应用、执行脚本、文件操作
        
        ## 元素定位策略 (优先级从高到低)
        1. **元素 ID 定位** (最精确): 当我提供 UI_ELEMENTS 列表时，优先使用元素 ID 调用工具
           - 使用 `clickElement(id)` 而不是 `click(x, y)`
           - 使用 `typeInElement(id, text)` 而不是先点击再输入
        2. **名称定位**: 使用 `clickElementByName(name)` 通过按钮文字定位
        3. **坐标定位** (兜底): 只有当元素列表中找不到目标时，才使用视觉估算坐标
        
        ## 执行规则
        1. **先查表**: 检查 UI_ELEMENTS 列表，找到目标元素的 ID
        2. **再执行**: 使用元素 ID 调用精确工具
        3. **后验证**: 执行后说明结果
        
        ## 重要提示
        - **优先使用元素 ID**: 元素列表中的坐标是精确的，比视觉估算更准确
        - 当用户要求操作时，你必须调用相应的工具来执行
        - 不要只是描述要做什么，而是实际调用工具去做
        - 点击文本框后，等待一下再输入文本
        - 遇到弹窗/对话框，优先处理
        """;
    
    @Value("${agent.ui.scan.enabled:true}")
    private boolean uiScanEnabled = true;

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
                    .baseUrl("https://docs.newapi.pro/v1/chat/completions")
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
            return processWithTools(userMessage);
        });
    }

    /**
     * 发送带截图的消息 (多模态 + 工具调用)
     * 同时提供截图和结构化 UI 元素列表，实现混合感知
     */
    public String chatWithScreenshot(String message) {
        if (chatModel == null) {
            return "❌ Agent 未初始化，请检查 API Key 配置";
        }

        log.info("📷 用户消息 (带截图): {}", message);
        
        return executeWithRetry(() -> {
            // 获取屏幕截图
            String base64Image = screenCapturer.captureScreenAsBase64();
            log.info("📸 截图大小: {} KB", base64Image.length() * 3 / 4 / 1024);
            
            // 获取 UI 元素 (混合感知模式)
            String uiContext = "";
            if (uiScanEnabled) {
                uiContext = buildUIContext();
            }
            
            // 构建增强的消息内容
            String enhancedMessage = message;
            if (!uiContext.isEmpty()) {
                enhancedMessage = message + "\n\n" + uiContext;
            }
            
            // 构建多模态用户消息
            UserMessage userMessage = UserMessage.from(
                TextContent.from(enhancedMessage),
                ImageContent.from(base64Image, "image/jpeg")
            );
            
            return processWithTools(userMessage);
        });
    }
    
    /**
     * 构建 UI 上下文信息 (发送给 LLM)
     */
    private String buildUIContext() {
        try {
            // 获取窗口信息
            AXDumper.WindowInfo windowInfo = axDumper.getActiveWindowInfo();
            
            // 快速扫描 UI 元素
            List<UIElement> elements = axDumper.quickScan();
            
            if (elements.isEmpty()) {
                log.debug("未扫描到 UI 元素");
                return "";
            }
            
            StringBuilder context = new StringBuilder();
            context.append("## 当前窗口信息\n");
            if (windowInfo != null) {
                context.append(String.format("应用: %s, 窗口: %s\n", 
                    windowInfo.appName(), windowInfo.windowTitle()));
            }
            
            context.append("\n## UI_ELEMENTS (可交互元素列表)\n");
            context.append("以下是当前屏幕上的可交互元素，请优先使用元素 ID 进行操作:\n");
            context.append("```json\n");
            context.append(axDumper.toJsonForLLM(elements));
            context.append("\n```\n");
            
            // 添加简要说明
            context.append("\n提示: 使用 clickElement(\"btn_0\") 比 click(x, y) 更精确。\n");
            
            log.info("📋 UI 上下文: {} 个元素", elements.size());
            return context.toString();
            
        } catch (Exception e) {
            log.warn("构建 UI 上下文失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 核心方法：处理消息并执行工具调用循环
     */
    private String processWithTools(UserMessage userMessage) {
        // 构建消息列表
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.addAll(chatMemory.messages());
        messages.add(userMessage);
        
        // 保存用户消息到记忆
        chatMemory.add(userMessage);
        
        StringBuilder fullResponse = new StringBuilder();
        
        // 工具调用循环
        for (int iteration = 0; iteration < maxToolIterations; iteration++) {
            log.debug("🔄 工具调用迭代 {}/{}", iteration + 1, maxToolIterations);
            
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
                
                log.info("🤖 Agent 响应: {}", fullResponse);
                return fullResponse.toString();
            }
            
            // 执行工具调用
            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
            log.info("🔧 执行 {} 个工具调用", toolRequests.size());
            
            for (ToolExecutionRequest request : toolRequests) {
                String toolName = request.name();
                String toolArgs = request.arguments();
                
                log.info("  → 调用工具: {}({})", toolName, toolArgs);
                
                // 执行工具
                String result = executeToolMethod(toolName, toolArgs);
                log.info("  ← 工具结果: {}", result);
                
                // 添加工具执行结果
                ToolExecutionResultMessage toolResult = ToolExecutionResultMessage.from(
                    request,
                    result
                );
                messages.add(toolResult);
                
                fullResponse.append(String.format("[%s] %s\n", toolName, result));
            }
            
            // 如果 AI 也有文本响应，添加到结果
            if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
                fullResponse.append(aiMessage.text()).append("\n");
            }
        }
        
        log.warn("⚠️ 达到最大工具调用次数 {}", maxToolIterations);
        return fullResponse + "\n(达到最大迭代次数)";
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
     * 执行自动化任务 (带反思循环)
     */
    public String executeTask(String task) {
        if (chatModel == null) {
            return "❌ Agent 未初始化，请检查 API Key 配置";
        }

        log.info("🚀 开始执行任务: {}", task);
        
        String prompt = String.format("""
            ## 任务
            %s
            
            ## 要求
            请立即开始执行这个任务。使用你的工具来完成操作。
            """, task);
        
        return chatWithScreenshot(prompt);
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
