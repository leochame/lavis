package com.lavis.cognitive.executor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lavis.cognitive.context.GlobalContext;
import com.lavis.cognitive.model.PlanStep;
import com.lavis.perception.ScreenCapturer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Dimension;
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

    private final ScreenCapturer screenCapturer;
    private final ToolExecutionService toolExecutionService;
    private final List<ToolSpecification> reflectionToolSpecs;
    // 在类成员变量区域添加
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 调度器触发的中断标记 */
    private volatile boolean interrupted = false;

    // LLM 模型（由外部注入或配置）
    private ChatLanguageModel chatModel;

    @Value("${executor.max.corrections:2}")
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
                You are a tactical execution expert acting as a skilled worker role responsible for completing the specific execution of milestone level tasks

                ## Core Concept M-E-R Loop
                You have complete memory execution reflection loop capability
                1. **Memory**: You know where I am what I just did
                2. **Execution**: Make decisions based on current observation and memory
                3. **Reflection**: Observe screen changes after each operation and judge if successful

                ## Coordinate System Strict Compliance Required
                Screen size %d x %d pixels logical screen coordinates
                - X coordinate range 0 to %d
                - Y coordinate range 0 to %d

                **Important**: 
                The coordinates shown in the screenshot are the coordinates you need to use
                Red cross marker shows current mouse position and its coordinates
                Green circle marker shows last click position

                ## Visual Marker Description
                - [Red cross + coordinates]: Current mouse position
                - [Green circle + label]: Last click position

                ## Anchor Point Positioning Strategy(Critical): 
                **Prohibited blind coordinate guessing**: Must base on visual anchor points
                1. **Find anchor point**: Identify visual features of target button input box color text icon
                2. **Relative positioning**: Estimate precise coordinates of target based on anchor point and current mouse position
                3. **Verify hit**: After execution observe if green circle lands on target
                4. **Fine tune correction**: If deviated fine tune based on current position plus or minus 5-30 pixels

                ## Autonomous Processing Capability
                - You do not need to report to Planner: Can handle the following situations independently
                - Popup dialog boxes: Close or confirm independently
                - Loading delays: Wait and re capture screenshot independently
                - Click offset: Fine tune coordinates and retry independently
                - Scroll search: Scroll to find target element independently

                ## Execution Rules
                - Execute only one action at a time single step principle
                - Always make decisions based on latest screenshot
                - Do not explain too much execute operations directly
                - If target state is achieved in screenshot report task completed

                """, logicalSize.width, logicalSize.height, logicalSize.width, logicalSize.height));

        // 【新增】注入 GlobalContext 的"前情提要"
        if (globalContext != null) {
            sb.append("Context Summary Your Memory\n");
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

    public MicroExecutorService(ScreenCapturer screenCapturer, ToolExecutionService toolExecutionService,
            List<ToolSpecification> reflectionToolSpecs) {
        this.screenCapturer = screenCapturer;
        this.toolExecutionService = toolExecutionService;
        this.reflectionToolSpecs = reflectionToolSpecs;
    }

    /**
     * 初始化 LLM 模型（由 AgentService 或配置注入）
     */
    public void initialize(ChatLanguageModel model) {
        this.chatModel = model;
        log.info("✅ MicroExecutorService 初始化完成，工具数: {}", toolExecutionService.getToolCount());
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
     * @param step          要执行的步骤（里程碑级）
     * @param globalContext 全局上下文（宏观记忆）
     * @return 执行结果（含验尸报告）
     */
    public ExecutionResult executeStep(PlanStep step, GlobalContext globalContext) {
        log.info("🎯 MicroExecutor 开始执行里程碑 {}: {}", step.getId(), step.getDescription());

        if (chatModel == null) {
            return ExecutionResult.failed("MicroExecutor 未初始化", null);
        }

        if (interrupted) {
            step.markFailed("用户中断任务");
            return ExecutionResult.failed("用户中断任务", null);
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
            if (interrupted) {
                step.markFailed("用户中断任务");
                return ExecutionResult.failed("用户中断任务", null);
            }

            try {
                // ========== Execution: 观察-决策-行动 ==========

                // 1. 观察：获取当前屏幕截图
                String screenshot = screenCapturer.captureScreenWithCursorAsBase64();

                // 2. 决策：构建提示词，让 LLM 决策
                String userPrompt = buildMERPrompt(step, corrections, lastActionResult, globalContext);

                UserMessage userMessage = UserMessage.from(
                        TextContent.from(userPrompt),
                        ImageContent.from(screenshot, "image/jpeg"));
                localContext.add(userMessage);

                // 调用 LLM 决策
                Response<AiMessage> response = chatModel.generate(localContext,
                        toolExecutionService.getToolSpecifications());
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
                    String result = toolExecutionService.execute(toolName, toolArgs);
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

                // ========== Reflection: 等待-重新截图-简化反思 ==========

                // 等待 UI 响应
                log.info("⏳ 等待 UI 响应 {}ms...", toolWaitMs);
                Thread.sleep(toolWaitMs);

                // 【关键】重新截图并进行反思
                String newScreenshot = screenCapturer.captureScreenWithCursorAsBase64();

                // 构建反思提示
                String reflectionPrompt = buildToolBasedReflectionPrompt(step, lastActionResult);
                UserMessage reflectionMessage = UserMessage.from(
                        TextContent.from(reflectionPrompt),
                        ImageContent.from(newScreenshot, "image/jpeg"));
                localContext.add(reflectionMessage);

                // 调用 LLM 进行反思
                Response<AiMessage> reflectionResponse = chatModel.generate(localContext, reflectionToolSpecs);
                AiMessage reflectionAi = reflectionResponse.content();
                localContext.add(reflectionAi);

                // ========== 简化的反思逻辑 ==========
                // 判断标准：如果 LLM 调用了 completeMilestone 工具 → 任务成功
                //          其他情况（无工具调用或其他工具）→ 继续下一轮循环

                if (reflectionAi.hasToolExecutionRequests()) {
                    ToolExecutionRequest req = reflectionAi.toolExecutionRequests().get(0);

                    if ("completeMilestone".equals(req.name())) {
                        // ✅ LLM 调用了 completeMilestone，视为任务成功
                        String summary = extractArg(req, "summary");
                        String successMessage = summary != null ? summary : "任务已完成";
                        
                        step.markSuccess(successMessage);
                        log.info("✅ 里程碑 {} 达成: {}", step.getId(), successMessage);
                        
                        if (globalContext != null) {
                            globalContext.updateFromExecution(successMessage, lastActionSummary, true);
                        }
                        return ExecutionResult.success(successMessage, attemptedStrategies);
                    } else {
                        // 调用了其他工具（理论上不存在），继续下一轮
                        log.warn("⚠️ 反思阶段调用了未知工具: {}，继续循环", req.name());
                        lastScreenState = "调用了非预期工具: " + req.name();
                    }
                } else {
                    // LLM 输出了文本分析但未调用工具，视为任务未完成，继续下一轮
                    String reflectionText = reflectionAi.text();
                    log.info("📝 反思分析（继续执行）: {}", truncate(reflectionText, 100));
                    lastScreenState = "继续执行: " + truncate(reflectionText, 50);
                    
                    if (globalContext != null) {
                        globalContext.addActionSummary(lastActionSummary, "继续", true);
                    }
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
        PlanStep.PostMortem.FailureReason failureReason = corrections >= effectiveMaxRetries
                ? PlanStep.PostMortem.FailureReason.INFINITE_LOOP
                : PlanStep.PostMortem.FailureReason.TIMEOUT;

        String reason = corrections >= effectiveMaxRetries ? "达到最大修正次数 (" + effectiveMaxRetries + ")" : "执行超时";

        return createFailedResult(step, reason, lastScreenState, attemptedStrategies, failureReason, globalContext);
    }

    /**
     * 构建反思阶段的 Prompt（简化版）
     * 
     * 简化逻辑：
     * - 如果任务完成 → 调用 completeMilestone 工具
     * - 如果任务未完成 → 直接输出文本分析（不调用工具）
     */
    private String buildToolBasedReflectionPrompt(PlanStep step, String lastActionResult) {
        String definitionOfDone = step.getDefinitionOfDone() != null 
                ? step.getDefinitionOfDone() 
                : "No clear criteria please judge based on task description";
        
        return String.format("""
                ## Reflection Checkpoint
                
                You just executed operation
                %s
                
                Now please carefully observe the latest screen screenshot and judge if the task is completed
                
                ## Task Information
                - Current Milestone %s
                - Completion Criteria Definition of Done %s
                
                ## Visual Success Indicators
                - To judge task success you should see in screenshot
                - Target state has been achieved such as opened correct application entered correct page
                - Success prompt appears such as Success Completed green checkmark
                - URL title bar displays expected content
                - Element that needed operation has disappeared or state has changed
                
                ## Incomplete Indicators
                When encountering the following situations do not judge as success:
                - Interface has no changes
                - Error text appears such as Error Failed
                - Interface stays at Loading
                - Click position deviated from target
                - Unexpected dialog popped up
                
                ## Response Instructions
                
                Please respond according to the following rules
                
                ### If task is completed
                Call completeMilestone tool summary parameter describes success evidence you see in screenshot
                
                ### If task is not completed
                Do not call any tools directly output text analysis
                1. What is the current screen state
                2. What is still missing to complete
                3. What should be done next
                
                Please make a judgment
                """,
                lastActionResult,
                step.getDescription(),
                definitionOfDone);
    }

    /**
     * 辅助方法：截断字符串
     */
    private String truncate(String str, int maxLen) {
        if (str == null)
            return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    /**
     * 构建 M-E-R 循环的提示词
     * 
     * @param step             当前步骤
     * @param corrections      已修正次数
     * @param lastActionResult 上次操作结果
     * @param globalContext    全局上下文
     */
    private String buildMERPrompt(PlanStep step, int corrections, String lastActionResult,
            GlobalContext globalContext) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("Current Milestone Task\n");
        prompt.append(step.getDescription()).append("\n\n");

        // 注入完成状态定义（Definition of Done）
        if (step.getDefinitionOfDone() != null && !step.getDefinitionOfDone().isEmpty()) {
            prompt.append("Completion Criteria Definition of Done\n");
            prompt.append(step.getDefinitionOfDone()).append("\n");
            prompt.append("When you see the above state in the screenshot the task is considered completed\n\n");
        }

        if (corrections == 0) {
            // 首次执行
            prompt.append("""
                    ## Execution Instructions
                    Please analyze the screenshot use anchor point positioning strategy to find target element then execute necessary operations

                    Anchor Point Positioning Steps
                    1. Identify visual features of target element color text icon position relationship
                    2. Locate target in screenshot based on features
                    3. Reference red cross current mouse position to estimate precise coordinates
                    4. Execute one operation single step principle
                    """);
        } else {
            // 修正执行
            prompt.append("## Continue Execution Attempt ").append(corrections + 1).append("\n");
            prompt.append("Last Operation Result ").append(lastActionResult).append("\n\n");
            prompt.append("""
                    ## Fine Tuning Strategy
                    1. Check current position coordinates of red cross
                    2. Evaluate distance and direction to target
                    3. Fine tune based on current position 5-30 pixels
                    4. If multiple clicks are ineffective consider
                       - Target may need to be scrolled into visible area first
                       - There may be popup blocking need to close first
                       - May need to use different interaction methods double click right click etc
                    """);

            // 如果处于恢复模式，给出更强的提示
            if (globalContext != null && globalContext.isInRecoveryMode()) {
                prompt.append("\n## Note Currently in Recovery Mode\n");
                prompt.append("Previous strategies were unsuccessful please try completely different methods\n");
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
     * 从 ToolExecutionRequest 中提取指定参数
     * * @param req 工具执行请求
     * 
     * @param key 参数名
     * @return 参数值字符串，如果解析失败或key不存在则返回 null
     */
    private String extractArg(ToolExecutionRequest req, String key) {
        String arguments = req.arguments();

        // 1. 基础校验
        if (arguments == null || arguments.isBlank()) {
            log.warn("⚠️ 工具参数为空，无法提取 key: {}", key);
            return null;
        }

        try {
            // 2. 解析 JSON
            JsonNode rootNode = objectMapper.readTree(arguments);

            // 3. 获取指定 Key
            JsonNode valueNode = rootNode.get(key);

            // 4. 返回文本值 (asText() 可以正确处理 String, Number, Boolean 等类型转 String)
            if (valueNode != null && !valueNode.isNull()) {
                return valueNode.asText();
            }

            log.debug("ℹ️ 参数 JSON 中未找到 key: {}", key);
            return null;

        } catch (JsonProcessingException e) {
            log.error("❌ JSON 解析失败: args={}, error={}", arguments, e.getMessage());
            return null;
        }
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
     * 调度器触发的中断请求
     */
    public void requestInterrupt() {
        interrupted = true;
    }

    /**
     * 新任务前清除中断标记
     */
    public void clearInterrupt() {
        interrupted = false;
    }

    /**
     * 当前是否处于中断状态
     */
    public boolean isInterrupted() {
        return interrupted;
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
