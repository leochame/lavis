package com.lavis.cognitive.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 计划步骤 - Planner 生成的单个任务步骤（里程碑级）
 * 
 * 【架构升级】双层大脑架构的契约：
 * - Planner（战略层）：生成里程碑级 PlanStep 列表，只关心"做什么"
 * - Executor（战术层）：逐个执行每个 PlanStep，自主完成 OODA 闭环
 * - 执行结果包含验尸报告（PostMortem），供 Planner 决策
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanStep {
    
    /**
     * 步骤 ID (从 1 开始)
     */
    private int id;
    
    /**
     * 步骤描述 - 里程碑级的任务描述
     * 例如: "导航到个人主页"、"完成发布表单填写并提交"
     * 【注意】不应包含具体坐标或原子动作
     */
    private String description;
    
    /**
     * 步骤类型 - 里程碑类型（高层语义指令）
     */
    private StepType type;
    
    /**
     * 预期操作数量 (可选) - 帮助判断是否陷入死循环
     */
    private Integer expectedActions;
    
    /**
     * 超时时间 (秒)
     */
    @Builder.Default
    private int timeoutSeconds = 60;
    
    /**
     * 最大重试次数 (Executor 内部重试)
     */
    @Builder.Default
    private int maxRetries = 8;
    
    /**
     * 步骤状态
     */
    @Builder.Default
    private StepStatus status = StepStatus.PENDING;
    
    /**
     * 开始执行时间
     */
    private Instant startTime;
    
    /**
     * 结束执行时间
     */
    private Instant endTime;
    
    /**
     * 执行结果 (成功/失败的简要说明)
     */
    private String resultSummary;
    
    /**
     * 【新增】验尸报告 (Post-mortem) - 失败时的详细诊断信息
     */
    private PostMortem postMortem;
    
    /**
     * 步骤类型枚举 - 里程碑级（高层语义指令）
     */
    public enum StepType {
        // === 高层语义指令（里程碑级）===
        LAUNCH_APP,         // 启动并确保应用就绪
        NAVIGATE_TO,        // 导航至特定功能区（如"个人主页"、"设置页"）
        EXECUTE_WORKFLOW,   // 执行完整业务流程（如"填写表单并提交"）
        VERIFY_STATE,       // 验证当前状态（如"确认已登录"）
        
        // === 兼容旧类型（降级为里程碑）===
        CLICK,          // 单次点击操作（简单里程碑）
        TYPE,           // 输入文本（简单里程碑）
        DRAG,           // 拖拽操作（简单里程碑）
        SCROLL,         // 滚动操作（简单里程碑）
        WAIT,           // 等待/验证
        KEYBOARD,       // 键盘快捷键
        OPEN_APP,       // 打开应用 (同 LAUNCH_APP)
        NAVIGATE,       // 导航 (同 NAVIGATE_TO)
        SEARCH,         // 搜索操作
        COMPLEX,        // 复杂操作（需要多步）
        UNKNOWN         // 未知类型
    }
    
    /**
     * 【新增】验尸报告 - 失败时的详细诊断信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PostMortem {
        /** 最后一次看到的屏幕状态描述 */
        private String lastScreenState;
        /** 尝试过的策略列表 */
        private java.util.List<String> attemptedStrategies;
        /** 失败的具体原因 */
        private FailureReason failureReason;
        /** 详细错误信息 */
        private String errorDetail;
        /** 建议的恢复策略 */
        private String suggestedRecovery;
        
        public enum FailureReason {
            ELEMENT_NOT_FOUND,      // 找不到目标元素
            CLICK_MISSED,           // 点击未命中
            INFINITE_LOOP,          // 陷入死循环
            APP_NOT_RESPONDING,     // 应用无响应
            UNEXPECTED_DIALOG,      // 意外弹窗
            TIMEOUT,                // 超时
            UNKNOWN                 // 未知原因
        }
    }
    
    /**
     * 步骤状态枚举
     */
    public enum StepStatus {
        PENDING,        // 待执行
        IN_PROGRESS,    // 执行中
        SUCCESS,        // 成功
        FAILED,         // 失败
        SKIPPED         // 跳过
    }
    
    /**
     * 标记开始执行
     */
    public void markStarted() {
        this.status = StepStatus.IN_PROGRESS;
        this.startTime = Instant.now();
    }
    
    /**
     * 标记执行成功
     */
    public void markSuccess(String summary) {
        this.status = StepStatus.SUCCESS;
        this.endTime = Instant.now();
        this.resultSummary = summary;
    }
    
    /**
     * 标记执行失败
     */
    public void markFailed(String reason) {
        this.status = StepStatus.FAILED;
        this.endTime = Instant.now();
        this.resultSummary = reason;
    }
    
    /**
     * 标记执行失败（带验尸报告）
     */
    public void markFailed(String reason, PostMortem postMortem) {
        this.status = StepStatus.FAILED;
        this.endTime = Instant.now();
        this.resultSummary = reason;
        this.postMortem = postMortem;
    }
    
    /**
     * 获取执行耗时 (毫秒)
     */
    public long getExecutionTimeMs() {
        if (startTime == null || endTime == null) {
            return 0;
        }
        return java.time.Duration.between(startTime, endTime).toMillis();
    }
    
    @Override
    public String toString() {
        return String.format("Step[%d]: %s (%s)", 
                id, description, status);
    }
}

