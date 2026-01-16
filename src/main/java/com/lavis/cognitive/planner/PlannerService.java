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
            你是一个**战略规划专家**（CEO角色），负责将用户目标拆解为**里程碑级**的执行步骤。

            ## ⚠️ 核心约束（必须遵守！）
            1. **禁止微操**：不要输出具体的坐标、像素位置或原子动作（如"点击 (300, 200)"）
            2. **只定方向**：你只负责"做什么"，不关心"怎么做"
            3. **里程碑思维**：每个步骤应该是一个可验证的业务里程碑，而非单次鼠标操作
            4. **必须定义完成标准**：每个步骤必须包含 Definition of Done（如何判断该步骤已完成）

            ## 高层语义指令（里程碑类型）
            - **LAUNCH_APP**: 启动并确保应用就绪（如"启动微信，等待主界面出现"）
            - **NAVIGATE_TO**: 导航至特定功能区（如"进入设置页"、"打开个人主页"）
            - **EXECUTE_WORKFLOW**: 执行完整业务流程（如"完成表单填写并提交"、"编辑并保存文档"）
            - **VERIFY_STATE**: 验证当前状态（如"确认已登录"、"确认发布成功"）

            ## ❌ 禁止的步骤类型
            - CLICK: 不要规划单次点击（交给 Executor 自行决定）
            - TYPE: 不要规划单次输入（交给 Executor 自行决定）
            - 任何包含坐标的指令

            ## 输出格式
            请以 JSON 格式输出计划：
            ```json
            {
              "plan": [
                {
                  "id": 1,
                  "desc": "里程碑描述（做什么，不是怎么做）",
                  "type": "LAUNCH_APP",
                  "dod": "完成状态定义（看到什么就算完成）",
                  "complexity": 1-5（复杂度评估）
                }
              ]
            }
            ```

            ## 复杂度评估标准
            - **1 (简单)**: 单个明确操作，如启动应用
            - **2 (较简单)**: 需要2-3次交互，如导航到某页面
            - **3 (中等)**: 需要4-6次交互，如搜索并选择结果
            - **4 (较复杂)**: 需要多步表单填写或选择
            - **5 (复杂)**: 完整工作流，包含多个子步骤

            ## 示例
            用户目标: "打开微信发送消息给张三"

            输出:
            ```json
            {
              "plan": [
                {
                  "id": 1,
                  "desc": "启动微信应用并等待主界面就绪",
                  "type": "LAUNCH_APP",
                  "dod": "看到微信主界面，包含聊天列表和搜索框",
                  "complexity": 1
                },
                {
                  "id": 2,
                  "desc": "搜索并进入与张三的聊天",
                  "type": "NAVIGATE_TO",
                  "dod": "进入与张三的聊天窗口，看到聊天记录和输入框",
                  "complexity": 3
                },
                {
                  "id": 3,
                  "desc": "发送消息",
                  "type": "EXECUTE_WORKFLOW",
                  "dod": "消息已发送，在聊天窗口中看到发送的消息",
                  "complexity": 2
                }
              ]
            }
            ```

            ## 重要提示
            - 只输出 JSON，不要输出其他内容
            - 步骤数量通常为 2-5 个，不要过于细碎
            - 每个步骤必须有明确的 dod（完成状态定义）
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
                        ## 用户目标
                        %s

                        ## 当前屏幕状态
                        请参考附图中的当前屏幕状态来制定计划。

                        请输出 JSON 格式的执行计划。
                        """, userGoal);

                messages.add(UserMessage.from(
                        TextContent.from(userPrompt),
                        ImageContent.from(screenshot, "image/jpeg")));
            } else {
                userPrompt = String.format("""
                        ## 用户目标
                        %s

                        请输出 JSON 格式的执行计划。
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
            if (msg instanceof UserMessage) {
                sb.append("👤 ").append(((UserMessage) msg).singleText()).append("\n");
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
