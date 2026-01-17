package com.lavis.cognitive.executor;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 反思阶段专用工具集
 * * 简化后：仅保留任务完成的信号工具。
 * 只有当 LLM 确信任务已完成时，才会调用此工具。
 */
@Slf4j
@Component
public class ReflectionTools {

    /**
     * 里程碑完成工具
     * * 调用此工具即代表流程结束（Success）。
     */
    @Tool("当且仅当截图中能看到任务【完全达成】的明确视觉证据时调用。此调用将结束当前任务循环。")
    public String completeMilestone(
            @dev.langchain4j.agent.tool.P("必须包含：1.截图中看到的成功证据 2.完成状态的具体表现。") String summary) {
        log.info("✅ 反思结果: 里程碑完成 - {}", summary);
        return "里程碑已标记为完成: " + summary;
    }
}