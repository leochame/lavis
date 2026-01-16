package com.lavis.cognitive;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 任务执行上下文 - 解决 Agent "失忆"问题
 * 
 * 核心功能：
 * 1. 记录操作历史轨迹，避免重复执行相同操作
 * 2. 跟踪执行状态和偏差，支持反思修正
 * 3. 检测死循环和无效操作堆积
 * 4. 提供上下文摘要供 LLM 参考
 */
@Slf4j
public class TaskContext {
    
    // 任务基本信息
    private final String taskId;
    private final String taskDescription;
    private final Instant startTime;
    
    // 操作历史记录（最近 N 条）
    private static final int MAX_HISTORY_SIZE = 20;
    private final Deque<ActionRecord> actionHistory = new ConcurrentLinkedDeque<>();
    
    // 重复操作检测
    private static final int REPEAT_THRESHOLD = 3;  // 连续重复 N 次视为死循环
    private final Map<String, Integer> recentActionCounts = new LinkedHashMap<>();
    
    // 执行统计
    private int totalActions = 0;
    private int successfulActions = 0;
    private int failedActions = 0;
    private int repeatedActions = 0;
    
    // 当前状态
    private TaskState state = TaskState.RUNNING;
    private String lastError = null;
    
    public TaskContext(String taskDescription) {
        this.taskId = UUID.randomUUID().toString().substring(0, 8);
        this.taskDescription = taskDescription;
        this.startTime = Instant.now();
        log.info("📋 创建任务上下文 [{}]: {}", taskId, taskDescription);
    }
    
    /**
     * 记录一次操作及其结果
     */
    public ActionResult recordAction(String actionName, Map<String, Object> params, 
                                     boolean success, String result, ExecutionDetails details) {
        totalActions++;
        
        // 创建操作记录
        ActionRecord record = new ActionRecord();
        record.setTimestamp(Instant.now());
        record.setActionName(actionName);
        record.setParams(params);
        record.setSuccess(success);
        record.setResult(result);
        record.setDetails(details);
        
        // 检测重复操作
        String actionSignature = generateActionSignature(actionName, params);
        int repeatCount = recentActionCounts.merge(actionSignature, 1, Integer::sum);
        record.setRepeatCount(repeatCount);
        
        if (repeatCount >= REPEAT_THRESHOLD) {
            repeatedActions++;
            record.setWarning(String.format("⚠️ 检测到重复操作！相同操作已执行 %d 次", repeatCount));
            log.warn("🔄 死循环警告 [{}]: {} 已重复 {} 次", taskId, actionSignature, repeatCount);
        }
        
        // 更新统计
        if (success) {
            successfulActions++;
        } else {
            failedActions++;
            lastError = result;
        }
        
        // 添加到历史
        actionHistory.addLast(record);
        if (actionHistory.size() > MAX_HISTORY_SIZE) {
            actionHistory.removeFirst();
        }
        
        // 清理旧的操作计数
        cleanupOldActionCounts();
        
        log.debug("📝 记录操作 [{}]: {} -> {}", taskId, actionName, success ? "成功" : "失败");
        
        // 返回带有上下文信息的结果
        return new ActionResult(record, generateContextSummary());
    }
    
    /**
     * 生成操作签名，用于检测重复
     */
    private String generateActionSignature(String actionName, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(actionName);
        if (params != null && !params.isEmpty()) {
            // 对于坐标操作，允许一定容差（20像素内视为相同位置）
            if (params.containsKey("x") && params.containsKey("y")) {
                int x = ((Number) params.get("x")).intValue();
                int y = ((Number) params.get("y")).intValue();
                // 量化到 20 像素网格
                sb.append("@").append(x / 20 * 20).append(",").append(y / 20 * 20);
            } else {
                params.forEach((k, v) -> sb.append(":").append(k).append("=").append(v));
            }
        }
        return sb.toString();
    }
    
    /**
     * 清理过期的操作计数
     */
    private void cleanupOldActionCounts() {
        if (recentActionCounts.size() > 10) {
            Iterator<String> it = recentActionCounts.keySet().iterator();
            while (recentActionCounts.size() > 5 && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }
    
    /**
     * 生成上下文摘要，供 LLM 参考
     */
    public String generateContextSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## 📊 执行上下文摘要\n");
        sb.append(String.format("- 任务: %s\n", taskDescription));
        sb.append(String.format("- 已执行操作: %d 次 (成功: %d, 失败: %d)\n", 
                totalActions, successfulActions, failedActions));
        
        if (repeatedActions > 0) {
            sb.append(String.format("- ⚠️ 检测到 %d 次重复操作，可能陷入死循环！\n", repeatedActions));
        }
        
        // 最近 5 条操作历史
        sb.append("\n### 最近操作历史:\n");
        List<ActionRecord> recent = new ArrayList<>(actionHistory);
        int start = Math.max(0, recent.size() - 5);
        for (int i = start; i < recent.size(); i++) {
            ActionRecord r = recent.get(i);
            String status = r.isSuccess() ? "✅" : "❌";
            sb.append(String.format("%d. %s %s", i + 1, status, r.getActionName()));
            if (r.getParams() != null && r.getParams().containsKey("x")) {
                sb.append(String.format("(%s,%s)", r.getParams().get("x"), r.getParams().get("y")));
            }
            if (r.getDetails() != null && r.getDetails().getDeviation() != null) {
                sb.append(String.format(" [偏差: %s]", r.getDetails().getDeviation()));
            }
            if (r.getRepeatCount() > 1) {
                sb.append(String.format(" ⚠️重复%d次", r.getRepeatCount()));
            }
            sb.append("\n");
        }
        
        // 如果有重复操作，给出建议
        if (repeatedActions > 0) {
            sb.append("\n### 💡 建议:\n");
            sb.append("- 相同操作多次执行未见效果，请尝试不同策略\n");
            sb.append("- 可能原因: 坐标偏差、元素不可点击、需要等待加载\n");
            sb.append("- 建议: 1)调整坐标 2)尝试双击 3)先等待再操作 4)检查元素状态\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 检查是否应该停止执行（死循环保护）
     */
    public boolean shouldStop() {
        // 连续失败过多
        if (failedActions > 5 && failedActions > successfulActions) {
            log.warn("🛑 任务停止: 失败率过高");
            state = TaskState.FAILED;
            return true;
        }
        // 重复操作过多
        if (repeatedActions > 5) {
            log.warn("🛑 任务停止: 死循环检测");
            state = TaskState.STUCK;
            return true;
        }
        return false;
    }
    
    /**
     * 获取最后一次操作的详情
     */
    public Optional<ActionRecord> getLastAction() {
        return Optional.ofNullable(actionHistory.peekLast());
    }
    
    /**
     * 检查最近是否有相同操作
     */
    public boolean hasRecentSameAction(String actionName, int x, int y, int tolerance) {
        return actionHistory.stream()
                .filter(r -> r.getActionName().equals(actionName))
                .filter(r -> r.getParams() != null)
                .filter(r -> r.getParams().containsKey("x") && r.getParams().containsKey("y"))
                .anyMatch(r -> {
                    int rx = ((Number) r.getParams().get("x")).intValue();
                    int ry = ((Number) r.getParams().get("y")).intValue();
                    return Math.abs(rx - x) <= tolerance && Math.abs(ry - y) <= tolerance;
                });
    }
    
    /**
     * 标记任务完成
     */
    public void markCompleted() {
        this.state = TaskState.COMPLETED;
        log.info("✅ 任务完成 [{}]: {} (共 {} 次操作)", taskId, taskDescription, totalActions);
    }
    
    /**
     * 重置重复计数（当界面发生变化时调用）
     */
    public void resetRepeatCounts() {
        recentActionCounts.clear();
        log.debug("🔄 重置重复计数");
    }
    
    // Getters
    public String getTaskId() { return taskId; }
    public String getTaskDescription() { return taskDescription; }
    public TaskState getState() { return state; }
    public int getTotalActions() { return totalActions; }
    public int getRepeatedActions() { return repeatedActions; }
    
    /**
     * 任务状态枚举
     */
    public enum TaskState {
        RUNNING,    // 执行中
        COMPLETED,  // 已完成
        FAILED,     // 失败
        STUCK       // 卡住（死循环）
    }
    
    /**
     * 操作记录
     */
    @Data
    public static class ActionRecord {
        private Instant timestamp;
        private String actionName;
        private Map<String, Object> params;
        private boolean success;
        private String result;
        private ExecutionDetails details;
        private int repeatCount;
        private String warning;
    }
    
    /**
     * 执行详情（包含偏差等信息）
     */
    @Data
    public static class ExecutionDetails {
        private String deviation;      // 坐标偏差
        private boolean targetHit;     // 是否命中目标
        private String uiChange;       // UI 变化描述
        private long executionTimeMs;  // 执行耗时
    }
    
    /**
     * 带上下文的操作结果
     */
    @Data
    public static class ActionResult {
        private final ActionRecord record;
        private final String contextSummary;
        
        public ActionResult(ActionRecord record, String contextSummary) {
            this.record = record;
            this.contextSummary = contextSummary;
        }
        
        /**
         * 生成给 LLM 看的完整反馈
         */
        public String toFeedback() {
            StringBuilder sb = new StringBuilder();
            
            // 操作结果
            sb.append(record.isSuccess() ? "✅ " : "❌ ");
            sb.append(record.getResult());
            
            // 偏差信息
            if (record.getDetails() != null && record.getDetails().getDeviation() != null) {
                sb.append("\n⚠️ 执行偏差: ").append(record.getDetails().getDeviation());
            }
            
            // 警告信息
            if (record.getWarning() != null) {
                sb.append("\n").append(record.getWarning());
            }
            
            // 上下文摘要
            sb.append(contextSummary);
            
            return sb.toString();
        }
    }
}

