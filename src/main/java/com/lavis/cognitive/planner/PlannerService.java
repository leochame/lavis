package com.lavis.cognitive.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lavis.cognitive.model.PlanStep;
import com.lavis.cognitive.model.TaskPlan;
import com.lavis.perception.ScreenCapturer;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    // LLM 模型
    private ChatLanguageModel chatModel;

    // 全局历史 - 只记录高层对话
    private final List<ChatMessage> globalHistory = new ArrayList<>();

    // 规划专用的 System Prompt - 【架构升级】里程碑级规划
    private static final String PLANNER_SYSTEM_PROMPT = """
            You are a strategic planning expert acting as a CEO role responsible for breaking down user goals into milestone level execution steps

            ## Core Constraints Must Follow
            1. **No micro operations**: Do not output specific coordinates pixel positions or atomic actions such as click 300 200
            2. **Direction only**: You are only responsible for what to do ,you are not responsible for how to do it
            3. **Milestone thinking**: Each step should be a verifiable business milestone not a single mouse operation
            4. **Must define completion criteria**: Each step must include Definition of Done how to determine if the step is completed

            ## High Level Semantic Instructions Milestone Types
            - **LAUNCH_APP**: Launch and ensure application is ready such as launch WeChat wait for main interface to appear
            - **NAVIGATE_TO**: Navigate to specific functional area such as enter settings page open personal profile
            - **EXECUTE_WORKFLOW**: Execute complete business process such as complete form filling and submit edit and save document
            - **VERIFY_STATE**: Verify current state such as confirm logged in confirm publish successful

            ## Prohibited Step Types
            - CLICK: Do not plan single clicks leave to Executor to decide
            - TYPE: Do not plan single inputs leave to Executor to decide
            Any instruction containing coordinates

            ## Output Format
            Please output the plan in JSON format
            {
              "plan": [
                {
                  "id": 1,
                  "desc": "Milestone description what to do not how to do it",
                  "type": "LAUNCH_APP",
                  "dod": "Completion state definition what to see to consider it done",
                  "complexity": 1-5 complexity assessment
                }
              ]
            }

            ## Complexity Assessment Standards
            - **1 Simple**: Single clear operation such as launching application
            - **2 Relatively Simple**: Requires 2-3 interactions such as navigating to a page
            - **3 Medium**: Requires 4-6 interactions such as searching and selecting result
            - **4 Relatively Complex**: Requires multi step form filling or selection
            - **5 Complex**: Complete workflow containing multiple sub steps

            ## Example
            User Goal: Open WeChat send message to Zhang San

            ## Output
            {
              "plan": [
                {
                  "id": 1,
                  "desc": "Launch WeChat application and wait for main interface ready",
                  "type": "LAUNCH_APP",
                  "dod": "See WeChat main interface containing chat list and search box",
                  "complexity": 1
                },
                {
                  "id": 2,
                  "desc": "Search and enter chat with Zhang San",
                  "type": "NAVIGATE_TO",
                  "dod": "Enter chat window with Zhang San see chat history and input box",
                  "complexity": 3
                },
                {
                  "id": 3,
                  "desc": "Send message",
                  "type": "EXECUTE_WORKFLOW",
                  "dod": "Message sent see sent message in chat window",
                  "complexity": 2
                }
              ]
            }

            ## Important Notes
            - **Only output JSON**: Do not output other content
            - **Step count is usually 2-5**: Do not be too fragmented
            - **Each step must have clear dod completion state definition**: Each step must include Definition of Done how to determine if the step is completed
            """;

    public PlannerService(ScreenCapturer screenCapturer) {
        this.screenCapturer = screenCapturer;
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
     * 生成任务计划
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
            // 构建消息
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(PLANNER_SYSTEM_PROMPT));

            String userPrompt;
            if (withScreenshot) {
                String screenshot = screenCapturer.captureScreenWithCursorAsBase64();
                userPrompt = String.format("""
                        ## User Goal
                        %s

                        ##Current Screen State
                        Please refer to the current screen state in the attached image to create the plan

                        Please output the execution plan in JSON format
                        """, userGoal);

                messages.add(UserMessage.from(
                        TextContent.from(userPrompt),
                        ImageContent.from(screenshot, "image/jpeg")));
            } else {
                userPrompt = String.format("""
                        ##User Goal
                        %s

                        Please output the execution plan in JSON format
                        """, userGoal);

                messages.add(UserMessage.from(userPrompt));
            }

            // 调用 LLM
            Response<AiMessage> response = chatModel.generate(messages);
            String responseText = response.content().text();

            log.debug("📝 LLM 响应: {}", responseText);

            // 解析 JSON
            List<PlanStep> steps = parseStepsFromResponse(responseText);
            plan.addSteps(steps);

            // 记录到全局历史
            globalHistory.add(UserMessage.from("目标: " + userGoal));
            globalHistory.add(AiMessage.from("计划: " + steps.size() + " 个步骤"));

            log.info("✅ 计划生成完成: {} 个步骤", steps.size());
            for (PlanStep step : steps) {
                log.info("   {} - {} [{}]", step.getId(), step.getDescription(), step.getType());
            }

        } catch (Exception e) {
            log.error("❌ 计划生成失败: {}", e.getMessage(), e);
            // 创建一个简单的单步计划
            PlanStep fallbackStep = PlanStep.builder()
                    .description(userGoal)
                    .type(PlanStep.StepType.COMPLEX)
                    .build();
            plan.addStep(fallbackStep);
        }

        return plan;
    }

    /**
     * 从 LLM 响应中解析步骤
     */
    private List<PlanStep> parseStepsFromResponse(String responseText) {
        List<PlanStep> steps = new ArrayList<>();

        try {
            // 提取 JSON 部分
            String json = extractJson(responseText);

            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                JsonNode planNode = root.get("plan");

                if (planNode != null && planNode.isArray()) {
                    for (JsonNode stepNode : planNode) {
                        PlanStep step = PlanStep.builder()
                                .id(stepNode.has("id") ? stepNode.get("id").asInt() : 0)
                                .description(stepNode.has("desc") ? stepNode.get("desc").asText()
                                        : stepNode.has("description") ? stepNode.get("description").asText() : "未知步骤")
                                .type(parseStepType(stepNode.has("type") ? stepNode.get("type").asText() : "UNKNOWN"))
                                // 【新增】解析完成状态定义
                                .definitionOfDone(stepNode.has("dod") ? stepNode.get("dod").asText() : null)
                                // 【新增】解析复杂度
                                .complexity(stepNode.has("complexity") ? stepNode.get("complexity").asInt() : 3)
                                .build();

                        // 【新增】根据复杂度动态设置 maxRetries 和 timeout
                        step.applyDynamicParameters();

                        steps.add(step);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("JSON 解析失败，尝试文本解析: {}", e.getMessage());
            // 降级为文本解析
            steps = parseStepsFromText(responseText);
        }

        // 如果解析失败，至少返回一个步骤
        if (steps.isEmpty()) {
            log.warn("未能解析出任何步骤，创建默认步骤");
            steps.add(PlanStep.builder()
                    .description(responseText.substring(0, Math.min(100, responseText.length())))
                    .type(PlanStep.StepType.COMPLEX)
                    .build());
        }

        return steps;
    }

    /**
     * 从响应文本中提取 JSON
     */
    private String extractJson(String text) {
        // 尝试匹配 ```json ... ``` 代码块
        Pattern codeBlockPattern = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```");
        Matcher matcher = codeBlockPattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 尝试匹配 ``` ... ``` 代码块
        Pattern genericBlockPattern = Pattern.compile("```\\s*([\\s\\S]*?)\\s*```");
        matcher = genericBlockPattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 尝试直接解析整个文本为 JSON
        if (text.trim().startsWith("{")) {
            return text.trim();
        }

        return null;
    }

    /**
     * 从纯文本解析步骤（降级方案）
     */
    private List<PlanStep> parseStepsFromText(String text) {
        List<PlanStep> steps = new ArrayList<>();

        // 匹配 "1. xxx" 或 "- xxx" 格式
        Pattern pattern = Pattern.compile("(?:^|\\n)\\s*(?:(\\d+)[.、)]|[-*])\\s*(.+?)(?=\\n|$)");
        Matcher matcher = pattern.matcher(text);

        int id = 1;
        while (matcher.find()) {
            String desc = matcher.group(2).trim();
            if (!desc.isEmpty()) {
                steps.add(PlanStep.builder()
                        .id(id++)
                        .description(desc)
                        .type(guessStepType(desc))
                        .build());
            }
        }

        return steps;
    }

    /**
     * 解析步骤类型
     */
    private PlanStep.StepType parseStepType(String typeStr) {
        try {
            return PlanStep.StepType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PlanStep.StepType.UNKNOWN;
        }
    }

    /**
     * 根据描述猜测步骤类型（优先匹配里程碑类型）
     */
    private PlanStep.StepType guessStepType(String desc) {
        desc = desc.toLowerCase();

        // 优先匹配里程碑级类型
        if (desc.contains("启动") || desc.contains("打开应用") || desc.contains("launch")) {
            return PlanStep.StepType.LAUNCH_APP;
        } else if (desc.contains("导航") || desc.contains("进入") || desc.contains("跳转") ||
                desc.contains("navigate") || desc.contains("go to")) {
            return PlanStep.StepType.NAVIGATE_TO;
        } else if (desc.contains("完成") || desc.contains("提交") || desc.contains("发送") ||
                desc.contains("workflow") || desc.contains("execute")) {
            return PlanStep.StepType.EXECUTE_WORKFLOW;
        } else if (desc.contains("确认") || desc.contains("验证") || desc.contains("检查") ||
                desc.contains("verify")) {
            return PlanStep.StepType.VERIFY_STATE;
        }

        return PlanStep.StepType.COMPLEX;
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
