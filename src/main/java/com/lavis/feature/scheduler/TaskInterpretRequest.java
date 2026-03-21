package com.lavis.feature.scheduler;

import lombok.Data;

@Data
public class TaskInterpretRequest {
    private String text;
    private TaskInterpretResult.TaskDraft draft;
    /**
     * 短期记忆 key（建议由前端按 Task 流程生成并复用）
     */
    private String memoryKey;
    /**
     * true 时，先清空该 memoryKey 对应的短期记忆，再处理本次输入
     */
    private Boolean clearMemory;
}
