package com.lavis.cognitive.executor;

import dev.langchain4j.agent.tool.Tool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 反思阶段专用工具集
 * 用于强制模型通过结构化的方式输出决策，而非依赖不稳定的文本解析
 */
@Slf4j
@Component
public class ReflectionTools {

    @Tool("当且仅当当前里程碑任务的所有目标都已【完全达成】时调用此工具。不要在任务仅完成一半时调用。")
    public ReflectionResult completeMilestone(
            @dev.langchain4j.agent.tool.P("完成情况的详细摘要，包括已验证的关键信息") String summary) {
        return new ReflectionResult(Decision.SUCCESS, summary, null);
    }

    @Tool("当任务尚未完成、需要修正错误、或者需要继续执行下一步时调用此工具。")
    public ReflectionResult reflectSituation(
            @dev.langchain4j.agent.tool.P("对当前屏幕状态的详细视觉分析") String observation,
            @dev.langchain4j.agent.tool.P("决策状态：CONTINUE(继续正常执行), RETRY(遇到问题需修正), FAIL(无法解决)") Status status,
            @dev.langchain4j.agent.tool.P("下一步的行动计划或修正建议") String nextStep) {
        return new ReflectionResult(
                status == Status.FAIL ? Decision.FAIL : (status == Status.RETRY ? Decision.RETRY : Decision.CONTINUE), 
                observation, 
                nextStep
        );
    }

    // --- 辅助数据结构 ---

    public enum Status {
        CONTINUE, RETRY, FAIL
    }

    public enum Decision {
        SUCCESS, CONTINUE, RETRY, FAIL
    }

    @Data
    public static class ReflectionResult {
        private final Decision decision;
        private final String message;
        private final String suggestion;
        
        public ReflectionResult(Decision decision, String message, String suggestion) {
            this.decision = decision;
            this.message = message;
            this.suggestion = suggestion;
        }
    }
}