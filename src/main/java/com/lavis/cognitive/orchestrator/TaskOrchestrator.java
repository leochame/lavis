package com.lavis.cognitive.orchestrator;

import com.lavis.cognitive.context.GlobalContext;
import com.lavis.cognitive.executor.MicroExecutorService;
import com.lavis.cognitive.model.PlanStep;
import com.lavis.cognitive.model.TaskPlan;
import com.lavis.cognitive.planner.PlannerService;
import com.lavis.service.llm.LlmFactory;
import com.lavis.websocket.WorkflowEventService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 任务调度器 (Task Orchestrator) - 唯一指挥官
 * 
 * 【架构升级】统一控制流 - 废弃 ReflectionLoop，确立 TaskOrchestrator 为唯一入口
 * 
 * 核心职责：
 * 1. 协调 Planner（战略层）和 Executor（战术层）
 * 2. 维护 GlobalContext（宏观上下文/长期记忆）
 * 3. 基于验尸报告（PostMortem）进行智能决策
 * 4. 控制执行流程和异常恢复
 * 5. 支持动态 Re-plan
 * 
 * 状态机流程：
 * IDLE -> PLANNING -> EXECUTING -> (STEP_SUCCESS/STEP_FAILED) -> ... ->
 * COMPLETED/FAILED
 * 
 * 【重要】所有复杂任务必须通过 executeGoal() 启动
 */
@Slf4j
@Service
public class TaskOrchestrator {

    private final PlannerService plannerService;
    private final MicroExecutorService microExecutorService;
    private final LlmFactory llmFactory;
    
    // WebSocket 事件服务（用于向前端推送工作流状态）
    @Autowired(required = false)
    private WorkflowEventService workflowEventService;

    /** Planner 使用的模型别名 */
    @Value("${planner.model.alias:}")
    private String plannerModelAlias;

    /** Executor 使用的模型别名 */
    @Value("${executor.model.alias:}")
    private String executorModelAlias;

    // 当前任务计划
    private TaskPlan currentPlan;

    // 【新增】全局上下文 - 宏观记忆
    private GlobalContext globalContext;

    // 调度器状态
    private OrchestratorState state = OrchestratorState.IDLE;

    // 执行统计
    private int totalStepsExecuted = 0;
    private int totalStepsFailed = 0;

    // 最大连续失败次数（触发 Re-plan）
    private static final int MAX_CONSECUTIVE_FAILURES = 2;
    private int consecutiveFailures = 0;
    
    // 中断标记（/stop 指令触发）
    private volatile boolean interrupted = false;

    public TaskOrchestrator(PlannerService plannerService, MicroExecutorService microExecutorService, 
                            LlmFactory llmFactory) {
        this.plannerService = plannerService;
        this.microExecutorService = microExecutorService;
        this.llmFactory = llmFactory;
    }

    /**
     * 初始化 LLM 模型（传递给 Planner 和 Executor）
     * 
     * 支持两种模式：
     * 1. 如果配置了 planner.model.alias 或 executor.model.alias，使用独立模型
     * 2. 否则使用传入的统一模型（向后兼容）
     */
    public void initialize(ChatLanguageModel defaultModel) {
        // Planner 模型：优先使用独立配置，否则使用默认模型
        ChatLanguageModel plannerModel = defaultModel;
        if (plannerModelAlias != null && !plannerModelAlias.isBlank() 
                && llmFactory.isModelAvailable(plannerModelAlias)) {
            plannerModel = llmFactory.getModel(plannerModelAlias);
            log.info("📋 Planner 使用独立模型: {}", plannerModelAlias);
        }
        
        // Executor 模型：优先使用独立配置，否则使用默认模型
        ChatLanguageModel executorModel = defaultModel;
        if (executorModelAlias != null && !executorModelAlias.isBlank() 
                && llmFactory.isModelAvailable(executorModelAlias)) {
            executorModel = llmFactory.getModel(executorModelAlias);
            log.info("🔧 Executor 使用独立模型: {}", executorModelAlias);
        }
        
        plannerService.initialize(plannerModel);
        microExecutorService.initialize(executorModel);
        log.info("✅ TaskOrchestrator 初始化完成");
    }

    /**
     * 执行用户目标（主入口）
     * 
     * 【统一入口】所有复杂任务必须通过此方法启动
     * 
     * 完整流程：
     * 1. 创建 GlobalContext（宏观上下文）
     * 2. Planner 生成计划
     * 3. 逐步执行每个步骤（注入 GlobalContext）
     * 4. 失败时触发 Re-plan
     * 5. 返回最终结果
     * 
     * @param userGoal 用户目标
     * @return 执行结果
     */
    public OrchestratorResult executeGoal(String userGoal) {
        log.info("🚀 开始执行目标: {}", userGoal);
        Instant startTime = Instant.now();
        clearInterruptFlag();

        try {
            // 0. 【新增】创建 GlobalContext（宏观上下文）
            this.globalContext = new GlobalContext(userGoal);
            log.info("🌍 创建 GlobalContext [{}]", globalContext.getContextId());

            // 1. 规划阶段
            state = OrchestratorState.PLANNING;
            log.info("📋 阶段1: 规划中...");

            currentPlan = plannerService.generatePlan(userGoal);

            if (currentPlan.getSteps().isEmpty()) {
                // 【内存安全】规划失败时清理 GlobalContext
                cleanupGlobalContext();
                return OrchestratorResult.failed("规划失败：未能生成任何步骤");
            }

            log.info("📋 计划生成完成，共 {} 个步骤", currentPlan.getSteps().size());
            
            // 【WebSocket】通知前端计划已创建
            if (workflowEventService != null) {
                workflowEventService.onPlanCreated(currentPlan);
            }

            // 2. 执行阶段
            state = OrchestratorState.EXECUTING;
            currentPlan.markStarted();
            consecutiveFailures = 0;

            while (true) {
                if (interrupted) {
                    return handleInterrupt(userGoal);
                }

                Optional<PlanStep> currentStepOpt = currentPlan.getCurrentStep();

                if (currentStepOpt.isEmpty()) {
                    break;
                }

                PlanStep currentStep = currentStepOpt.get();
                log.info("🔄 执行步骤 {}/{}: {}",
                        currentStep.getId(),
                        currentPlan.getSteps().size(),
                        currentStep.getDescription());

                // 【新增】更新 GlobalContext - 开始新里程碑
                globalContext.startMilestone(currentStep);
                
                // 【WebSocket】通知前端步骤开始
                if (workflowEventService != null) {
                    workflowEventService.onStepStarted(currentPlan, currentStep);
                }

                // 执行单个步骤（通过 MicroExecutor，注入 GlobalContext）
                MicroExecutorService.ExecutionResult stepResult = microExecutorService.executeStep(currentStep,
                        globalContext);

                if (interrupted || microExecutorService.isInterrupted()) {
                    return handleInterrupt(userGoal);
                }

                totalStepsExecuted++;

                // 【新增】更新 GlobalContext - 完成里程碑
                globalContext.completeMilestone(stepResult.getMessage(), stepResult.isSuccess());

                // 更新 Planner 状态
                plannerService.updatePlanProgress(currentPlan, currentStep, stepResult.isSuccess());

                if (stepResult.isSuccess()) {
                    // 成功：重置连续失败计数，移动到下一步
                    state = OrchestratorState.STEP_SUCCESS;
                    consecutiveFailures = 0;
                    log.info("✅ 里程碑 {} 达成: {}", currentStep.getId(), stepResult.getMessage());
                    
                    // 【WebSocket】通知前端步骤完成
                    if (workflowEventService != null) {
                        workflowEventService.onStepCompleted(currentPlan, currentStep);
                    }

                    if (!currentPlan.moveToNextStep()) {
                        // 所有步骤完成
                        break;
                    }
                } else {
                    // 失败：基于验尸报告进行智能决策
                    state = OrchestratorState.STEP_FAILED;
                    totalStepsFailed++;
                    consecutiveFailures++;

                    log.warn("❌ 里程碑 {} 执行失败: {}", currentStep.getId(), stepResult.getMessage());
                    
                    // 【WebSocket】通知前端步骤失败
                    if (workflowEventService != null) {
                        workflowEventService.onStepFailed(currentPlan, currentStep, stepResult.getMessage());
                    }

                    // 输出验尸报告反馈
                    log.warn("📋 Executor 反馈:\n{}", stepResult.generatePlannerFeedback());

                    // 【新增】检查是否需要 Re-plan
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        log.warn("🔄 连续失败 {} 次，触发 Re-plan", consecutiveFailures);
                        boolean replanned = attemptReplan(currentStep, stepResult);
                        if (replanned) {
                            consecutiveFailures = 0;
                            continue; // 重新开始执行新计划
                        }
                    }

                    // 基于验尸报告的智能决策
                    RecoveryDecision decision = makeRecoveryDecision(currentStep, stepResult);
                    log.info("🤔 Planner 决策: {}", decision);

                    switch (decision) {
                        case RETRY_STEP -> {
                            // 重试当前步骤
                            log.info("🔄 决定重试当前步骤");
                            globalContext.setTotalRetries(globalContext.getTotalRetries() + 1);
                            continue; // 不移动到下一步，重新执行当前步骤
                        }
                        case SKIP_STEP -> {
                            // 跳过当前步骤
                            log.info("⏭️ 决定跳过当前步骤");
                            currentStep.setStatus(PlanStep.StepStatus.SKIPPED);
                            if (!currentPlan.moveToNextStep()) {
                                break;
                            }
                        }
                        case REPLAN -> {
                            // 触发重新规划
                            log.info("🔄 决定重新规划");
                            boolean replanned = attemptReplan(currentStep, stepResult);
                            if (!replanned) {
                                // Re-plan 失败，中止任务
                                currentPlan.markFailed("Re-plan 失败: " + stepResult.getMessage());
                                // 【内存安全】清理 GlobalContext
                                cleanupGlobalContext();
                                return OrchestratorResult.failed(
                                        "任务在 Re-plan 后仍然失败: " + stepResult.getMessage(),
                                        currentStep.getPostMortem());
                            }
                            continue;
                        }
                        case ABORT -> {
                            // 中止任务
                            currentPlan.markFailed("验尸报告建议中止: " + stepResult.getMessage());
                            // 【内存安全】清理 GlobalContext
                            cleanupGlobalContext();
                            return OrchestratorResult.failed(
                                    String.format("任务在里程碑 %d 失败后中止: %s\n%s",
                                            currentStep.getId(),
                                            stepResult.getMessage(),
                                            stepResult.generatePlannerFeedback()),
                                    currentStep.getPostMortem());
                        }
                        case CONTINUE -> {
                            // 继续尝试下一步
                            log.info("➡️ 决定继续执行下一步");
                            if (!currentPlan.moveToNextStep()) {
                                break;
                            }
                        }
                    }
                }
            }

            // 3. 完成阶段
            long executionTimeMs = java.time.Duration.between(startTime, Instant.now()).toMillis();

            if (currentPlan.isCompleted()) {
                state = OrchestratorState.COMPLETED;
                currentPlan.markCompleted();
                
                // 【WebSocket】通知前端计划完成
                if (workflowEventService != null) {
                    workflowEventService.onPlanCompleted(currentPlan);
                    
                    // 【新增】异步生成并发送拟人化TTS通知（仅在最终步骤完成时）
                    generateAndSendVoiceAnnouncement(userGoal, totalStepsExecuted, executionTimeMs);
                }

                log.info("✅ 目标执行完成！耗时 {}ms", executionTimeMs);
                log.info("📊 GlobalContext 摘要:\n{}", globalContext.getExecutionSummary());
                
                // 【内存安全】任务完成后清理 GlobalContext
                cleanupGlobalContext();
                
                return OrchestratorResult.success(
                        String.format("任务完成：%s (执行 %d 步，耗时 %dms)",
                                userGoal, totalStepsExecuted, executionTimeMs),
                        currentPlan);
            } else if (currentPlan.hasFailed()) {
                state = OrchestratorState.FAILED;
                currentPlan.markFailed("部分步骤执行失败");
                
                // 【WebSocket】通知前端计划失败
                if (workflowEventService != null) {
                    workflowEventService.onPlanFailed(currentPlan, "部分步骤执行失败");
                }

                // 【内存安全】任务失败后清理 GlobalContext
                cleanupGlobalContext();
                
                return OrchestratorResult.partial(
                        String.format("任务部分完成：%d/%d 步骤成功",
                                totalStepsExecuted - totalStepsFailed,
                                currentPlan.getSteps().size()),
                        currentPlan);
            } else {
                state = OrchestratorState.COMPLETED;
                currentPlan.markCompleted();

                // 【内存安全】任务完成后清理 GlobalContext
                cleanupGlobalContext();
                
                return OrchestratorResult.success("任务完成", currentPlan);
            }

        } catch (Exception e) {
            log.error("❌ 任务执行异常: {}", e.getMessage(), e);
            // 【内存安全】异常情况下也要清理 GlobalContext
            cleanupGlobalContext();
            state = OrchestratorState.FAILED;

            String errorMessage = "执行异常: " + e.getMessage();
            if (currentPlan != null) {
                currentPlan.markFailed(errorMessage);
                
                // 【WebSocket】通知前端执行异常
                if (workflowEventService != null) {
                    workflowEventService.onTaskExecutionException(errorMessage, currentPlan.getPlanId());
                    // 同时发送计划失败事件
                    workflowEventService.onPlanFailed(currentPlan, errorMessage);
                }
            } else {
                // 即使没有计划，也要通知前端有错误发生
                if (workflowEventService != null) {
                    workflowEventService.onTaskExecutionException(errorMessage, "unknown");
                }
            }

            return OrchestratorResult.failed(errorMessage);
        }
    }

    /**
     * 【新增】尝试重新规划
     * 
     * @param failedStep 失败的步骤
     * @param result     执行结果（含验尸报告）
     * @return 是否成功重新规划
     */
    private boolean attemptReplan(PlanStep failedStep, MicroExecutorService.ExecutionResult result) {
        try {
            log.info("🔄 开始 Re-plan...");

            // 构建 Re-plan 请求
            String replanContext = String.format("""
                    ## Original Plan Step %d Execution Failed
                    Description %s

                    ## Post Mortem Report
                    %s

                    ## Completed Milestones
                    %s

                    Please replan remaining steps based on current screen state
                    """,
                    failedStep.getId(),
                    failedStep.getDescription(),
                    result.generatePlannerFeedback(),
                    globalContext.getCompletedMilestonesSummary());

            // 调用 Planner 重新生成计划
            TaskPlan newPlan = plannerService.generatePlan(
                    globalContext.getUserGoal() + "\n\n" + replanContext,
                    true);

            if (newPlan.getSteps().isEmpty()) {
                log.warn("❌ Re-plan 未能生成新步骤");
                return false;
            }

            // 替换当前计划
            currentPlan = newPlan;
            currentPlan.markStarted();

            log.info("✅ Re-plan 完成，新计划有 {} 个步骤", newPlan.getSteps().size());
            return true;

        } catch (Exception e) {
            log.error("❌ Re-plan 失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 判断是否应该中止任务
     */
    private boolean shouldAbort() {
        // 连续失败 3 步以上
        if (totalStepsFailed >= 3) {
            return true;
        }

        // 失败率超过 50%
        if (totalStepsExecuted > 0 &&
                (double) totalStepsFailed / totalStepsExecuted > 0.5) {
            return true;
        }

        return false;
    }

    /**
     * 恢复决策类型
     */
    public enum RecoveryDecision {
        RETRY_STEP, // 重试当前步骤
        SKIP_STEP, // 跳过当前步骤
        CONTINUE, // 继续执行下一步
        REPLAN, // 【新增】触发重新规划
        ABORT // 中止任务
    }

    /**
     * 基于验尸报告的智能恢复决策
     * 
     * 【架构升级】增加 REPLAN 决策支持
     * 
     * @param step   失败的步骤
     * @param result 执行结果（含验尸报告）
     * @return 恢复决策
     */
    private RecoveryDecision makeRecoveryDecision(PlanStep step, MicroExecutorService.ExecutionResult result) {
        PlanStep.PostMortem postMortem = result.getPostMortem();

        // 如果没有验尸报告，使用传统逻辑
        if (postMortem == null) {
            return shouldAbort() ? RecoveryDecision.ABORT : RecoveryDecision.CONTINUE;
        }

        // 基于失败原因决策
        return switch (postMortem.getFailureReason()) {
            case ELEMENT_NOT_FOUND -> {
                // 找不到元素：可能需要滚动或导航问题
                // 连续失败多次则尝试 Re-plan；否则重试
                if (consecutiveFailures >= 2) {
                    yield RecoveryDecision.REPLAN;
                }
                yield RecoveryDecision.RETRY_STEP;
            }
            case CLICK_MISSED -> {
                // 点击未命中：可能是坐标问题，重试或让下一步处理
                yield consecutiveFailures >= 2 ? RecoveryDecision.REPLAN : RecoveryDecision.RETRY_STEP;
            }
            case INFINITE_LOOP -> {
                // 死循环：严重问题，尝试 Re-plan 或中止
                yield consecutiveFailures < MAX_CONSECUTIVE_FAILURES ? RecoveryDecision.REPLAN : RecoveryDecision.ABORT;
            }
            case APP_NOT_RESPONDING -> {
                // 应用无响应：严重问题，应该中止
                yield RecoveryDecision.ABORT;
            }
            case UNEXPECTED_DIALOG -> {
                // 意外弹窗：可能需要重新规划来处理
                yield consecutiveFailures >= 1 ? RecoveryDecision.REPLAN : RecoveryDecision.RETRY_STEP;
            }
            case TIMEOUT -> {
                // 超时：可能是暂时性问题，重试或 Re-plan
                if (consecutiveFailures >= 2) {
                    yield RecoveryDecision.REPLAN;
                }
                yield RecoveryDecision.RETRY_STEP;
            }
            default -> {
                // 未知原因：基于连续失败次数决策
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    yield RecoveryDecision.REPLAN;
                }
                yield shouldAbort() ? RecoveryDecision.ABORT : RecoveryDecision.CONTINUE;
            }
        };
    }

    /**
     * 外部中断（/stop 指令）
     */
    public void interrupt() {
        interrupted = true;
        microExecutorService.requestInterrupt();
        state = OrchestratorState.FAILED;
        log.warn("🛑 TaskOrchestrator 收到中断信号");
    }

    /**
     * 是否处于中断状态
     */
    public boolean isInterrupted() {
        return interrupted;
    }

    /**
     * 处理用户中断
     */
    private OrchestratorResult handleInterrupt(String userGoal) {
        state = OrchestratorState.FAILED;
        if (currentPlan != null && !currentPlan.hasFailed()) {
            currentPlan.markFailed("用户中断任务");
        }
        log.warn("🛑 用户中断任务: {}", userGoal);
        // 【内存安全】中断时清理 GlobalContext
        cleanupGlobalContext();
        return OrchestratorResult.partial("任务已被用户中断", currentPlan);
    }

    /**
     * 清除中断状态
     */
    private void clearInterruptFlag() {
        interrupted = false;
        microExecutorService.clearInterrupt();
    }

    /**
     * 获取当前状态
     */
    public OrchestratorState getState() {
        return state;
    }

    /**
     * 获取当前计划
     */
    public TaskPlan getCurrentPlan() {
        return currentPlan;
    }

    /**
     * 【新增】获取全局上下文
     */
    public GlobalContext getGlobalContext() {
        return globalContext;
    }

    /**
     * 【内存安全】清理 GlobalContext
     * 在任务执行结束后（无论成功失败）调用，清空可能缓存的临时跨步骤数据
     */
    private void cleanupGlobalContext() {
        if (globalContext != null) {
            log.info("🧹 清理 GlobalContext [{}]", globalContext.getContextId());
            // 清理共享变量
            globalContext.getSharedVariables().clear();
            // 清理最近操作摘要（保留已完成里程碑用于日志）
            globalContext.getRecentActions().clear();
            // 清空当前屏幕状态
            globalContext.setCurrentScreenState(null);
            globalContext.setLastError(null);
            globalContext.setInRecoveryMode(false);
            // 注意：不清理 completedMilestones，因为它们可能用于日志和摘要
            log.debug("✅ GlobalContext 清理完成");
        }
    }

    /**
     * 获取执行摘要
     */
    public String getExecutionSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Execution Summary\n");
        sb.append("State: ").append(state).append("\n");
        sb.append("Executed: ").append(totalStepsExecuted).append(" steps\n");
        sb.append("Failed: ").append(totalStepsFailed).append(" steps\n");

        if (currentPlan != null) {
            sb.append("\n").append(currentPlan.generateSummary());
        }

        return sb.toString();
    }

    /**
     * 【新增】异步生成并发送拟人化TTS通知
     * 仅在最终步骤（计划完成时）调用，不阻塞主执行流程
     * 
     * @param userGoal 用户目标
     * @param stepsExecuted 执行的步数
     * @param executionTimeMs 执行耗时（毫秒）
     */
    private void generateAndSendVoiceAnnouncement(String userGoal, int stepsExecuted, long executionTimeMs) {
        CompletableFuture.runAsync(() -> {
            try {
                // 获取快速模型（优先使用 executor 模型，否则使用默认模型）
                ChatLanguageModel fastModel = null;
                if (executorModelAlias != null && !executorModelAlias.isBlank() 
                        && llmFactory.isModelAvailable(executorModelAlias)) {
                    fastModel = llmFactory.getModel(executorModelAlias);
                } else {
                    fastModel = llmFactory.getModel();
                }
                
                if (fastModel == null) {
                    log.warn("⚠️ 无法获取 LLM 模型，跳过 TTS 通知生成");
                    return;
                }
                
                // 构建提示词
                String systemPrompt = "You are a personified assistant Please summarize task completion in spoken language limit to 20 characters For example task completed WeChat message sent No unnecessary words just state the result";
                
                String userPrompt = String.format("""
                        User Goal %s
                        Executed %d steps took %d seconds
                        
                        Please summarize task completion in one sentence
                        """, 
                        userGoal, 
                        stepsExecuted, 
                        executionTimeMs / 1000);
                
                // 构建消息
                List<ChatMessage> messages = new ArrayList<>();
                messages.add(SystemMessage.from(systemPrompt));
                messages.add(UserMessage.from(userPrompt));
                
                // 调用 LLM 生成文本
                Response<AiMessage> response = fastModel.generate(messages);
                String announcementText = response.content().text();
                
                if (announcementText != null && !announcementText.trim().isEmpty()) {
                    // 清理文本（移除可能的引号、换行等）
                    announcementText = announcementText.trim()
                            .replaceAll("^[\"']|[\"']$", "") // 移除首尾引号
                            .replaceAll("\n+", " ") // 替换换行为空格
                            .trim();
                    
                    // 限制长度（20字以内）
                    if (announcementText.length() > 20) {
                        announcementText = announcementText.substring(0, 20) + "...";
                    }
                    
                    // 发送 TTS 通知
                    if (workflowEventService != null) {
                        workflowEventService.onVoiceAnnouncement(announcementText);
                        log.info("🎙️ TTS 通知已生成: {}", announcementText);
                    }
                } else {
                    log.warn("⚠️ LLM 返回空文本，跳过 TTS 通知");
                }
                
            } catch (Exception e) {
                log.error("❌ 生成 TTS 通知失败: {}", e.getMessage(), e);
                // 失败不影响主流程，静默处理
            }
        });
    }

    /**
     * 重置调度器
     */
    public void reset() {
        state = OrchestratorState.IDLE;
        currentPlan = null;
        globalContext = null;
        totalStepsExecuted = 0;
        totalStepsFailed = 0;
        consecutiveFailures = 0;
        clearInterruptFlag();
        plannerService.clearHistory();
        log.info("🔄 调度器已重置");
    }

    /**
     * 调度器状态枚举
     */
    public enum OrchestratorState {
        IDLE, // 空闲
        PLANNING, // 规划中
        EXECUTING, // 执行中
        STEP_SUCCESS, // 当前步骤成功
        STEP_FAILED, // 当前步骤失败
        COMPLETED, // 全部完成
        FAILED // 任务失败
    }

    /**
     * 调度器执行结果
     */
    @Data
    public static class OrchestratorResult {
        private final boolean success;
        private final boolean partial;
        private final String message;
        private final TaskPlan plan;

        private OrchestratorResult(boolean success, boolean partial, String message, TaskPlan plan) {
            this.success = success;
            this.partial = partial;
            this.message = message;
            this.plan = plan;
        }

        public static OrchestratorResult success(String message, TaskPlan plan) {
            return new OrchestratorResult(true, false, message, plan);
        }

        public static OrchestratorResult partial(String message, TaskPlan plan) {
            return new OrchestratorResult(false, true, message, plan);
        }

        public static OrchestratorResult failed(String message) {
            return new OrchestratorResult(false, false, message, null);
        }

        /**
         * 【新增】带验尸报告的失败结果
         */
        public static OrchestratorResult failed(String message, PlanStep.PostMortem postMortem) {
            OrchestratorResult result = new OrchestratorResult(false, false, message, null);
            // 可以在这里记录 postMortem 用于后续分析
            return result;
        }

        @Override
        public String toString() {
            String icon = success ? "✅" : (partial ? "⚠️" : "❌");
            return icon + " " + message;
        }
    }
}
