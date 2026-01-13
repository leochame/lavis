package com.lavis.cognitive.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lavis.cognitive.AgentTools;
import com.lavis.cognitive.model.PlanStep;
import com.lavis.perception.ScreenCapturer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Dimension;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;

/**
 * 微观执行器服务 (Micro-Executor Service)
 * 
 * 核心特性：
 * 1. 【微观上下文隔离】每次执行单个步骤，使用独立的上下文 (localContext)
 * 2. 【自我修正循环】Action -> Screenshot -> Validate -> Correction
 * 3. 【阅后即焚】执行完成后，微观上下文销毁，只返回简单结果给 Planner
 * 
 * 设计哲学：
 * - 这是一个"短命"的 Worker，专注于完成单个原子任务
 * - 内部重试和修正不会污染全局 Planner 的上下文
 * - 对外只暴露简单的 Success/Failed 结果
 */
@Slf4j
@Service
public class MicroExecutorService {

    private final AgentTools agentTools;
    private final ScreenCapturer screenCapturer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // LLM 模型（由外部注入或配置）
    private ChatLanguageModel chatModel;
    private List<ToolSpecification> toolSpecifications;
    private Map<String, Method> toolMethods;
    
    @Value("${executor.max.corrections:5}")
    private int maxCorrections = 5;
    
    @Value("${executor.action.timeout.seconds:30}")
    private int actionTimeoutSeconds = 30;

    // 工具执行后等待 UI 响应的时间（毫秒）
    @Value("${executor.tool.wait.ms:500}")
    private int toolWaitMs = 500;
    
    /**
     * 动态生成执行器专用的 System Prompt
     * 根据实际的压缩图像尺寸计算坐标范围
     */
    private String generateExecutorSystemPrompt() {
        // 获取压缩后的图像尺寸
        Dimension logicalSize = screenCapturer.getScreenSize();
        int targetWidth = screenCapturer.getTargetWidth(); // 768
        // 计算压缩后的高度（保持宽高比）
        int targetHeight = (int)(targetWidth * logicalSize.height / (double)logicalSize.width);
        
        return String.format("""
        你是一个专注于执行单步操作的底层驱动程序。
        
        ## 你的唯一目标
        完成当前给定的【单个步骤】，不要思考其他步骤。
        
        ## ⚠️ 坐标系统（严格遵守！）
        截图尺寸: **%d x %d 像素**（压缩后的图像）
        - X 坐标范围: 0 ~ %d
        - Y 坐标范围: 0 ~ %d
        - ❌ 绝对禁止输出超出此范围的坐标！
        - 使用截图上的网格辅助定位
        
        ## 🔴 关键：红色十字 = 当前鼠标位置
        - 截图中的【红色十字】标记当前鼠标的精确位置
        - 【绿色圆环】标记上次点击的位置
        - 你必须时刻关注红色十字的位置！
        
        ## ⚠️ 坐标微调核心原则（必须遵守！）
        当操作未命中目标时：
        1. **禁止**盲目使用新的绝对坐标重试
        2. **必须**以红色十字（当前位置）为基准进行微调
        3. **计算**红色十字与目标的相对偏移量
        4. **微调**在当前坐标基础上加减 5-30 像素
        
        示例：
        - 红色十字在 (200, 150)，目标按钮在其右下方约 20px
        - 正确做法：调用 click(220, 170)  ← 基于当前位置 +20, +20
        - 错误做法：调用 click(400, 300)  ← 盲目猜测新坐标
        
        ## 工作流程
        1. 分析**当前最新截图**，定位红色十字位置
        2. 确定目标元素相对于红色十字的方位
        3. 调用工具执行操作
        4. 等待新截图，观察屏幕变化
        5. 如果失败，基于新的红色十字位置微调
        
        ## 重要规则
        - 每次只执行一个动作
        - 失败时，观察红色十字与目标的距离，小幅度调整
        - 始终根据**最新截图**中的红色十字位置做决策
        - 不要解释太多，直接执行操作
        
        ## 成功标准
        - 完成步骤描述中的目标即为成功
        - 通过观察截图变化来判断是否成功
        """, targetWidth, targetHeight, targetWidth, targetHeight);
    }

    public MicroExecutorService(AgentTools agentTools, ScreenCapturer screenCapturer) {
        this.agentTools = agentTools;
        this.screenCapturer = screenCapturer;
    }
    
    /**
     * 初始化 LLM 模型（由 AgentService 或配置注入）
     */
    public void initialize(ChatLanguageModel model) {
        this.chatModel = model;
        this.toolSpecifications = ToolSpecifications.toolSpecificationsFrom(agentTools);
        
        this.toolMethods = new HashMap<>();
        for (Method method : AgentTools.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                toolMethods.put(method.getName(), method);
            }
        }
        
        log.info("✅ MicroExecutorService 初始化完成，工具数: {}", toolSpecifications.size());
    }

    /**
     * 执行单个步骤（核心方法）
     * 
     * 这是 Planner 调用的入口：
     * - 传入一个 PlanStep
     * - 返回 ExecutionResult（只包含成功/失败和简要说明）
     * - 内部的所有重试、修正都不会暴露给调用者
     * 
     * @param step 要执行的步骤
     * @return 执行结果
     */
    public ExecutionResult executeStep(PlanStep step) {
        log.info("🎯 MicroExecutor 开始执行步骤 {}: {}", step.getId(), step.getDescription());
        
        if (chatModel == null) {
            return ExecutionResult.failed("MicroExecutor 未初始化");
        }
        
        step.markStarted();
        Instant deadline = Instant.now().plusSeconds(step.getTimeoutSeconds());
        
        // 【核心】创建独立的微观上下文 - 每次执行都是全新的
        // 动态生成系统提示词，包含准确的坐标范围
        List<ChatMessage> localContext = new ArrayList<>();
        localContext.add(SystemMessage.from(generateExecutorSystemPrompt()));
        
        // 执行循环
        int corrections = 0;
        String lastActionResult = null;
        
        while (corrections < step.getMaxRetries() && Instant.now().isBefore(deadline)) {
            try {
                // 1. 获取当前屏幕截图
                String screenshot = screenCapturer.captureScreenWithCursorAsBase64();
                
                // 2. 构建用户消息
                String userPrompt;
                if (corrections == 0) {
                    // 首次执行
                    userPrompt = String.format("""
                        ## 当前任务
                        %s
                        
                        请分析截图并执行必要的操作来完成此任务。
                        """, step.getDescription());
                } else {
                    // 修正执行 - 强制基于当前位置微调
                    userPrompt = String.format("""
                        ## 继续任务
                        %s
                        
                        上次操作结果: %s
                        
                        ## ⚠️ 微调指令（必须遵守！）
                        1. 首先在截图中找到【红色十字】- 这是当前鼠标位置
                        2. 判断红色十字与目标元素的相对距离（上下左右多少像素）
                        3. 基于红色十字的当前坐标进行微调，而不是猜测新坐标
                        4. 调整幅度通常在 5-30 像素之间
                        
                        ❌ 禁止：直接使用与上次完全不同的坐标
                        ✅ 正确：在红色十字位置基础上 +/- 像素微调
                        
                        请分析当前截图，如果任务未完成，基于红色十字位置微调坐标继续尝试。
                        """, step.getDescription(), lastActionResult);
                }
                
                UserMessage userMessage = UserMessage.from(
                        TextContent.from(userPrompt),
                        ImageContent.from(screenshot, "image/jpeg")
                );
                localContext.add(userMessage);
                
                // 3. 调用 LLM 决策
                Response<AiMessage> response = chatModel.generate(localContext, toolSpecifications);
                AiMessage aiMessage = response.content();
                localContext.add(aiMessage);
                
                // 4. 检查是否需要执行工具
                if (!aiMessage.hasToolExecutionRequests()) {
                    // LLM 认为任务完成或无法完成
                    String text = aiMessage.text();
                    if (text != null && (text.contains("完成") || text.contains("成功") || text.contains("已经"))) {
                        step.markSuccess(text);
                        log.info("✅ 步骤 {} 执行成功: {}", step.getId(), text);
                        return ExecutionResult.success(text);
                    } else {
                        // 可能需要继续
                        corrections++;
                        continue;
                    }
                }
                
                // 5. 执行工具调用
                List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
                StringBuilder actionResults = new StringBuilder();
                
                for (ToolExecutionRequest request : toolRequests) {
                    String toolName = request.name();
                    String toolArgs = request.arguments();
                    
                    log.info("  🔧 执行工具: {}({})", toolName, toolArgs);
                    String result = executeToolMethod(toolName, toolArgs);
                    actionResults.append(result).append("\n");
                    
                    // 添加工具结果到本地上下文
                    ToolExecutionResultMessage toolResult = ToolExecutionResultMessage.from(request, result);
                    localContext.add(toolResult);
                }
                
                lastActionResult = actionResults.toString();
                
                // 6. 等待 UI 响应，让下一轮截图能看到变化
                log.info("⏳ 等待 UI 响应 {}ms...", toolWaitMs);
                Thread.sleep(toolWaitMs);
                
                // 7. 简单判断是否可能成功（基于工具返回）
                if (lastActionResult.contains("✅") && !lastActionResult.contains("❌")) {
                    // 工具报告成功，但需要通过下一轮截图验证屏幕变化
                    log.info("工具报告成功，继续验证屏幕变化...");
                }
                
                corrections++;
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                step.markFailed("执行被中断");
                return ExecutionResult.failed("执行被中断");
            } catch (Exception e) {
                log.error("步骤执行异常: {}", e.getMessage(), e);
                corrections++;
                lastActionResult = "执行异常: " + e.getMessage();
            }
        }
        
        // 达到最大重试或超时
        String reason = corrections >= step.getMaxRetries() ? 
                "达到最大修正次数" : "执行超时";
        step.markFailed(reason);
        log.warn("❌ 步骤 {} 执行失败: {}", step.getId(), reason);
        
        return ExecutionResult.failed(reason + " - 最后结果: " + lastActionResult);
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

            JsonNode argsNode = objectMapper.readTree(argsJson);
            Class<?>[] paramTypes = method.getParameterTypes();
            java.lang.reflect.Parameter[] params = method.getParameters();
            Object[] args = new Object[paramTypes.length];

            for (int i = 0; i < params.length; i++) {
                String paramName = params[i].getName();
                JsonNode valueNode = argsNode.get(paramName);

                if (valueNode == null) {
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

            Object result = method.invoke(agentTools, args);
            return result != null ? result.toString() : "执行完成";

        } catch (Exception e) {
            log.error("工具执行失败: {} - {}", toolName, e.getMessage());
            return "工具执行错误: " + e.getMessage();
        }
    }

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

    private Object getDefaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == boolean.class) return false;
        return null;
    }

    /**
     * 执行结果 - 对外只暴露简单的结果
     */
    @Data
    public static class ExecutionResult {
        private final boolean success;
        private final String message;
        private final long executionTimeMs;
        
        private ExecutionResult(boolean success, String message, long executionTimeMs) {
            this.success = success;
            this.message = message;
            this.executionTimeMs = executionTimeMs;
        }
        
        public static ExecutionResult success(String message) {
            return new ExecutionResult(true, message, 0);
        }
        
        public static ExecutionResult failed(String reason) {
            return new ExecutionResult(false, reason, 0);
        }
        
        public static ExecutionResult of(boolean success, String message, long timeMs) {
            return new ExecutionResult(success, message, timeMs);
        }
        
        @Override
        public String toString() {
            return (success ? "✅ " : "❌ ") + message;
        }
    }
}

