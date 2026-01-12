package com.lavis.cognitive;

import com.lavis.perception.ScreenCapturer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * M2 思考模块 - 反思循环
 * 类似于 Flowith 的反思机制：执行动作 -> 再次截图 -> 询问 "任务完成了吗？" -> 修正或结束
 * 实现 Action-Observation-Correction (行动-观察-修正) 闭环
 */
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
//                    .modelName("gemini-2.0-flash")
//                    .temperature(0.3) // 低温度保证稳定性
//                    .build();
            this.reflectionModel = OpenAiChatModel.builder()
                    .baseUrl("https://docs.newapi.pro/v1/chat/completions")
                    .apiKey(apiKey)
                    .modelName("gemini-2.0-flash")
                    .temperature(0.3) // 低温度保证稳定性
                    .build();

            log.info("ReflectionLoop 初始化完成");
        } catch (Exception e) {
            log.error("ReflectionLoop 初始化失败", e);
        }
    }

    /**
     * 执行带反思的任务
     * @param task 任务描述
     * @param progressCallback 进度回调
     * @return 执行结果
     */
    public ReflectionResult executeWithReflection(String task, Consumer<String> progressCallback) {
        log.info("开始反思循环执行任务: {}", task);
        
        List<String> actionHistory = new ArrayList<>();
        ReflectionResult result = new ReflectionResult();
        result.setTask(task);
        
        // 初始状态
        notifyProgress(progressCallback, "📋 开始执行任务: " + task);
        
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            log.info("反思循环 - 第 {} 次迭代", iteration + 1);
            notifyProgress(progressCallback, String.format("🔄 迭代 %d/%d", iteration + 1, maxIterations));
            
            try {
                // 1. 执行动作
                String action = planAndExecute(task, actionHistory);
                actionHistory.add(action);
                notifyProgress(progressCallback, "✅ 执行: " + truncate(action, 100));
                
                // 2. 等待 UI 响应
                Thread.sleep(reflectionDelayMs);
                
                // 3. 观察结果
                ReflectionStatus status = checkCompletion(task, actionHistory);
                notifyProgress(progressCallback, "🔍 状态: " + status.getMessage());
                
                // 4. 判断是否完成
                if (status.isCompleted()) {
                    result.setSuccess(true);
                    result.setMessage("任务完成: " + status.getMessage());
                    result.setIterations(iteration + 1);
                    result.setActionHistory(actionHistory);
                    notifyProgress(progressCallback, "🎉 任务完成!");
                    return result;
                }
                
                // 5. 如果失败次数过多，尝试修正
                if (status.needsCorrection()) {
                    notifyProgress(progressCallback, "⚠️ 需要修正: " + status.getCorrectionHint());
                    // 将修正建议加入历史，以便下次迭代参考
                    actionHistory.add("[修正建议] " + status.getCorrectionHint());
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.setSuccess(false);
                result.setMessage("任务被中断");
                return result;
            } catch (Exception e) {
                log.error("反思循环执行异常", e);
                actionHistory.add("[错误] " + e.getMessage());
                notifyProgress(progressCallback, "❌ 错误: " + e.getMessage());
            }
        }
        
        // 达到最大迭代次数
        result.setSuccess(false);
        result.setMessage("达到最大迭代次数 (" + maxIterations + ")，任务可能未完成");
        result.setIterations(maxIterations);
        result.setActionHistory(actionHistory);
        notifyProgress(progressCallback, "⏱️ 达到最大迭代次数");
        
        return result;
    }

    /**
     * 规划并执行单步动作
     */
    private String planAndExecute(String task, List<String> history) {
        StringBuilder context = new StringBuilder();
        context.append("任务: ").append(task).append("\n\n");
        
        if (!history.isEmpty()) {
            context.append("已执行的操作:\n");
            for (int i = 0; i < history.size(); i++) {
                context.append(String.format("%d. %s\n", i + 1, history.get(i)));
            }
            context.append("\n请继续执行下一步操作。\n");
        } else {
            context.append("这是第一步操作，请分析屏幕并开始执行。\n");
        }
        
        // 调用 Agent 执行
        String response = agentService.chatWithScreenshot(context.toString());
        return response;
    }

    /**
     * 检查任务是否完成
     */
    private ReflectionStatus checkCompletion(String task, List<String> history) {
        if (reflectionModel == null) {
            return new ReflectionStatus(false, false, "反思模型未初始化", null);
        }

        try {
            // 获取当前屏幕截图
            String base64Image = screenCapturer.captureScreenAsBase64();
            
            // 构建反思提示
            String reflectionPrompt = String.format("""
                我正在执行一个自动化任务，请帮我判断任务是否已完成。
                
                任务描述: %s
                
                已执行的操作:
                %s
                
                请观察当前屏幕截图，判断:
                1. 任务是否已经完成？(回答 YES 或 NO)
                2. 如果没完成，还需要做什么？给出简短的下一步建议。
                3. 有没有出现错误或异常情况？
                
                请按以下格式回答:
                COMPLETED: YES/NO
                NEXT_STEP: (如果未完成，给出下一步建议)
                ERROR: (如果有错误，描述错误)
                """, 
                task,
                String.join("\n", history)
            );
            
            // 发送多模态请求
            UserMessage userMessage = UserMessage.from(
                TextContent.from(reflectionPrompt),
                ImageContent.from(base64Image, "image/png")
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
     * 解析反思响应
     */
    private ReflectionStatus parseReflectionResponse(String response) {
        boolean completed = response.toUpperCase().contains("COMPLETED: YES") || 
                           response.toUpperCase().contains("COMPLETED:YES");
        
        boolean needsCorrection = response.toUpperCase().contains("ERROR:") && 
                                  !response.toUpperCase().contains("ERROR: NONE") &&
                                  !response.toUpperCase().contains("ERROR: NO");
        
        String message = completed ? "任务已完成" : "任务进行中";
        String correctionHint = null;
        
        // 提取下一步建议
        if (response.contains("NEXT_STEP:")) {
            int start = response.indexOf("NEXT_STEP:") + 10;
            int end = response.indexOf("\n", start);
            if (end == -1) end = response.length();
            correctionHint = response.substring(start, end).trim();
        }
        
        return new ReflectionStatus(completed, needsCorrection, message, correctionHint);
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

