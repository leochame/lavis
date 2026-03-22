package com.lavis.agent.loop;

import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 任务调度器 (Task Orchestrator)
 *
 * v1 版本承载完整 ReAct 决策循环（Perceive → Decide → Execute），
 * 但在统一到 `AgentService` + AgentTools 之后，该类只保留：
 * - 调度器状态枚举（用于 /status 等接口展示）
 * - 中断控制（用于 /stop 触发紧急中止）
 *
 * 原先的 ReAct 相关实现（DecisionBundle / ReactTaskContext / LocalExecutor 等）
 * 已从运行路径上移除，仅作为停用代码保留在 `com.lavis.agent.react` 包中。
 */
@Slf4j
@Service
public class TaskOrchestrator {

    // 调度器状态（用于 /status 展示）
    private OrchestratorState state = OrchestratorState.IDLE;

    // 中断标记（用于 /stop 控制）
    private volatile boolean interrupted = false;

    public TaskOrchestrator() {
        // 无需依赖任何外部组件，保持 Spring 兼容的默认构造函数
    }

    /**
     * initialize（保持向后兼容）
     */
    public void initialize(ChatLanguageModel defaultModel) {
        log.info("✅ TaskOrchestrator 初始化完成（ReAct 调度已停用，仅保留状态/中断管理）");
        this.state = OrchestratorState.IDLE;
    }


    /**
     * 外部中断
     */
    public void interrupt() {
        interrupted = true;
        state = OrchestratorState.FAILED;
        log.warn("🛑 TaskOrchestrator 收到中断信号");
    }

    /**
     * 是否处于中断状态
     */
    public boolean isInterrupted() {
        return interrupted;
    }

    /**
     * 清除中断状态
     */
    private void clearInterruptFlag() {
        interrupted = false;
    }

    /**
     * 获取当前状态
     */
    public OrchestratorState getState() {
        return state;
    }

    /**
     * 重置调度器
     */
    public void reset() {
        state = OrchestratorState.IDLE;
        clearInterruptFlag();
        log.info("🔄 调度器已重置");
    }

    /**
     * 调度器状态枚举
     */
    public enum OrchestratorState {
        IDLE,       // 空闲
        EXECUTING,  // 执行中
        COMPLETED,  // 完成
        FAILED      // 失败
    }

    /**
     * 调度器执行结果
     */
    @Data
    public static class OrchestratorResult {
        private final boolean success;
        private final boolean partial;
        private final String message;

        private OrchestratorResult(boolean success, boolean partial, String message) {
            this.success = success;
            this.partial = partial;
            this.message = message;
        }

        public static OrchestratorResult success(String message) {
            return new OrchestratorResult(true, false, message);
        }

        public static OrchestratorResult partial(String message) {
            return new OrchestratorResult(false, true, message);
        }

        public static OrchestratorResult failed(String message) {
            return new OrchestratorResult(false, false, message);
        }

        @Override
        public String toString() {
            String icon = success ? "✅" : (partial ? "⚠️" : "❌");
            return icon + " " + message;
        }
    }
}
