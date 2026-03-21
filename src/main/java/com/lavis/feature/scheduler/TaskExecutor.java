package com.lavis.feature.scheduler;

import com.lavis.agent.action.AppleScriptExecutor;
import com.lavis.agent.AgentService;
import com.lavis.infra.persistence.entity.ScheduledTaskEntity;
import com.lavis.agent.chat.ChatRequest;
import com.lavis.agent.chat.ChatResponse;
import com.lavis.agent.chat.UnifiedChatService;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TaskExecutor {

    private static final Logger logger = LoggerFactory.getLogger(TaskExecutor.class);
    private static final String PREFIX_REQUEST = "request:";
    private static final String PREFIX_REQUEST_TASK = "request-task:";

    private final AgentService agentService;
    private final AppleScriptExecutor appleScriptExecutor;
    private final UnifiedChatService unifiedChatService;
    private final TaskFileLoader taskFileLoader;

    public TaskExecutor(AgentService agentService,
                        AppleScriptExecutor appleScriptExecutor,
                        UnifiedChatService unifiedChatService,
                        TaskFileLoader taskFileLoader) {
        this.agentService = agentService;
        this.appleScriptExecutor = appleScriptExecutor;
        this.unifiedChatService = unifiedChatService;
        this.taskFileLoader = taskFileLoader;
    }

    public ExecutionResult execute(ScheduledTaskEntity task) {
        if (TaskRules.SOURCE_TYPE_FILE.equalsIgnoreCase(task.getSourceType())) {
            return executeFileBackedTask(task);
        }

        String executionMode = task.getExecutionMode() != null
                ? task.getExecutionMode().trim().toUpperCase()
                : "COMMAND";
        String command = task.getCommand() != null ? task.getCommand().trim() : "";

        // REQUEST 模式：周期模拟用户发起文本请求（走 /chat 同路径）
        if (TaskRules.EXECUTION_MODE_REQUEST.equals(executionMode)) {
            String requestText = resolveRequestText(task);
            return executeUserRequestTask(requestText, Boolean.TRUE.equals(task.getRequestUseOrchestrator()));
        }

        // 向后兼容：命令前缀 request:/request-task: 也可模拟用户请求
        if (command.startsWith(PREFIX_REQUEST_TASK)) {
            return executeUserRequestTask(command.substring(PREFIX_REQUEST_TASK.length()).trim(), true);
        } else if (command.startsWith(PREFIX_REQUEST)) {
            return executeUserRequestTask(command.substring(PREFIX_REQUEST.length()).trim(), false);
        } else if (command.startsWith("agent:")) {
            return executeAgentTask(command.substring(6).trim());
        } else if (command.startsWith("shell:")) {
            return executeShellTask(command.substring(6).trim());
        } else if (command.isBlank()) {
            return new ExecutionResult(false, null, "Task command is empty", 0);
        } else {
            return executeShellTask(command);
        }
    }

    private ExecutionResult executeFileBackedTask(ScheduledTaskEntity task) {
        return taskFileLoader.loadExecutionDefinition(task.getSourcePath())
                .map(definition -> {
                    String mode = definition.executionMode() != null
                            ? definition.executionMode().trim().toUpperCase()
                            : TaskRules.EXECUTION_MODE_REQUEST;
                    String body = definition.body() != null ? definition.body().trim() : "";

                    if (!hasText(body)) {
                        return new ExecutionResult(false, null, ".task body is empty", 0);
                    }

                    if (TaskRules.EXECUTION_MODE_SCRIPT.equals(mode)) {
                        return executeShellTask(body);
                    }
                    return executeUserRequestTask(body, definition.useOrchestrator());
                })
                .orElseGet(() -> new ExecutionResult(false, null,
                        "Failed to load .task execution content: " + task.getSourcePath(), 0));
    }

    private String resolveRequestText(ScheduledTaskEntity task) {
        if (hasText(task.getRequestContent())) {
            return task.getRequestContent().trim();
        }
        String command = task.getCommand() != null ? task.getCommand().trim() : "";
        if (command.startsWith(PREFIX_REQUEST_TASK)) {
            return command.substring(PREFIX_REQUEST_TASK.length()).trim();
        }
        if (command.startsWith(PREFIX_REQUEST)) {
            return command.substring(PREFIX_REQUEST.length()).trim();
        }
        return command;
    }

    private ExecutionResult executeUserRequestTask(String requestText, boolean useOrchestrator) {
        long startTime = System.currentTimeMillis();
        if (!hasText(requestText)) {
            return new ExecutionResult(false, null, "Request content is empty", 0);
        }

        try {
            logger.info("Executing simulated user request task: useOrchestrator={}, text={}",
                    useOrchestrator,
                    requestText.length() > 120 ? requestText.substring(0, 120) + "..." : requestText);

            ChatRequest request = unifiedChatService.normalizeTextInput(requestText, null, useOrchestrator, false);
            ChatResponse response = unifiedChatService.process(request);
            long duration = System.currentTimeMillis() - startTime;

            String output = response.agentText();
            if (response.orchestratorState() != null) {
                output = String.format("%s%n[orchestrator_state=%s]", output, response.orchestratorState());
            }

            if (response.success()) {
                logger.info("Simulated user request task completed in {}ms", duration);
                return new ExecutionResult(true, output, null, duration);
            }

            logger.warn("Simulated user request task failed in {}ms: {}", duration, output);
            return new ExecutionResult(false, output, output, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Simulated user request task error: {}", e.getMessage(), e);
            return new ExecutionResult(false, null, e.getMessage(), duration);
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
