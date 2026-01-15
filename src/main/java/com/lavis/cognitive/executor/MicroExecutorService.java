package com.lavis.cognitive.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lavis.cognitive.AgentTools;
import com.lavis.cognitive.context.GlobalContext;
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
 * 微观执行器服务 (Micro-Executor Service) - 战术层
 * 
 * 【架构升级】实现 M-E-R (记忆-执行-反思) 完整闘环
 * 
 * 核心特性：
 * 1. 【Memory 记忆】从 GlobalContext 读取"前情提要"，知道"我在哪"、"我刚才做了什么"
 * 2. 【Execution 执行】基于当前观测和记忆做出决策，执行原子操作
 * 3. 【Reflection 反思】Tool Execution -> Wait -> Re-capture -> 强制反思
 * 4. 【锚点定位】基于视觉锚点定位目标，而非盲目坐标点击
 * 5. 【验尸报告】失败时返回详细的 PostMortem，供 Planner 决策
 * 6. 【微观上下文隔离】执行完成后销毁上下文，只将结果同步回 GlobalContext
 * 
 * 设计哲学：
 * - 这是一个"熟练工"，而非"机械臂"
 * - 能自行解决琐碎问题，无需事事上报给 Planner
 * - 只有真正搞不定时才上报异常（带验尸报告）
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
     * 
     * 【架构升级】
     * - 使用逻辑屏幕坐标范围
     * - 支持锚点定位
     * - 移除网格线描述（解决坐标幻觉问题）
     * - 接收 GlobalContext 注入的"前情提要"
     */
    private String generateExecutorSystemPrompt(GlobalContext globalContext) {
        // 获取逻辑屏幕尺寸
        Dimension logicalSize = screenCapturer.getScreenSize();
        
        StringBuilder sb = new StringBuilder();
        
        // 基础角色定义
        sb.append(String.format("""
        你是一个**战术执行专家**（熟练工角色），负责完成里程碑级任务的具体执行。
        
        ## 🎯 核心理念：M-E-R 闭环
        你拥有完整的 记忆-执行-反思 闭环能力：
        1. **Memory（记忆）**: 你知道"我在哪"、"我刚才做了什么"
        2. **Execution（执行）**: 基于当前观测和记忆做出决策
        3. **Reflection（反思）**: 每次操作后观察屏幕变化，判断是否成功
        
        ## ⚠️ 坐标系统（严格遵守！）
        屏幕尺寸: **%d x %d 像素**（逻辑屏幕坐标）
        - X 坐标范围: 0 ~ %d
        - Y 坐标范围: 0 ~ %d
        
        **重要**: 
        - 截图中显示的坐标就是你需要使用的坐标
        - 红色十字标记显示当前鼠标位置及其坐标
        - 绿色圆环标记显示上次点击位置
        
        ## 🔴 视觉标记说明
        - 【红色十字 + 坐标】: 当前鼠标位置
        - 【绿色圆环 + 标签】: 上一次点击位置
        
        ## 🎯 锚点定位策略（关键！）
        **禁止**盲目猜测坐标，**必须**基于视觉锚点定位：
        
        1. **寻找锚点**: 识别目标按钮/输入框的视觉特征（颜色、文字、图标）
        2. **相对定位**: 基于锚点和当前鼠标位置估算目标的精确坐标
        3. **验证命中**: 执行后观察绿色圆环是否落在目标上
        4. **微调修正**: 如果偏离，基于当前位置 +/- 5-30 像素微调
        
        ## 自主处理能力
        你**无需上报给 Planner**，可自行处理以下情况：
        - 弹窗/对话框：自行关闭或确认
        - 加载延迟：自行等待并重新截图
        - 点击偏移：自行微调坐标重试
        - 滚动查找：自行滚动寻找目标元素
        
        ## 执行规则
        - 每次只执行**一个**动作（单步原则）
        - 始终根据**最新截图**做决策
        - 不要解释太多，直接执行操作
        - 如果截图中看到目标状态已达成，报告"任务完成"
        
        """, logicalSize.width, logicalSize.height, logicalSize.width, logicalSize.height));
        
        // 【新增】注入 GlobalContext 的"前情提要"
        if (globalContext != null) {
            sb.append("## 📋 前情提要（你的记忆）\n");
            sb.append(globalContext.generateContextInjection());
        }
        
        return sb.toString();
    }
    
    /**
     * 兼容旧调用的重载方法
     */
    private String generateExecutorSystemPrompt() {
        return generateExecutorSystemPrompt(null);
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
     * 执行单个步骤（核心方法 - M-E-R 闘环）
     * 
     * 【架构升级】实现完整的 记忆-执行-反思 闭环：
     * 1. Memory: 从 GlobalContext 读取"前情提要"
     * 2. Execution: 基于当前观测和记忆做出决策
     * 3. Reflection: 执行后强制反思，判断是否成功
     * 
     * @param step 要执行的步骤（里程碑级）
     * @return 执行结果（含验尸报告）
     */
    public ExecutionResult executeStep(PlanStep step) {
        return executeStep(step, null);
    }
    
    /**
     * 执行单个步骤（核心方法 - M-E-R 闘环）- 带 GlobalContext
     * 
     * @param step 要执行的步骤（里程碑级）
     * @param globalContext 全局上下文（宏观记忆）
     * @return 执行结果（含验尸报告）
     */
    public ExecutionResult executeStep(PlanStep step, GlobalContext globalContext) {
        log.info("🎯 MicroExecutor 开始执行里程碑 {}: {}", step.getId(), step.getDescription());
        
        if (chatModel == null) {
            return ExecutionResult.failed("MicroExecutor 未初始化", null);
        }
        
        step.markStarted();
        
        // 根据步骤复杂度动态设置参数
        int effectiveMaxRetries = step.getMaxRetries();
        int effectiveTimeoutSeconds = step.getTimeoutSeconds();
        log.info("   📊 复杂度: {}, 最大重试: {}, 超时: {}秒", 
                step.getComplexity(), effectiveMaxRetries, effectiveTimeoutSeconds);
        
        Instant deadline = Instant.now().plusSeconds(effectiveTimeoutSeconds);
        
        // ========== Memory: 创建微观上下文，注入宏观记忆 ==========
        List<ChatMessage> localContext = new ArrayList<>();
        // 【关键】使用带 GlobalContext 的 System Prompt
        localContext.add(SystemMessage.from(generateExecutorSystemPrompt(globalContext)));
        
        // 记录尝试过的策略（用于验尸报告）
        List<String> attemptedStrategies = new ArrayList<>();
        String lastScreenState = "初始状态";
        String lastActionSummary = null;
        
        // 执行循环
        int corrections = 0;
        String lastActionResult = null;
        
        while (corrections < effectiveMaxRetries && Instant.now().isBefore(deadline)) {
            try {
                // ========== Execution: 观察-决策-行动 ==========
                
                // 1. 观察：获取当前屏幕截图
                String screenshot = screenCapturer.captureScreenWithCursorAsBase64();
                
                // 2. 决策：构建提示词，让 LLM 决策
                String userPrompt = buildMERPrompt(step, corrections, lastActionResult, globalContext);
                
                UserMessage userMessage = UserMessage.from(
                        TextContent.from(userPrompt),
                        ImageContent.from(screenshot, "image/jpeg")
                );
                localContext.add(userMessage);
                
                // 调用 LLM 决策
                Response<AiMessage> response = chatModel.generate(localContext, toolSpecifications);
                AiMessage aiMessage = response.content();
                localContext.add(aiMessage);
                
                // 检查是否需要执行工具
                if (!aiMessage.hasToolExecutionRequests()) {
                    // LLM 认为任务完成或无法完成
                    String text = aiMessage.text();
                    if (text != null && isTaskCompleted(text, step)) {
                        step.markSuccess(text);
                        log.info("✅ 里程碑 {} 达成: {}", step.getId(), text);
                        
                        // 【新增】更新 GlobalContext
                        if (globalContext != null) {
                            globalContext.updateFromExecution(text, "任务完成", true);
                        }
                        
                        return ExecutionResult.success(text, attemptedStrategies);
                    } else {
                        // 可能需要继续
                        corrections++;
                        attemptedStrategies.add("LLM 无操作建议: " + (text != null ? truncate(text, 50) : "无"));
                        continue;
                    }
                }
                
                // 3. 行动：执行工具（单步原则）
                List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
                StringBuilder actionResults = new StringBuilder();
                
                for (ToolExecutionRequest request : toolRequests) {
                    String toolName = request.name();
                    String toolArgs = request.arguments();
                    
                    log.info("  🔧 执行工具: {}({})", toolName, toolArgs);
                    String result = executeToolMethod(toolName, toolArgs);
                    actionResults.append(result).append("\n");
                    
                    // 记录策略
                    String strategyRecord = String.format("%s(%s) -> %s", 
                            toolName, toolArgs.length() > 30 ? toolArgs.substring(0, 30) + "..." : toolArgs, 
                            result.split("\n")[0]);
                    attemptedStrategies.add(strategyRecord);
                    lastActionSummary = strategyRecord;
                    
                    // 添加工具结果到本地上下文
                    ToolExecutionResultMessage toolResult = ToolExecutionResultMessage.from(request, result);
                    localContext.add(toolResult);
                }
                
                lastActionResult = actionResults.toString();
                
                // ========== Reflection: 等待-重新截图-强制反思 ==========
                
                // 等待 UI 响应
                log.info("⏳ 等待 UI 响应 {}ms...", toolWaitMs);
                Thread.sleep(toolWaitMs);
                
                // 【关键】重新截图并强制反思
                String newScreenshot = screenCapturer.captureScreenWithCursorAsBase64();
                
                // 构建反思提示
                String reflectionPrompt = buildReflectionPrompt(step, lastActionResult);
                UserMessage reflectionMessage = UserMessage.from(
                        TextContent.from(reflectionPrompt),
                        ImageContent.from(newScreenshot, "image/jpeg")
                );
                localContext.add(reflectionMessage);
                
                // 调用 LLM 进行反思
                Response<AiMessage> reflectionResponse = chatModel.generate(localContext, toolSpecifications);
                AiMessage reflectionAi = reflectionResponse.content();
                localContext.add(reflectionAi);
                
                // 解析反思结果
                String reflectionText = reflectionAi.text();
                ReflectionDecision decision = parseReflectionDecision(reflectionText, step);
                
                log.info("🔍 反思结果: {}", decision);
                
                switch (decision) {
                    case SUCCESS -> {
                        // 任务完成
                        step.markSuccess(reflectionText);
                        log.info("✅ 里程碑 {} 达成 (反思确认)", step.getId());
                        
                        // 更新 GlobalContext
                        if (globalContext != null) {
                            globalContext.updateFromExecution(reflectionText, lastActionSummary, true);
                        }
                        
                        return ExecutionResult.success(reflectionText, attemptedStrategies);
                    }
                    case CONTINUE -> {
                        // 继续执行（工具成功但任务未完成）
                        lastScreenState = "操作成功，继续执行";
                        if (globalContext != null) {
                            globalContext.addActionSummary(lastActionSummary, "继续", true);
                        }
                    }
                    case RETRY -> {
                        // 需要修正重试
                        lastScreenState = "操作需要修正: " + truncate(reflectionText, 50);
                        if (globalContext != null) {
                            globalContext.addActionSummary(lastActionSummary, "需要修正", false);
                        }
                    }
                    case FAIL -> {
                        // 无法解决，上报失败
                        lastScreenState = "操作失败: " + truncate(reflectionText, 50);
                    }
                }
                
                // 如果 LLM 在反思时也请求了工具，说明它想继续操作
                if (reflectionAi.hasToolExecutionRequests()) {
                    // 继续下一轮循环执行新的工具
                    log.info("🔄 反思时发现需要继续操作...");
                }
                
                corrections++;
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return createFailedResult(step, "执行被中断", lastScreenState, attemptedStrategies, 
                        PlanStep.PostMortem.FailureReason.UNKNOWN, globalContext);
            } catch (Exception e) {
                log.error("步骤执行异常: {}", e.getMessage(), e);
                corrections++;
                lastActionResult = "执行异常: " + e.getMessage();
                attemptedStrategies.add("异常: " + e.getMessage());
            }
        }
        
        // 达到最大重试或超时 - 生成验尸报告
        PlanStep.PostMortem.FailureReason failureReason = 
                corrections >= effectiveMaxRetries ? 
                        PlanStep.PostMortem.FailureReason.INFINITE_LOOP : 
                        PlanStep.PostMortem.FailureReason.TIMEOUT;
        
        String reason = corrections >= effectiveMaxRetries ? 
                "达到最大修正次数 (" + effectiveMaxRetries + ")" : "执行超时";
        
        return createFailedResult(step, reason, lastScreenState, attemptedStrategies, failureReason, globalContext);
    }
    
    /**
     * 反思决策类型
     */
    private enum ReflectionDecision {
        SUCCESS,    // 任务完成
        CONTINUE,   // 继续执行
        RETRY,      // 需要修正重试
        FAIL        // 无法解决
    }
    
    /**
     * 构建反思提示词
     */
    private String buildReflectionPrompt(PlanStep step, String lastActionResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 📷 操作后的屏幕观察（强制反思）\n\n");
        
        sb.append("上一步操作结果:\n");
        sb.append(lastActionResult).append("\n\n");
        
        sb.append("## 当前任务\n");
        sb.append(step.getDescription()).append("\n\n");
        
        if (step.getDefinitionOfDone() != null) {
            sb.append("## ✅ 完成标准\n");
            sb.append(step.getDefinitionOfDone()).append("\n\n");
        }
        
        sb.append("""
                ## 请判断
                仔细观察**当前最新截图**，回答：
                
                1. 操作是否成功执行？屏幕是否发生了预期变化？
                2. 任务是否已经完成？（对照完成标准）
                3. 如果未完成，下一步应该做什么？
                
                **回复格式**（选择一个）：
                - 如果任务已完成: "SUCCESS: [完成描述]"
                - 如果需要继续: "CONTINUE: [下一步操作]" 并调用工具
                - 如果操作失败需要修正: "RETRY: [修正建议]" 并调用工具
                - 如果无法解决: "FAIL: [失败原因]"
                """);
        
        return sb.toString();
    }
    
    /**
     * 解析反思决策
     */
    private ReflectionDecision parseReflectionDecision(String text, PlanStep step) {
        if (text == null) {
            return ReflectionDecision.CONTINUE;
        }
        
        String upperText = text.toUpperCase();
        
        // 显式状态判断
        if (upperText.contains("SUCCESS:") || upperText.contains("任务完成") || upperText.contains("已完成")) {
            return ReflectionDecision.SUCCESS;
        }
        if (upperText.contains("FAIL:") || upperText.contains("无法") || upperText.contains("失败")) {
            return ReflectionDecision.FAIL;
        }
        if (upperText.contains("RETRY:") || upperText.contains("修正") || upperText.contains("重试")) {
            return ReflectionDecision.RETRY;
        }
        
        // 检查是否符合完成标准
        if (isTaskCompleted(text, step)) {
            return ReflectionDecision.SUCCESS;
        }
        
        // 默认继续
        return ReflectionDecision.CONTINUE;
    }
    
    /**
     * 辅助方法：截断字符串
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
    
    /**
     * 构建 M-E-R 循环的提示词
     * 
     * @param step 当前步骤
     * @param corrections 已修正次数
     * @param lastActionResult 上次操作结果
     * @param globalContext 全局上下文
     */
    private String buildMERPrompt(PlanStep step, int corrections, String lastActionResult, GlobalContext globalContext) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("## 当前里程碑任务\n");
        prompt.append(step.getDescription()).append("\n\n");
        
        // 注入完成状态定义（Definition of Done）
        if (step.getDefinitionOfDone() != null && !step.getDefinitionOfDone().isEmpty()) {
            prompt.append("## ✅ 完成标准 (Definition of Done)\n");
            prompt.append(step.getDefinitionOfDone()).append("\n");
            prompt.append("当你在截图中看到上述状态时，任务即为完成。\n\n");
        }
        
        if (corrections == 0) {
            // 首次执行
            prompt.append("""
                ## 执行指令
                请分析截图，使用锚点定位策略找到目标元素，然后执行必要的操作。
                
                锚点定位步骤：
                1. 识别目标元素的视觉特征（颜色、文字、图标、位置关系）
                2. 基于特征在截图中定位目标
                3. 参考红色十字（当前鼠标位置）估算精确坐标
                4. 执行**一个**操作（单步原则）
                """);
        } else {
            // 修正执行
            prompt.append("## 继续执行（第 ").append(corrections + 1).append(" 次尝试）\n");
            prompt.append("上次操作结果: ").append(lastActionResult).append("\n\n");
            prompt.append("""
                ## ⚠️ 微调策略
                1. 查看【红色十字】的当前位置坐标
                2. 评估与目标的距离和方向
                3. 基于当前位置进行 5-30 像素的微调
                4. 如果多次点击无效，考虑：
                   - 目标可能需要先滚动到可见区域
                   - 可能有弹窗遮挡，需要先关闭
                   - 可能需要使用不同的交互方式（双击、右键等）
                """);
            
            // 如果处于恢复模式，给出更强的提示
            if (globalContext != null && globalContext.isInRecoveryMode()) {
                prompt.append("\n## ⚠️ 注意：当前处于恢复模式\n");
                prompt.append("之前的策略未能成功，请尝试完全不同的方法！\n");
            }
        }
        
        return prompt.toString();
    }
    
    /**
     * 判断任务是否完成
     */
    private boolean isTaskCompleted(String text, PlanStep step) {
        // 基本关键词匹配
        if (text.contains("完成") || text.contains("成功") || text.contains("已经")) {
            return true;
        }
        
        // 【新增】如果有 Definition of Done，检查是否提到
        if (step.getDefinitionOfDone() != null) {
            String dod = step.getDefinitionOfDone().toLowerCase();
            String textLower = text.toLowerCase();
            // 简单匹配：如果 DoD 中的关键词出现在响应中
            String[] dodKeywords = dod.split("[\\s,，。、]+");
            int matchCount = 0;
            for (String keyword : dodKeywords) {
                if (keyword.length() > 2 && textLower.contains(keyword)) {
                    matchCount++;
                }
            }
            // 超过一半的关键词匹配则认为完成
            if (matchCount > dodKeywords.length / 2) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 创建失败结果（含验尸报告）- 兼容旧调用
     */
    private ExecutionResult createFailedResult(PlanStep step, String reason, String lastScreenState,
                                               List<String> attemptedStrategies, 
                                               PlanStep.PostMortem.FailureReason failureReason) {
        return createFailedResult(step, reason, lastScreenState, attemptedStrategies, failureReason, null);
    }
    
    /**
     * 创建失败结果（含验尸报告）- 带 GlobalContext
     */
    private ExecutionResult createFailedResult(PlanStep step, String reason, String lastScreenState,
                                               List<String> attemptedStrategies, 
                                               PlanStep.PostMortem.FailureReason failureReason,
                                               GlobalContext globalContext) {
        // 构建验尸报告
        PlanStep.PostMortem postMortem = PlanStep.PostMortem.builder()
                .lastScreenState(lastScreenState)
                .attemptedStrategies(attemptedStrategies)
                .failureReason(failureReason)
                .errorDetail(reason)
                .suggestedRecovery(generateRecoverySuggestion(failureReason, attemptedStrategies))
                .build();
        
        step.markFailed(reason, postMortem);
        log.warn("❌ 里程碑 {} 执行失败: {}", step.getId(), reason);
        log.warn("   📋 验尸报告: {}", postMortem);
        
        // 【新增】更新 GlobalContext
        if (globalContext != null) {
            globalContext.updateFromExecution(lastScreenState, reason, false);
            globalContext.setLastError(reason);
        }
        
        return ExecutionResult.failed(reason, postMortem);
    }
    
    /**
     * 生成恢复建议
     */
    private String generateRecoverySuggestion(PlanStep.PostMortem.FailureReason reason, 
                                              List<String> attemptedStrategies) {
        return switch (reason) {
            case ELEMENT_NOT_FOUND -> "建议滚动页面或检查元素是否存在";
            case CLICK_MISSED -> "建议调整坐标或使用不同的定位策略";
            case INFINITE_LOOP -> "建议重新规划步骤或跳过此步骤";
            case APP_NOT_RESPONDING -> "建议等待更长时间或重启应用";
            case UNEXPECTED_DIALOG -> "建议先处理弹窗再继续";
            case TIMEOUT -> "建议增加超时时间或简化任务";
            default -> "建议检查屏幕状态并重试";
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
     * 执行结果 - 包含验尸报告（PostMortem）
     */
    @Data
    public static class ExecutionResult {
        private final boolean success;
        private final String message;
        private final long executionTimeMs;
        /** 【新增】验尸报告 - 失败时的详细诊断信息 */
        private final PlanStep.PostMortem postMortem;
        /** 【新增】尝试过的策略列表 */
        private final List<String> attemptedStrategies;
        
        private ExecutionResult(boolean success, String message, long executionTimeMs, 
                               PlanStep.PostMortem postMortem, List<String> attemptedStrategies) {
            this.success = success;
            this.message = message;
            this.executionTimeMs = executionTimeMs;
            this.postMortem = postMortem;
            this.attemptedStrategies = attemptedStrategies != null ? attemptedStrategies : new ArrayList<>();
        }
        
        public static ExecutionResult success(String message) {
            return new ExecutionResult(true, message, 0, null, null);
        }
        
        public static ExecutionResult success(String message, List<String> attemptedStrategies) {
            return new ExecutionResult(true, message, 0, null, attemptedStrategies);
        }
        
        public static ExecutionResult failed(String reason) {
            return new ExecutionResult(false, reason, 0, null, null);
        }
        
        public static ExecutionResult failed(String reason, PlanStep.PostMortem postMortem) {
            return new ExecutionResult(false, reason, 0, postMortem, null);
        }
        
        public static ExecutionResult of(boolean success, String message, long timeMs) {
            return new ExecutionResult(success, message, timeMs, null, null);
        }
        
        /**
         * 生成给 Planner 的反馈报告
         */
        public String generatePlannerFeedback() {
            StringBuilder sb = new StringBuilder();
            sb.append(success ? "✅ 成功: " : "❌ 失败: ").append(message).append("\n");
            
            if (!success && postMortem != null) {
                sb.append("\n📋 验尸报告:\n");
                sb.append("  - 失败原因: ").append(postMortem.getFailureReason()).append("\n");
                sb.append("  - 最后屏幕状态: ").append(postMortem.getLastScreenState()).append("\n");
                sb.append("  - 建议恢复策略: ").append(postMortem.getSuggestedRecovery()).append("\n");
                
                if (postMortem.getAttemptedStrategies() != null && !postMortem.getAttemptedStrategies().isEmpty()) {
                    sb.append("  - 尝试过的策略 (最后5条):\n");
                    List<String> strategies = postMortem.getAttemptedStrategies();
                    int start = Math.max(0, strategies.size() - 5);
                    for (int i = start; i < strategies.size(); i++) {
                        sb.append("    ").append(i + 1).append(". ").append(strategies.get(i)).append("\n");
                    }
                }
            }
            
            return sb.toString();
        }
        
        @Override
        public String toString() {
            return (success ? "✅ " : "❌ ") + message;
        }
    }
}

