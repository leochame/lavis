package com.lavis.cognitive.context;

import com.lavis.cognitive.model.PlanStep;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 宏观上下文 (Global Context) - 长期记忆
 * 
 * 【架构核心】分层上下文体系的顶层
 * 
 * 持有者: TaskOrchestrator 创建和维护
 * 生命周期: 整个任务（User Goal）的生命周期
 * 
 * 职责:
 * 1. 存储用户总目标 (User Goal)
 * 2. 存储已完成里程碑历史 (Milestone History) - "我们已经做完了什么"
 * 3. 存储关键共享变量 (Shared Variables) - 跨步骤共享的信息
 * 4. 存储最近的全局操作摘要 (Short-term Action Summary) - 用于跨步骤衔接
 */
@Data
@Slf4j
public class GlobalContext {
    
    // ========== 基本信息 ==========
    
    /** 上下文唯一标识 */
    private final String contextId;
    
    /** 用户总目标 */
    private final String userGoal;
    
    /** 创建时间 */
    private final Instant createdAt;
    
    // ========== 里程碑历史 (Milestone History) ==========
    
    /** 已完成的里程碑列表 */
    private final List<MilestoneRecord> completedMilestones = new ArrayList<>();
    
    /** 当前正在执行的里程碑 */
    private MilestoneRecord currentMilestone;
    
    // ========== 共享变量 (Shared Variables) ==========
    
    /** 跨步骤共享的变量（如：搜索到的用户名、选择的文件路径等） */
    private final Map<String, Object> sharedVariables = new HashMap<>();
    
    // ========== 短期操作摘要 (Short-term Action Summary) ==========
    
    /** 最近的操作摘要队列（用于跨步骤衔接） */
    private static final int MAX_ACTION_SUMMARY_SIZE = 10;
    private final Deque<ActionSummary> recentActions = new ConcurrentLinkedDeque<>();
    
    // ========== 执行统计 ==========
    
    /** 总步骤数 */
    private int totalSteps = 0;
    
    /** 成功步骤数 */
    private int successfulSteps = 0;
    
    /** 失败步骤数 */
    private int failedSteps = 0;
    
    /** 重试次数 */
    private int totalRetries = 0;
    
    // ========== 状态信息 ==========
    
    /** 当前屏幕状态描述（由 MicroExecutor 更新） */
    private String currentScreenState;
    
    /** 最后一次错误信息 */
    private String lastError;
    
    /** 是否处于恢复模式 */
    private boolean inRecoveryMode = false;
    
    // ========== 构造函数 ==========
    
    public GlobalContext(String userGoal) {
        this.contextId = UUID.randomUUID().toString().substring(0, 8);
        this.userGoal = userGoal;
        this.createdAt = Instant.now();
        log.info("🌍 创建 GlobalContext [{}]: {}", contextId, userGoal);
    }
    
    // ========== 里程碑管理 ==========
    
    /**
     * 开始新的里程碑
     */
    public void startMilestone(PlanStep step) {
        this.currentMilestone = new MilestoneRecord(
                step.getId(),
                step.getDescription()
        );
        this.totalSteps++;
        log.info("🎯 开始里程碑 {}: {}", step.getId(), step.getDescription());
    }
    
    /**
     * 完成当前里程碑
     */
    public void completeMilestone(String result, boolean success) {
        if (currentMilestone != null) {
            currentMilestone.setEndTime(Instant.now());
            currentMilestone.setSuccess(success);
            currentMilestone.setResult(result);
            
            completedMilestones.add(currentMilestone);
            
            if (success) {
                successfulSteps++;
                log.info("✅ 里程碑 {} 完成: {}", currentMilestone.getStepId(), result);
            } else {
                failedSteps++;
                lastError = result;
                log.warn("❌ 里程碑 {} 失败: {}", currentMilestone.getStepId(), result);
            }
            
            currentMilestone = null;
        }
    }
    
    /**
     * 获取已完成里程碑的摘要
     */
    public String getCompletedMilestonesSummary() {
        if (completedMilestones.isEmpty()) {
            return "No completed milestones yet";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("### Completed Milestones\n");
        for (MilestoneRecord milestone : completedMilestones) {
            sb.append(String.format("%d. %s\n", milestone.getStepId(), milestone.getDescription()));
            if (milestone.getResult() != null) {
                sb.append(String.format("   Result %s\n", truncate(milestone.getResult(), 100)));
            }
        }
        return sb.toString();
    }
    
    // ========== 共享变量管理 ==========
    
    /**
     * 设置共享变量
     */
    public void setVariable(String key, Object value) {
        sharedVariables.put(key, value);
        log.debug("📦 设置共享变量: {} = {}", key, value);
    }
    
    /**
     * 获取共享变量
     */
    @SuppressWarnings("unchecked")
    public <T> T getVariable(String key, Class<T> type) {
        return (T) sharedVariables.get(key);
    }
    
    /**
     * 获取共享变量（带默认值）
     */
    @SuppressWarnings("unchecked")
    public <T> T getVariable(String key, T defaultValue) {
        Object value = sharedVariables.get(key);
        return value != null ? (T) value : defaultValue;
    }
    
    // ========== 操作摘要管理 ==========
    
    /**
     * 添加操作摘要
     */
    public void addActionSummary(String action, String result, boolean success) {
        ActionSummary summary = new ActionSummary(action, result, success);
        recentActions.addLast(summary);
        
        // 保持队列大小
        while (recentActions.size() > MAX_ACTION_SUMMARY_SIZE) {
            recentActions.removeFirst();
        }
    }
    
    /**
     * 获取最近操作的摘要文本
     */
    public String getRecentActionsSummary() {
        if (recentActions.isEmpty()) {
            return "No recent actions";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("### Recent Actions\n");
        int idx = 1;
        for (ActionSummary action : recentActions) {
            sb.append(String.format("%d. %s -> %s\n", 
                    idx++, action.getAction(), truncate(action.getResult(), 50)));
        }
        return sb.toString();
    }
    
    // ========== 上下文注入（给 MicroExecutor 使用） ==========
    
    /**
     * 生成供 MicroExecutor 使用的"前情提要"
     * 
     * 这个方法返回的内容将被注入到 MicroExecutor 的 System Prompt 中，
     * 让 Bot 知道"我在哪"、"我刚才做了什么"
     */
    public String generateContextInjection() {
        StringBuilder sb = new StringBuilder();
        
        // 1. 总目标
        sb.append("## Overall Goal\n");
        sb.append(userGoal).append("\n\n");
        
        // 2. 当前进度
        sb.append("## Current Progress\n");
        sb.append(String.format("Completed %d/%d milestones success %d failed %d\n\n", 
                completedMilestones.size(), totalSteps, successfulSteps, failedSteps));
        
        // 3. 已完成的里程碑（简要）
        if (!completedMilestones.isEmpty()) {
            sb.append("### Completed\n");
            // 只显示最近 3 个
            int start = Math.max(0, completedMilestones.size() - 3);
            for (int i = start; i < completedMilestones.size(); i++) {
                MilestoneRecord m = completedMilestones.get(i);
                sb.append(String.format("- %s step %d: %s\n", 
                        m.isSuccess() ? "✅" : "❌", m.getStepId(), m.getDescription()));
            }
            sb.append("\n");
        }
        
        // 4. 当前里程碑
        if (currentMilestone != null) {
            sb.append("### Current Task\n");
            sb.append(String.format("Step %d %s\n", 
                    currentMilestone.getStepId(), currentMilestone.getDescription()));
            sb.append("\n");
        }
        
        // 5. 最近操作（如果有）
        if (!recentActions.isEmpty()) {
            sb.append("### Recent Actions\n");
            // 只显示最近 3 条
            List<ActionSummary> recent = new ArrayList<>(recentActions);
            int start = Math.max(0, recent.size() - 3);
            for (int i = start; i < recent.size(); i++) {
                ActionSummary a = recent.get(i);
                sb.append(String.format("- %s %s\n", a.isSuccess() ? "✅" : "❌", a.getAction()));
            }
            sb.append("\n");
        }
        
        // 6. 恢复模式提示
        if (inRecoveryMode && lastError != null) {
            sb.append("### Note\n");
            sb.append("Last step execution failed, reason ").append(truncate(lastError, 100)).append("\n");
            sb.append("Please try different strategies to complete current task\n\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 更新执行结果到 GlobalContext（由 MicroExecutor 调用）
     */
    public void updateFromExecution(String screenState, String actionSummary, boolean success) {
        this.currentScreenState = screenState;
        if (actionSummary != null) {
            addActionSummary(actionSummary, screenState, success);
        }
        if (!success) {
            this.inRecoveryMode = true;
        } else {
            this.inRecoveryMode = false;
        }
    }
    
    // ========== 辅助方法 ==========
    
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
    
    /**
     * 获取执行摘要
     */
    public String getExecutionSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Execution Summary\n");
        sb.append(String.format("- Context ID: %s\n", contextId));
        sb.append(String.format("- Goal: %s\n", userGoal));
        sb.append(String.format("- Total Steps: %d\n", totalSteps));
        sb.append(String.format("- Success: %d Failed: %d\n", successfulSteps, failedSteps));
        sb.append(String.format("- Total Retries: %d\n", totalRetries));
        if (currentScreenState != null) {
            sb.append(String.format("- Current State: %s\n", truncate(currentScreenState, 80)));
        }
        return sb.toString();
    }
    
    // ========== 内部类 ==========
    
    /**
     * 里程碑记录
     */
    @Data
    public static class MilestoneRecord {
        private final int stepId;
        private final String description;
        private final Instant startTime;
        private Instant endTime;
        private boolean success;
        private String result;
        
        public MilestoneRecord(int stepId, String description) {
            this.stepId = stepId;
            this.description = description;
            this.startTime = Instant.now();
        }
        
        public long getDurationMs() {
            if (endTime == null) return 0;
            return java.time.Duration.between(startTime, endTime).toMillis();
        }
    }
    
    /**
     * 操作摘要
     */
    @Data
    public static class ActionSummary {
        private final String action;
        private final String result;
        private final boolean success;
        private final Instant timestamp;
        
        public ActionSummary(String action, String result, boolean success) {
            this.action = action;
            this.result = result;
            this.success = success;
            this.timestamp = Instant.now();
        }
    }
}

