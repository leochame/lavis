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
    @Tool("Call only when clear visual evidence of complete task achievement can be seen in screenshot This call will end current task loop")
    public String completeMilestone(
            @dev.langchain4j.agent.tool.P("Must include 1.success evidence seen in screenshot 2.specific manifestation of completion state") String summary) {
        log.info("✅ 反思结果: 里程碑完成 - {}", summary);
        return "Milestone marked as completed " + summary;
    }
}