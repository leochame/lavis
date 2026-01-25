package com.lavis.cognitive.model;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务计划 - Planner 生成的完整执行计划
 * 
 * 包含：
 * - 原始用户目标
 * - 拆解后的步骤列表
 * - 执行进度跟踪
 */
@Data
@Slf4j
public class TaskPlan {
    
    /**
     * 计划 ID
     */
    private final String planId;
    
    /**
     * 原始用户目标
     */
    private final String userGoal;
    
    /**
     * 计划创建时间
     */
    private final Instant createdAt;
    
    /**
     * 步骤列表
     */
    private final List<PlanStep> steps;
    
    /**
     * 当前执行到的步骤索引 (0-based)
     */
    private int currentStepIndex = 0;
    
    /**
     * 计划状态
     */
    private PlanStatus status = PlanStatus.CREATED;
    
    /**
     * 完成时间
     */
    private Instant completedAt;
    
    /**
     * 失败原因 (如果失败)
     */
    private String failureReason;
    
    public TaskPlan(String userGoal) {
        this.planId = UUID.randomUUID().toString().substring(0, 8);
        this.userGoal = userGoal;
        this.createdAt = Instant.now();
        this.steps = new ArrayList<>();
    }
    
    /**
     * 添加步骤
     */
    public void addStep(PlanStep step) {
        step.setId(steps.size() + 1);
        steps.add(step);
    }
    
    /**
     * 添加多个步骤
     */
    public void addSteps(List<PlanStep> newSteps) {
        for (PlanStep step : newSteps) {
            addStep(step);
        }
    }
    
    /**
     * 获取当前步骤
     */
    public Optional<PlanStep> getCurrentStep() {
        if (currentStepIndex >= 0 && currentStepIndex < steps.size()) {
            return Optional.of(steps.get(currentStepIndex));
        }
        return Optional.empty();
    }
    
    /**
     * 移动到下一步
     * @return 是否还有下一步
     */
    public boolean moveToNextStep() {
        if (currentStepIndex < steps.size() - 1) {
            currentStepIndex++;
            return true;
        }
        return false;
    }
    
    /**
     * 获取进度百分比
     */
    public int getProgressPercent() {
        if (steps.isEmpty()) {
            return 0;
        }
        long completed = steps.stream()
                .filter(s -> s.getStatus() == PlanStep.StepStatus.SUCCESS)
                .count();
        return (int) (completed * 100 / steps.size());
    }
    
    /**
     * 检查是否所有步骤都已完成
     */
    public boolean isCompleted() {
        return steps.stream().allMatch(
                s -> s.getStatus() == PlanStep.StepStatus.SUCCESS 
                  || s.getStatus() == PlanStep.StepStatus.SKIPPED);
    }
    
    /**
     * 检查是否有失败的步骤
     */
    public boolean hasFailed() {
        return steps.stream().anyMatch(s -> s.getStatus() == PlanStep.StepStatus.FAILED);
    }
    
    /**
     * 标记计划开始执行
     */
    public void markStarted() {
        this.status = PlanStatus.EXECUTING;
        log.info("📋 开始执行计划 [{}]: {} ({} 个步骤)", planId, userGoal, steps.size());
    }
    
    /**
     * 标记计划完成
     */
    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.completedAt = Instant.now();
        log.info("✅ 计划完成 [{}]: {} (耗时 {}ms)", 
                planId, userGoal, 
                java.time.Duration.between(createdAt, completedAt).toMillis());
    }
    
    /**
     * 标记计划失败
     */
    public void markFailed(String reason) {
        this.status = PlanStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
        log.error("❌ 计划失败 [{}]: {} - 原因: {}", planId, userGoal, reason);
    }
    
    /**
     * 生成计划摘要 (供 LLM 参考)
     */
    public String generateSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 📋 执行计划\n");
        sb.append("目标: ").append(userGoal).append("\n");
        sb.append("进度: ").append(getProgressPercent()).append("%\n\n");
        
        sb.append("### 步骤列表:\n");
        for (PlanStep step : steps) {
            String statusIcon = switch (step.getStatus()) {
                case SUCCESS -> "✅";
                case FAILED -> "❌";
                case IN_PROGRESS -> "🔄";
                case SKIPPED -> "⏭️";
                default -> "⬜";
            };
            
            String marker = (step.getId() == currentStepIndex + 1) ? "👉 " : "   ";
            sb.append(String.format("%s%s %d. %s\n", 
                    marker, statusIcon, step.getId(), step.getDescription()));
        }
        
        return sb.toString();
    }
    
    /**
     * 计划状态枚举
     */
    public enum PlanStatus {
        CREATED,        // 已创建
        EXECUTING,      // 执行中
        COMPLETED,      // 已完成
        FAILED,         // 失败
        CANCELLED       // 已取消
    }
}

