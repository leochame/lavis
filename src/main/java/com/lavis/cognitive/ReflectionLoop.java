package com.lavis.cognitive;

import com.lavis.perception.ScreenCapturer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * M2 思考模块 - 反思循环
 * 类似于 Flowith 的反思机制：执行动作 -> 再次截图 -> 询问 "任务完成了吗？" -> 修正或结束
 * 实现 Action-Observation-Correction (行动-观察-修正) 闭环
 * 
 * @deprecated 已废弃。请使用 {@link com.lavis.cognitive.orchestrator.TaskOrchestrator} 作为统一任务执行入口。
 *             TaskOrchestrator 实现了更完善的 M-E-R (Memory-Execution-Reflection) 闭环，
 *             并通过 GlobalContext 解决了"失忆症"问题。
 *             
 *             迁移指南：
 *             - 旧: reflectionLoop.executeWithReflection(task, callback)
 *             - 新: taskOrchestrator.executeGoal(task) 或 agentService.executePlanTask(task)
 */
@Deprecated(since = "2.0", forRemoval = true)
@Slf4j
@Component
@RequiredArgsConstructor
public class ReflectionLoop {

    private final ScreenCapturer screenCapturer;
    private final AgentService agentService;

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${reflection.max.iterations:5}")
    private int maxIterations;

    @Value("${reflection.delay.ms:1000}")
    private int reflectionDelayMs;

    @Value("${gemini.model:gemini-2.0-flash}")
    private String modelName;

    private ChatLanguageModel reflectionModel;

    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API Key 未配置");
            return;
        }

        try {
            // 使用专门的模型进行反思判断
//            this.reflectionModel = GoogleAiGeminiChatModel.builder()
//                    .apiKey(apiKey)
//                    .modelName(modelName)
//                    .temperature(0.3) // 低温度保证稳定性
//                    .build();
            this.reflectionModel = OpenAiChatModel.builder()
                    .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature(0.3) // 低温度保证稳定性
                    .build();

            log.info("ReflectionLoop 初始化完成");
        } catch (Exception e) {
            log.error("ReflectionLoop 初始化失败", e);
        }
    }

    /**
     * 执行带反思的任务（持续执行模式）
     * 
     * 【重构】实现"小步快跑 + 高频反思 + 持续迭代"的闭环
     * - AgentService 每次只执行 1-2 步，然后交还控制权
     * - ReflectionLoop 作为主控，持续监控和修正
     * 
     * @param task 任务描述
     * @param progressCallback 进度回调
     * @return 执行结果
     */
    public ReflectionResult executeWithReflection(String task, Consumer<String> progressCallback) {
        log.info("🚀 开始持续反思执行模式: {}", task);
        
        List<String> globalActionHistory = new ArrayList<>();
        // 维护反思上下文，用于传递修正建议和下一步提示
        StringBuilder reflectionContext = new StringBuilder();
        
        ReflectionResult result = new ReflectionResult();
        result.setTask(task);
        
        // 初始状态
        notifyProgress(progressCallback, "📋 开始执行任务: " + task);
        
        int totalSteps = 0;
        int maxTotalSteps = 50; // 防止无限运行的安全上限
        
        // 【关键改动】使用 while 循环实现持续执行
        while (totalSteps < maxTotalSteps) {
            notifyProgress(progressCallback, String.format("🔄 步骤 %d (总上限 %d)", totalSteps + 1, maxTotalSteps));
            
            try {
                // 1. 构建当前轮次的提示词（包含之前的反思修正）
                String currentPrompt = buildPrompt(task, globalActionHistory, reflectionContext.toString());
                
                // 2. 调用 AgentService，强制只执行 1 步
                // 这样可以确保每一步都能被反思捕捉到，避免内层死循环
                String executionResult = agentService.chatWithScreenshot(currentPrompt, 1);
                
                // 记录执行结果
                globalActionHistory.add(executionResult);
                totalSteps++;
                
                notifyProgress(progressCallback, "✅ 执行: " + truncate(executionResult, 100));
                
                // 3. 等待 UI 响应（给界面一点时间变化）
                Thread.sleep(reflectionDelayMs);
                
                // 4. 【关键】独立反思检查
                ReflectionStatus status = checkCompletion(task, globalActionHistory);
                notifyProgress(progressCallback, "🔍 反思结论: " + status.getMessage());
                
                // 5. 决策分支
                if (status.isCompleted()) {
                    result.setSuccess(true);
                    result.setMessage("✅ 任务完成: " + status.getMessage());
                    result.setIterations(totalSteps);
                    result.setActionHistory(globalActionHistory);
                    notifyProgress(progressCallback, "🎉 任务完成!");
                    return result;
                }
                
                if (status.needsCorrection()) {
                    // 如果反思认为出错了，生成具体的修正指令
                    String correction = String.format("\n⚠️ [系统反思修正]: 上一步操作可能有误或未生效。\n建议: %s\n请严格按照此建议调整下一步操作。", 
                                                    status.getCorrectionHint() != null ? status.getCorrectionHint() : "请重新分析屏幕并调整策略");
                    reflectionContext.setLength(0); // 清除旧上下文
                    reflectionContext.append(correction);
                    notifyProgress(progressCallback, "🛠️ 生成修正: " + status.getCorrectionHint());
                } else {
                    // 如果正常，清除之前的修正提示，保持 Context 干净
                    reflectionContext.setLength(0);
                    // 可以加入下一步建议
                    if (status.getCorrectionHint() != null && !status.getCorrectionHint().isEmpty()) {
                        reflectionContext.append("\n💡 下一步建议: ").append(status.getCorrectionHint());
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.setSuccess(false);
                result.setMessage("任务被中断");
                result.setIterations(totalSteps);
                result.setActionHistory(globalActionHistory);
                return result;
            } catch (Exception e) {
                log.error("反思循环执行异常", e);
                globalActionHistory.add("[错误] " + e.getMessage());
                notifyProgress(progressCallback, "❌ 错误: " + e.getMessage());
            }
        }
        
        // 达到最大步数限制
        result.setSuccess(false);
        result.setMessage("❌ 达到最大步数限制 (" + maxTotalSteps + ")，任务未完成");
        result.setIterations(totalSteps);
        result.setActionHistory(globalActionHistory);
        notifyProgress(progressCallback, "⏱️ 达到最大步数限制");
        
        return result;
    }

    /**
     * 构建当前轮次的提示词（包含历史记录和反思上下文）
     * 
     * @param task 任务描述
     * @param history 操作历史
     * @param reflectionContext 反思上下文（修正建议、下一步提示等）
     * @return 构建好的提示词
     */
    private String buildPrompt(String task, List<String> history, String reflectionContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前任务: ").append(task).append("\n\n");
        
        // 只保留最近 5 条历史，避免 Token 爆炸
        int start = Math.max(0, history.size() - 5);
        if (start < history.size()) {
            sb.append("最近操作历史:\n");
            for (int i = start; i < history.size(); i++) {
                sb.append(String.format("%d. %s\n", i + 1, history.get(i)));
            }
            sb.append("\n");
        }
        
        // 注入反思层的"上帝视角"建议
        if (reflectionContext != null && !reflectionContext.isEmpty()) {
            sb.append(reflectionContext).append("\n");
        }
        
        sb.append("\n请基于最新截图，继续执行下一步操作。");
        return sb.toString();
    }

    /**
     * 检查任务是否完成
     * 使用带标记的截图（显示鼠标位置和上次点击位置）进行反思
     */
    private ReflectionStatus checkCompletion(String task, List<String> history) {
        if (reflectionModel == null) {
            return new ReflectionStatus(false, false, "反思模型未初始化", null);
        }

        try {
            // 获取带标记的截图（显示鼠标和点击位置）
            String base64Image = screenCapturer.captureScreenWithCursorAsBase64();
            
            // 构建增强的反思提示（支持持续执行模式）
            String reflectionPrompt = String.format("""
                你是一个自动化任务的裁判。请基于截图和历史判断当前状态。
                
                任务: %s
                
                已执行的操作:
                %s
                
                ## 截图中的视觉辅助说明
                - 🟡 黄色网格线：20x20 辅助定位网格，每格约 38x24 像素
                - 🟠 橙色粗线：每5格的主分割线
                - 顶部/左侧数字：像素坐标刻度（显示逻辑屏幕坐标）
                - 🔴 红色十字 + 坐标：当前鼠标位置（显示逻辑屏幕坐标）
                - 🟢 绿色圆环 + 标签：上一次点击的位置（显示逻辑屏幕坐标）
                
                ## 判断标准
                1. **COMPLETED**: 任务彻底完成（如看到"发布成功"提示、目标状态已达成）
                2. **STUCK**: 画面与上一步完全一样，且操作无效（如反复点击同一位置无反应）
                3. **CONTINUE**: 任务正常进行中，需要继续执行下一步
                
                请利用网格坐标仔细观察截图，判断:
                1. 任务是否已经完成？
                2. 上次点击位置（绿色圆环）是否正确命中了预期的目标元素？
                3. 如果点击偏离了目标，参考网格估算偏离了多少像素？应该如何调整？
                4. 界面上是否出现了预期的变化（按钮高亮、弹窗、页面跳转等）？
                5. 有没有出现错误提示或异常情况？
                6. 是否陷入死循环（重复相同操作无效果）？
                
                请按以下格式回答:
                STATUS: [COMPLETED | STUCK | CONTINUE]
                REASON: [判断理由，详细说明为什么是这个状态]
                CLICK_ACCURACY: [ACCURATE | MISSED] (点击是否准确命中目标)
                OFFSET: [如果点击偏离，参考网格估算偏离方向和像素，如 "向右偏移约1格(38px)"，否则写 "NONE"]
                ADVICE: [给 Agent 的下一步具体操作建议，如果是点击，请给出预估的逻辑屏幕坐标，如 "点击 (420, 280)"]
                ERROR: [如果有错误，描述错误，否则写 "NONE"]
                """, 
                task,
                String.join("\n", history.isEmpty() ? List.of("(刚开始执行)") : history)
            );
            
            // 发送多模态请求
            UserMessage userMessage = UserMessage.from(
                TextContent.from(reflectionPrompt),
                ImageContent.from(base64Image, "image/jpeg")
            );
            
            Response<AiMessage> response = reflectionModel.generate(userMessage);
            String aiResponse = response.content().text();
            
            log.debug("反思结果: {}", aiResponse);
            
            // 解析响应
            return parseReflectionResponse(aiResponse);
            
        } catch (IOException e) {
            log.error("反思检查失败", e);
            return new ReflectionStatus(false, true, "截图失败", "请重试");
        }
    }

    /**
     * 解析反思响应（支持新的 STATUS 格式）
     */
    private ReflectionStatus parseReflectionResponse(String response) {
        String upperResponse = response.toUpperCase();
        
        // 解析 STATUS（新格式）
        boolean completed = false;
        boolean stuck = false;
        
        if (upperResponse.contains("STATUS:")) {
            String statusLine = extractField(response, "STATUS:");
            if (statusLine != null) {
                statusLine = statusLine.toUpperCase().trim();
                completed = statusLine.contains("COMPLETED");
                stuck = statusLine.contains("STUCK");
            }
        } else {
            // 兼容旧格式
            completed = upperResponse.contains("COMPLETED: YES") || 
                        upperResponse.contains("COMPLETED:YES");
        }
        
        // 检查是否有错误或点击不准确
        boolean hasError = false;
        String errorField = extractField(response, "ERROR:");
        if (errorField != null && !errorField.trim().equalsIgnoreCase("NONE") && 
            !errorField.trim().equalsIgnoreCase("NO") && !errorField.trim().equals("无")) {
            hasError = true;
        }
        
        boolean clickMissed = upperResponse.contains("CLICK_ACCURACY: MISSED") ||
                             upperResponse.contains("CLICK_ACCURACY:MISSED");
        
        // 如果状态是 STUCK 或 CONTINUE 且有错误/点击失败，则需要修正
        boolean needsCorrection = stuck || hasError || clickMissed;
        
        // 构建状态消息
        StringBuilder messageBuilder = new StringBuilder();
        if (completed) {
            messageBuilder.append("任务已完成");
        } else if (stuck) {
            messageBuilder.append("任务陷入停滞（需要修正策略）");
        } else {
            messageBuilder.append("任务进行中");
            if (clickMissed) {
                messageBuilder.append(" (点击偏离，需要调整)");
            }
        }
        
        // 提取 REASON
        String reason = extractField(response, "REASON:");
        if (reason != null && !reason.trim().isEmpty()) {
            messageBuilder.append(" - ").append(reason.trim());
        }
        
        String correctionHint = null;
        StringBuilder hintBuilder = new StringBuilder();
        
        // 提取偏移信息
        String offset = extractField(response, "OFFSET:");
        if (offset != null && !offset.trim().equalsIgnoreCase("NONE") && !offset.trim().equals("无")) {
            hintBuilder.append("点击偏移: ").append(offset.trim()).append("; ");
        }
        
        // 提取 ADVICE（新格式）或 NEXT_STEP（旧格式）
        String advice = extractField(response, "ADVICE:");
        if (advice == null) {
            advice = extractField(response, "NEXT_STEP:");
        }
        if (advice != null && !advice.trim().isEmpty()) {
            if (hintBuilder.length() > 0) {
                hintBuilder.append(advice.trim());
            } else {
                hintBuilder.append(advice.trim());
            }
        }
        
        if (hintBuilder.length() > 0) {
            correctionHint = hintBuilder.toString();
        }
        
        return new ReflectionStatus(completed, needsCorrection, messageBuilder.toString(), correctionHint);
    }
    
    /**
     * 从响应中提取字段值
     */
    private String extractField(String response, String fieldName) {
        int start = response.indexOf(fieldName);
        if (start == -1) return null;
        
        start += fieldName.length();
        // 跳过可能的空格和冒号
        while (start < response.length() && (response.charAt(start) == ' ' || response.charAt(start) == ':')) {
            start++;
        }
        
        int end = response.indexOf("\n", start);
        if (end == -1) end = response.length();
        
        return response.substring(start, end).trim();
    }

    /**
     * 发送进度通知
     */
    private void notifyProgress(Consumer<String> callback, String message) {
        log.info(message);
        if (callback != null) {
            callback.accept(message);
        }
    }

    /**
     * 截断字符串
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    /**
     * 反思状态
     */
    @Getter
    public static class ReflectionStatus {
        private final boolean completed;
        private final boolean needsCorrection;
        private final String message;
        private final String correctionHint;

        public ReflectionStatus(boolean completed, boolean needsCorrection, String message, String correctionHint) {
            this.completed = completed;
            this.needsCorrection = needsCorrection;
            this.message = message;
            this.correctionHint = correctionHint;
        }
        
        public boolean needsCorrection() {
            return needsCorrection;
        }
    }

    /**
     * 反思执行结果
     */
    @Getter
    public static class ReflectionResult {
        private String task;
        private boolean success;
        private String message;
        private int iterations;
        private List<String> actionHistory;

        public void setTask(String task) { this.task = task; }
        public void setSuccess(boolean success) { this.success = success; }
        public void setMessage(String message) { this.message = message; }
        public void setIterations(int iterations) { this.iterations = iterations; }
        public void setActionHistory(List<String> actionHistory) { this.actionHistory = actionHistory; }

        @Override
        public String toString() {
            return String.format("ReflectionResult{task='%s', success=%s, iterations=%d, message='%s'}", 
                task, success, iterations, message);
        }
    }
}

