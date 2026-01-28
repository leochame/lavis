package com.lavis.scheduler;

import com.lavis.action.AppleScriptExecutor;
import com.lavis.cognitive.AgentService;
import com.lavis.entity.ScheduledTaskEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TaskExecutor {

    private static final Logger logger = LoggerFactory.getLogger(TaskExecutor.class);

    private final AgentService agentService;
    private final AppleScriptExecutor appleScriptExecutor;

    public TaskExecutor(AgentService agentService, AppleScriptExecutor appleScriptExecutor) {
        this.agentService = agentService;
        this.appleScriptExecutor = appleScriptExecutor;
    }

    public ExecutionResult execute(ScheduledTaskEntity task) {
        String command = task.getCommand();

        if (command.startsWith("agent:")) {
            return executeAgentTask(command.substring(6).trim());
        } else if (command.startsWith("shell:")) {
            return executeShellTask(command.substring(6).trim());
        } else {
            return executeShellTask(command);
        }
    }

    private ExecutionResult executeAgentTask(String goal) {
        long startTime = System.currentTimeMillis();
        try {
            logger.info("Executing agent task: {}", goal);
            String response = agentService.chatWithScreenshot(goal);
            long duration = System.currentTimeMillis() - startTime;
            logger.info("Agent task completed in {}ms", duration);
            return new ExecutionResult(true, response, null, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Agent task failed: {}", e.getMessage(), e);
            return new ExecutionResult(false, null, e.getMessage(), duration);
        }
    }

    private ExecutionResult executeShellTask(String command) {
        long startTime = System.currentTimeMillis();
        try {
            logger.info("Executing shell task: {}", command);
            AppleScriptExecutor.ExecutionResult result = appleScriptExecutor.executeShell(command);
            long duration = System.currentTimeMillis() - startTime;

            boolean success = result.exitCode() == 0;
            String output = result.output();
            String error = success ? null : output;

            if (success) {
                logger.info("Shell task completed in {}ms", duration);
            } else {
                logger.warn("Shell task failed with exit code {}: {}", result.exitCode(), error);
            }

            return new ExecutionResult(success, output, error, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Shell task execution error: {}", e.getMessage(), e);
            return new ExecutionResult(false, null, e.getMessage(), duration);
        }
    }

    @Data
    @AllArgsConstructor
    public static class ExecutionResult {
        private boolean success;
        private String output;
        private String error;
        private long durationMs;
    }
}
