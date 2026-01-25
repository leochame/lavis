package com.lavis.cognitive.planner;

import com.lavis.cognitive.model.PlanStep;
import com.lavis.cognitive.model.TaskPlan;
import com.lavis.perception.ScreenCapturer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 规划器服务 (Planner Service) - 战略层
 * 
 * 核心职责：
 * 1. 理解用户意图
 * 2. 生成任务步骤表 (Task Plan)
 * 3. 监控整体进度
 * 
 * 设计哲学：
 * - Planner 只关心"做什么"，不关心"怎么做"
 * - 保持高层上下文"干净"，只记录步骤级别的状态
 * - 不涉及具体的坐标、点击等细节
 */
@Slf4j
@Service
public class PlannerService {

    private final ScreenCapturer screenCapturer;
    private final PlanTools planTools;

    // LLM 模型
    private ChatLanguageModel chatModel;

    // 全局历史 - 只记录高层对话
    private final List<ChatMessage> globalHistory = new ArrayList<>();

    // 规划专用的 System Prompt - 【架构升级】使用 Tool Call 方式
    private static final String PLANNER_SYSTEM_PROMPT = """
            You are a strategic planning expert acting as a CEO role responsible for breaking down user goals into milestone level execution steps

            ## Core Constraints Must Follow
            1. **No micro operations**: Do not output specific coordinates pixel positions or atomic actions such as click 300 200
            2. **Direction only**: You are only responsible for what to do, you are not responsible for how to do it
            3. **Milestone thinking**: Each step should be a verifiable business milestone not a single mouse operation

            ## Prohibited Operations
            - Do not plan single clicks, leave to Executor to decide
            - Do not plan single text inputs, leave to Executor to decide
            - Do not include any coordinates or pixel positions

            ## How to Create Plan
            Use the `addPlanStep` tool to add each step to the plan. Call this tool multiple times to build the complete plan.

            ## Step Description Guidelines
            - Each step should be a clear milestone-level task
            - Describe what to do, not how to do it
            - Examples:
              * Good: "Launch WeChat application and wait for main interface ready"
              * Good: "Navigate to profile page"
              * Good: "Complete and submit the form"
              * Bad: "Click at coordinate (300, 200)"
              * Bad: "Type text 'hello'"

            ## Example
            User Goal: Open WeChat send message to Zhang San

            You should call:
            1. addPlanStep(id=1, desc="Launch WeChat application and wait for main interface ready")
            2. addPlanStep(id=2, desc="Search and enter chat with Zhang San")
            3. addPlanStep(id=3, desc="Send message")

            ## Important Notes
            - **Use tools to create plan**: Call `addPlanStep` tool for each step
            - **Step count is usually 2-5**: Do not be too fragmented
            - **Start from id=1**: Step IDs should be sequential starting from 1
            """;

    public PlannerService(ScreenCapturer screenCapturer, PlanTools planTools) {
        this.screenCapturer = screenCapturer;
        this.planTools = planTools;
    }

    /**
     * 初始化 LLM 模型
     */
    public void initialize(ChatLanguageModel model) {
        this.chatModel = model;
        log.info("✅ PlannerService 初始化完成");
    }

    /**
     * 生成任务计划
     * 
     * @param userGoal 用户目标
     * @return 任务计划
     */
    public TaskPlan generatePlan(String userGoal) {
        return generatePlan(userGoal, true);
    }

    /**
     * 生成任务计划 - 使用 Tool Call 方式
     * 
     * @param userGoal       用户目标
     * @param withScreenshot 是否包含当前屏幕截图
     * @return 任务计划
     */
    public TaskPlan generatePlan(String userGoal, boolean withScreenshot) {
        log.info("📋 开始规划任务: {}", userGoal);

        if (chatModel == null) {
            throw new IllegalStateException("PlannerService 未初始化");
        }

        TaskPlan plan = new TaskPlan(userGoal);

        try {
            // 清空之前的步骤
            planTools.clear();

            // 构建消息
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(PLANNER_SYSTEM_PROMPT));

            String userPrompt;
            if (withScreenshot) {
                String screenshot = screenCapturer.captureScreenWithCursorAsBase64();
                userPrompt = String.format("""
                        ## User Goal
                        %s

                        ## Current Screen State
                        Please refer to the current screen state in the attached image to create the plan.

                        Use the addPlanStep tool to create the execution plan.
                        """, userGoal);

                messages.add(UserMessage.from(
                        TextContent.from(userPrompt),
                        ImageContent.from(screenshot, "image/jpeg")));
            } else {
                userPrompt = String.format("""
                        ## User Goal
                        %s

                        Use the addPlanStep tool to create the execution plan.
                        """, userGoal);

                messages.add(UserMessage.from(userPrompt));
            }

            // 获取工具规格
            List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecs = 
                    ToolSpecifications.toolSpecificationsFrom(planTools);

            // 工具调用循环（最多 10 次迭代）
            int maxIterations = 10;
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                log.debug("🔄 规划迭代 {}/{}", iteration + 1, maxIterations);

                // 调用 LLM
                Response<AiMessage> response = chatModel.generate(messages, toolSpecs);
                AiMessage aiMessage = response.content();
                messages.add(aiMessage);

                log.debug("🤖 Planner 响应: {}", aiMessage);

                // 检查是否有工具调用请求
                if (!aiMessage.hasToolExecutionRequests()) {
                    // 没有工具调用，说明规划完成或出错
                    String textResponse = aiMessage.text();
                    if (textResponse != null && !textResponse.isBlank()) {
                        log.debug("📝 Planner 文本响应: {}", textResponse);
                    }
                    break;
                }

                // 执行工具调用
                List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
                log.debug("🔧 执行 {} 个工具调用", toolRequests.size());

                for (ToolExecutionRequest request : toolRequests) {
                    String toolName = request.name();
                    String toolArgs = request.arguments();

                    log.debug("  → 调用工具: {}({})", toolName, toolArgs);

                    if ("addPlanStep".equals(toolName)) {
                        // 工具会在 PlanTools 中执行，步骤会被收集
                        String result = planTools.addPlanStep(
                                extractIntArg(toolArgs, "id"),
                                extractStringArg(toolArgs, "desc")
                        );
                        log.debug("  ← 工具结果: {}", result);

                        // 添加工具执行结果到消息列表
                        dev.langchain4j.data.message.ToolExecutionResultMessage toolResult = 
                                dev.langchain4j.data.message.ToolExecutionResultMessage.from(request, result);
                        messages.add(toolResult);
                    } else {
                        log.warn("⚠️ 未知工具: {}", toolName);
                    }
                }
            }

            // 获取收集的步骤
            List<PlanStep> steps = new ArrayList<>(planTools.getCollectedSteps());
            
            if (steps.isEmpty()) {
                log.warn("⚠️ 未能生成任何步骤，创建默认步骤");
                PlanStep fallbackStep = PlanStep.builder()
                        .id(1)
                        .description(userGoal)
                        .build();
                steps.add(fallbackStep);
            }

            plan.addSteps(steps);

            // 记录到全局历史
            globalHistory.add(UserMessage.from("目标: " + userGoal));
            globalHistory.add(AiMessage.from("计划: " + steps.size() + " 个步骤"));

            log.info("✅ 计划生成完成: {} 个步骤", steps.size());
            for (PlanStep step : steps) {
                log.info("   {} - {}", step.getId(), step.getDescription());
            }

        } catch (Exception e) {
            log.error("❌ 计划生成失败: {}", e.getMessage(), e);
            // 创建一个简单的单步计划
            PlanStep fallbackStep = PlanStep.builder()
                    .id(1)
                    .description(userGoal)
                    .build();
            plan.addStep(fallbackStep);
        }

        return plan;
    }

    /**
     * 从工具参数 JSON 中提取整数参数
     */
    private int extractIntArg(String argsJson, String key) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(argsJson);
            com.fasterxml.jackson.databind.JsonNode value = root.get(key);
            return value != null ? value.asInt() : 0;
        } catch (Exception e) {
            log.warn("⚠️ 提取参数失败: key={}, args={}", key, argsJson);
            return 0;
        }
    }

    /**
     * 从工具参数 JSON 中提取字符串参数
     */
    private String extractStringArg(String argsJson, String key) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(argsJson);
            com.fasterxml.jackson.databind.JsonNode value = root.get(key);
            return value != null ? value.asText() : "";
        } catch (Exception e) {
            log.warn("⚠️ 提取参数失败: key={}, args={}", key, argsJson);
            return "";
        }
    }



    /**
     * 更新计划状态（当步骤完成时调用）
     */
    public void updatePlanProgress(TaskPlan plan, PlanStep completedStep, boolean success) {
        String statusMsg = success
                ? String.format("✅ 步骤 %d 完成: %s", completedStep.getId(), completedStep.getDescription())
                : String.format("❌ 步骤 %d 失败: %s", completedStep.getId(), completedStep.getDescription());

        globalHistory.add(AiMessage.from(statusMsg));
        log.info(statusMsg);
    }

    /**
     * 获取全局历史摘要
     */
    public String getGlobalHistorySummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 执行历史\n");

        int start = Math.max(0, globalHistory.size() - 10);
        for (int i = start; i < globalHistory.size(); i++) {
            ChatMessage msg = globalHistory.get(i);
            if (msg instanceof UserMessage userMsg) {
                // 手动提取所有 TextContent 的文本，支持多模态消息（文本+图片）
                StringBuilder textBuilder = new StringBuilder();
                for (Content content : userMsg.contents()) {
                    if (content instanceof TextContent textContent) {
                        textBuilder.append(textContent.text());
                    }
                }
                String text = textBuilder.toString();
                if (text != null && !text.isBlank()) {
                    sb.append("👤 ").append(text).append("\n");
                } else {
                    sb.append("👤 [多模态消息，无文本内容]\n");
                }
            } else if (msg instanceof AiMessage) {
                sb.append("🤖 ").append(((AiMessage) msg).text()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 清空全局历史
     */
    public void clearHistory() {
        globalHistory.clear();
        log.info("🔄 Planner 历史已清空");
    }
}
