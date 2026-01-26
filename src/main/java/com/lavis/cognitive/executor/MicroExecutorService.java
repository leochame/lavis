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
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * 微观执行器服务 (Micro-Executor Service) - 战术层
 * 
 * 【架构升级】实现合并的 OODA 循环 (Observe-Orient-Decide-Act)
 * 
 * 核心特性：
 * 1. 【Memory 记忆】从 GlobalContext 读取"前情提要"，知道"我在哪"、"我刚才做了什么"
 * 2. 【执行-反思合并】在同一轮 LLM 调用中完成观察、决策、行动和反思
 *    - 观察：获取当前屏幕截图
 *    - 决策：LLM 基于截图和上下文做出决策
 *    - 行动：执行工具（如点击、输入等）
 *    - 反思：在下一轮观察时，LLM 看到新截图后自然反思上一步是否成功
 * 3. 【幻觉抑制】通过 Prompt 约束，禁止 LLM 在执行动作的同一轮宣布完成
 * 4. 【锚点定位】基于视觉锚点定位目标，而非盲目坐标点击
 * 5. 【验尸报告】失败时返回详细的 PostMortem，供 Planner 决策
 * 6. 【兜底机制】检测重复无效操作，防止死循环
 * 7. 【微观上下文隔离】执行完成后销毁上下文，只将结果同步回 GlobalContext
 * 
 * 设计哲学：
 * - 这是一个"熟练工"，而非"机械臂"
 * - 能自行解决琐碎问题，无需事事上报给 Planner
 * - 只有真正搞不定时才上报异常（带验尸报告）
 * - 效率优先：合并反思阶段，减少 LLM 调用次数，提高执行速度
 */
@Slf4j
@Service
public class MicroExecutorService {

    private final ScreenCapturer screenCapturer;
    private final ToolExecutionService toolExecutionService;
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

                ## Core Concept: OODA Loop (Observe-Orient-Decide-Act)
                You operate in a continuous loop:
                1. **Observe**: You receive a screenshot showing the current screen state
                2. **Orient**: You understand where you are and what you just did (from memory/context)
                3. **Decide**: You decide what action to take next (or if the task is complete)
                4. **Act**: You execute the action using tools
                5. **Reflect**: In the NEXT turn, when you see a new screenshot, you naturally reflect on whether your previous action succeeded

                ## Coordinate System Strict Compliance Required
                **CRITICAL: You MUST use Gemini normalized coordinates (0-1000), NOT screen pixel coordinates!**
                
                Screen size: %d x %d pixels (logical screen)
                **Gemini coordinate range: X: 0 to 1000, Y: 0 to 1000**
                
                **Important**: 
                - The coordinates shown in the screenshot (red cross and green circle) are in Gemini format (0-1000)
                - ALL tool calls (click, doubleClick, rightClick, drag, moveMouse) MUST use Gemini coordinates [x, y] where x and y are integers between 0 and 1000
                - DO NOT use screen pixel coordinates (0-%d, 0-%d) - they will be rejected
                - Red cross marker shows current mouse position in Gemini coordinates
                - Green circle marker shows last click position in Gemini coordinates

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
                
                ## CRITICAL RULE: Action and Reflection Separation
                **STRICT RULE: Do NOT assume your action succeeded immediately.**
                - If you decide to click or type, you MUST NOT call 'completeMilestone' in the same turn.
                - You must wait for the NEXT turn to see the visual changes before marking completion.
                - Each screenshot you receive is AFTER the previous action has been executed.
                - When you see a new screenshot, observe the changes from your last action, then decide:
                  * If the task is clearly completed (you see success indicators), call 'completeMilestone'.
                  * If the task is not completed, continue with the next action.
                - This prevents hallucination: you cannot "predict" success, you must "confirm" it visually.

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

    public MicroExecutorService(ScreenCapturer screenCapturer, ToolExecutionService toolExecutionService) {
        this.screenCapturer = screenCapturer;
        this.toolExecutionService = toolExecutionService;
    }

    /**
     * 初始化 LLM 模型（由 AgentService 或配置注入）
     */
    public void initialize(ChatLanguageModel model) {
        this.chatModel = model;
        log.info("✅ MicroExecutorService 初始化完成，工具数: {}", toolExecutionService.getToolCount());
    }

    /**
     * 执行单个步骤（核心方法 - 合并的 OODA 循环）
     * 
     * 【架构升级】合并执行和反思阶段：
     * 1. Memory: 从 GlobalContext 读取"前情提要"
     * 2. Observe: 获取当前屏幕截图
     * 3. Orient & Decide: LLM 基于截图和上下文做出决策
     * 4. Act: 执行工具（如点击、输入等）
     * 5. Reflect: 在下一轮观察时，LLM 看到新截图后自然反思上一步是否成功
     * 
     * @param step 要执行的步骤（里程碑级）
     * @return 执行结果（含验尸报告）
     */
    public ExecutionResult executeStep(PlanStep step) {
        return executeStep(step, null);
    }

    /**
     * 执行单个步骤（核心方法 - 合并的 OODA 循环）- 带 GlobalContext
     * 
     * @param step          要执行的步骤（里程碑级）
     * @param globalContext 全局上下文（宏观记忆）
     * @return 执行结果（含验尸报告）
     */
    public ExecutionResult executeStep(PlanStep step, GlobalContext globalContext) {
        log.info("🎯 MicroExecutor 开始执行里程碑 {}: {}", step.getId(), step.getDescription());

        // 前置检查
        ExecutionResult preCheckResult = performPreChecks(step);
        if (preCheckResult != null) {
            return preCheckResult;
        }

        // 初始化执行上下文
        ExecutionContext context = initializeExecutionContext(step, globalContext);

        // 执行 OODA 循环
        while (context.isWithinLimits()) {
            if (interrupted) {
                return handleInterruption(step, context);
            }

            try {
                ExecutionResult loopResult = executeOODALoop(step, context);
                if (loopResult != null) {
                    return loopResult; // 成功或需要中断
                }
                context.incrementCorrections();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return createFailedResult(step, "执行被中断", context.getLastScreenState(), 
                        context.getAttemptedStrategies(), PlanStep.PostMortem.FailureReason.UNKNOWN, globalContext);
            } catch (Exception e) {
                log.error("步骤执行异常: {}", e.getMessage(), e);
                context.handleException(e);
            }
        }

        // 达到最大重试或超时 - 生成验尸报告
        return createFailureResult(step, context, globalContext);
    }

    /**
     * 执行前置检查
     */
    private ExecutionResult performPreChecks(PlanStep step) {
        if (chatModel == null) {
            return ExecutionResult.failed("MicroExecutor 未初始化", null);
        }

        if (interrupted) {
            step.markFailed("用户中断任务");
            return ExecutionResult.failed("用户中断任务", null);
        }

        step.markStarted();
        return null; // 检查通过
    }

    /**
     * 初始化执行上下文
     */
    private ExecutionContext initializeExecutionContext(PlanStep step, GlobalContext globalContext) {
        int effectiveMaxRetries = step.getMaxRetries();
        int effectiveTimeoutSeconds = step.getTimeoutSeconds();
        log.info("   📊 最大重试: {}, 超时: {}秒", effectiveMaxRetries, effectiveTimeoutSeconds);

        Instant deadline = Instant.now().plusSeconds(effectiveTimeoutSeconds);
        List<ChatMessage> localContext = new ArrayList<>();
        localContext.add(SystemMessage.from(generateExecutorSystemPrompt(globalContext)));

        return new ExecutionContext(effectiveMaxRetries, deadline, localContext, globalContext);
    }

    /**
     * 执行单次 OODA 循环
     * @return 如果任务完成或需要中断，返回结果；否则返回 null 继续循环
     */
    private ExecutionResult executeOODALoop(PlanStep step, ExecutionContext context) throws InterruptedException, IOException {
        // 1. Observe: 获取当前屏幕截图
        String screenshot = screenCapturer.captureScreenWithCursorAsBase64();
        String currentScreenHash = String.valueOf(screenshot.hashCode());

        // 2. Orient & Decide: LLM 决策
        AiMessage aiMessage = performLLMDecision(step, context, screenshot);

        // 3. 检查是否有工具调用
        if (!aiMessage.hasToolExecutionRequests()) {
            return handleNoToolCall(step, context, aiMessage);
        }

        // 4. Act: 执行工具
        ToolExecutionResult toolResult = executeTools(step, context, aiMessage, currentScreenHash);
        if (toolResult.isTaskCompleted()) {
            return toolResult.getResult();
        }

        // 如果检测到重复操作，需要增加修正计数
        if (toolResult.hasDuplicateOperation()) {
            context.incrementCorrections();
            return null; // 继续下一轮循环
        }

        // 5. 等待 UI 响应
        waitForUIResponse(toolResult.hasActions());

        return null; // 继续下一轮循环
    }

    /**
     * 执行 LLM 决策
     */
    private AiMessage performLLMDecision(PlanStep step, ExecutionContext context, String screenshot) {
        String userPrompt = buildMERPrompt(step, context.getCorrections(), 
                context.getLastActionResult(), context.getGlobalContext());

        UserMessage userMessage = UserMessage.from(
                TextContent.from(userPrompt),
                ImageContent.from(screenshot, "image/jpeg"));
        context.getLocalContext().add(userMessage);

        Response<AiMessage> response = chatModel.generate(context.getLocalContext(),
                toolExecutionService.getToolSpecifications());
        AiMessage aiMessage = response.content();
        context.getLocalContext().add(aiMessage);

        return aiMessage;
    }

    /**
     * 处理无工具调用的情况
     */
    private ExecutionResult handleNoToolCall(PlanStep step, ExecutionContext context, AiMessage aiMessage) {
        String text = aiMessage.text();
        if (text != null && isTaskCompleted(text, step)) {
            return handleTaskCompletion(step, context, text);
        } else {
            context.addStrategy("LLM 无操作建议: " + (text != null ? truncate(text, 50) : "无"));
            return null; // 继续循环
        }
    }

    /**
     * 处理任务完成
     */
    private ExecutionResult handleTaskCompletion(PlanStep step, ExecutionContext context, String message) {
        step.markSuccess(message);
        log.info("✅ 里程碑 {} 达成: {}", step.getId(), message);

        if (context.getGlobalContext() != null) {
            context.getGlobalContext().updateFromExecution(message, "任务完成", true);
        }

        return ExecutionResult.success(message, context.getAttemptedStrategies());
    }

    /**
     * 执行工具
     */
    private ToolExecutionResult executeTools(PlanStep step, ExecutionContext context, 
            AiMessage aiMessage, String currentScreenHash) {
        List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
        StringBuilder actionResults = new StringBuilder();
        String currentToolCallSignature = null;
        boolean hasDuplicateOperation = false;

        for (ToolExecutionRequest request : toolRequests) {
            String toolName = request.name();
            String toolArgs = request.arguments();
            currentToolCallSignature = toolName + ":" + toolArgs;

            // 检测重复无效操作
            if (isDuplicateOperation(context, currentToolCallSignature, currentScreenHash)) {
                handleDuplicateOperation(context, toolName);
                hasDuplicateOperation = true;
                continue; // 跳过执行，进入下一轮
            }

            log.info("  🔧 执行工具: {}({})", toolName, toolArgs);

            // 检查是否是任务完成信号
            if ("completeMilestone".equals(toolName)) {
                ExecutionResult result = handleMilestoneCompletion(step, context, request);
                return new ToolExecutionResult(true, result, actionResults.toString(), currentToolCallSignature, false);
            }

            // 执行工具
            String result = toolExecutionService.execute(toolName, toolArgs);
            actionResults.append(result).append("\n");

            // 记录策略
            String strategyRecord = formatStrategyRecord(toolName, toolArgs, result);
            context.addStrategy(strategyRecord);
            context.setLastActionSummary(strategyRecord);

            // 添加工具结果到上下文
            ToolExecutionResultMessage toolResult = ToolExecutionResultMessage.from(request, result);
            context.getLocalContext().add(toolResult);
        }

        // 更新上下文状态（只有在没有重复操作时才更新，避免覆盖）
        if (!hasDuplicateOperation) {
            context.updateAfterToolExecution(actionResults.toString(), currentToolCallSignature, currentScreenHash);
        }

        return new ToolExecutionResult(false, null, actionResults.toString(), currentToolCallSignature, hasDuplicateOperation);
    }

    /**
     * 检测是否为重复无效操作
     */
    private boolean isDuplicateOperation(ExecutionContext context, String currentSignature, String currentScreenHash) {
        String lastSignature = context.getLastToolCallSignature();
        String lastScreenHash = context.getLastScreenHash();
        
        return lastSignature != null && currentSignature.equals(lastSignature)
                && lastScreenHash != null && currentScreenHash.equals(lastScreenHash);
    }

    /**
     * 处理重复无效操作
     */
    private void handleDuplicateOperation(ExecutionContext context, String toolName) {
        log.warn("⚠️ 检测到重复无效操作: {}，屏幕状态未变化", toolName);
        context.getLocalContext().add(UserMessage.from(
                "System Alert: You just tried that exact same operation and the screen didn't change. " +
                "You MUST change your strategy (e.g. adjust coordinates, try double click, wait longer, or try a different approach)."));
        context.addStrategy("重复操作检测: " + toolName);
    }

    /**
     * 处理里程碑完成
     */
    private ExecutionResult handleMilestoneCompletion(PlanStep step, ExecutionContext context, 
            ToolExecutionRequest request) {
        String summary = extractArg(request, "summary");
        String successMessage = summary != null ? summary : "任务已完成";

        step.markSuccess(successMessage);
        log.info("✅ 里程碑 {} 达成: {}", step.getId(), successMessage);

        if (context.getGlobalContext() != null) {
            context.getGlobalContext().updateFromExecution(successMessage, context.getLastActionSummary(), true);
        }

        return ExecutionResult.success(successMessage, context.getAttemptedStrategies());
    }

    /**
     * 格式化策略记录
     */
    private String formatStrategyRecord(String toolName, String toolArgs, String result) {
        String argsPreview = toolArgs.length() > 30 ? toolArgs.substring(0, 30) + "..." : toolArgs;
        String resultPreview = result.split("\n")[0];
        return String.format("%s(%s) -> %s", toolName, argsPreview, resultPreview);
    }

    /**
     * 等待 UI 响应
     */
    private void waitForUIResponse(boolean hasActions) throws InterruptedException {
        if (hasActions) {
            log.info("⏳ 等待 UI 响应 {}ms...", toolWaitMs);
            Thread.sleep(toolWaitMs);
        }
    }

    /**
     * 处理中断
     */
    private ExecutionResult handleInterruption(PlanStep step, ExecutionContext context) {
        step.markFailed("用户中断任务");
        return ExecutionResult.failed("用户中断任务", null);
    }

    /**
     * 创建失败结果
     */
    private ExecutionResult createFailureResult(PlanStep step, ExecutionContext context, GlobalContext globalContext) {
        int corrections = context.getCorrections();
        int maxRetries = context.getMaxRetries();

        PlanStep.PostMortem.FailureReason failureReason = corrections >= maxRetries
                ? PlanStep.PostMortem.FailureReason.INFINITE_LOOP
                : PlanStep.PostMortem.FailureReason.TIMEOUT;

        String reason = corrections >= maxRetries
                ? "达到最大修正次数 (" + maxRetries + ")"
                : "执行超时";

        return createFailedResult(step, reason, context.getLastScreenState(), 
                context.getAttemptedStrategies(), failureReason, globalContext);
    }

    /**
     * 执行上下文 - 封装执行循环中的状态
     */
    private static class ExecutionContext {
        private final int maxRetries;
        private final Instant deadline;
        private final List<ChatMessage> localContext;
        private final GlobalContext globalContext;
        private final List<String> attemptedStrategies = new ArrayList<>();

        private int corrections = 0;
        private String lastActionResult = null;
        private String lastToolCallSignature = null;
        private String lastScreenHash = null;
        private String lastActionSummary = null;
        private String lastScreenState = "初始状态";

        public ExecutionContext(int maxRetries, Instant deadline, List<ChatMessage> localContext, 
                GlobalContext globalContext) {
            this.maxRetries = maxRetries;
            this.deadline = deadline;
            this.localContext = localContext;
            this.globalContext = globalContext;
        }

        public boolean isWithinLimits() {
            return corrections < maxRetries && Instant.now().isBefore(deadline);
        }

        public void incrementCorrections() {
            corrections++;
        }

        public void addStrategy(String strategy) {
            attemptedStrategies.add(strategy);
        }

        public void updateAfterToolExecution(String actionResult, String toolSignature, String screenHash) {
            this.lastActionResult = actionResult;
            this.lastToolCallSignature = toolSignature;
            this.lastScreenHash = screenHash;
        }

        public void handleException(Exception e) {
            corrections++;
            lastActionResult = "执行异常: " + e.getMessage();
            attemptedStrategies.add("异常: " + e.getMessage());
        }

        // Getters
        public int getCorrections() { return corrections; }
        public int getMaxRetries() { return maxRetries; }
        public Instant getDeadline() { return deadline; }
        public List<ChatMessage> getLocalContext() { return localContext; }
        public GlobalContext getGlobalContext() { return globalContext; }
        public List<String> getAttemptedStrategies() { return attemptedStrategies; }
        public String getLastActionResult() { return lastActionResult; }
        public String getLastToolCallSignature() { return lastToolCallSignature; }
        public String getLastScreenHash() { return lastScreenHash; }
        public String getLastActionSummary() { return lastActionSummary; }
        public String getLastScreenState() { return lastScreenState; }
        public void setLastActionSummary(String summary) { this.lastActionSummary = summary; }
    }

    /**
     * 工具执行结果
     */
    private static class ToolExecutionResult {
        private final boolean taskCompleted;
        private final ExecutionResult result;
        private final String actionResults;
        private final String toolSignature;
        private final boolean hasDuplicateOperation;

        public ToolExecutionResult(boolean taskCompleted, ExecutionResult result, 
                String actionResults, String toolSignature, boolean hasDuplicateOperation) {
            this.taskCompleted = taskCompleted;
            this.result = result;
            this.actionResults = actionResults;
            this.toolSignature = toolSignature;
            this.hasDuplicateOperation = hasDuplicateOperation;
        }

        public boolean isTaskCompleted() { return taskCompleted; }
        public ExecutionResult getResult() { return result; }
        public boolean hasActions() { return actionResults != null && !actionResults.isEmpty(); }
        public boolean hasDuplicateOperation() { return hasDuplicateOperation; }
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
