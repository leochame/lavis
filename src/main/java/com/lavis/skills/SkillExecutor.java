package com.lavis.skills;

import com.lavis.action.AppleScriptExecutor;
import com.lavis.cognitive.AgentService;
import com.lavis.skills.model.ParsedSkill;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Executes skill commands with support for agent: and shell: prefixes.
 * Handles parameter substitution.
 */
@Component
public class SkillExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SkillExecutor.class);

    private final AgentService agentService;
    private final AppleScriptExecutor appleScriptExecutor;

    public SkillExecutor(@Lazy AgentService agentService, AppleScriptExecutor appleScriptExecutor) {
        this.agentService = agentService;
        this.appleScriptExecutor = appleScriptExecutor;
    }

    /**
     * Execute a skill with the given parameters.
     */
    public ExecutionResult execute(ParsedSkill skill, Map<String, String> params) {
        String command = skill.resolveCommand(params);
        if (command == null || command.isEmpty()) {
            return new ExecutionResult(false, null, "Skill has no command defined", 0);
        }

        logger.info("Executing skill '{}' with command: {}", skill.getName(), command);
        return executeCommand(command);
    }

    /**
     * Execute a raw command string.
     */
    public ExecutionResult executeCommand(String command) {
        if (command.startsWith("agent:")) {
            return executeAgentCommand(command.substring(6).trim());
        } else if (command.startsWith("shell:")) {
            return executeShellCommand(command.substring(6).trim());
        } else {
            // Default to shell execution
            return executeShellCommand(command);
        }
    }

    private ExecutionResult executeAgentCommand(String goal) {
        long startTime = System.currentTimeMillis();
        try {
            logger.info("Executing agent command: {}", goal);
            String response = agentService.chatWithScreenshot(goal);
            long duration = System.currentTimeMillis() - startTime;
            logger.info("Agent command completed in {}ms", duration);
            return new ExecutionResult(true, response, null, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Agent command failed: {}", e.getMessage(), e);
            return new ExecutionResult(false, null, e.getMessage(), duration);
        }
    }

    private ExecutionResult executeShellCommand(String command) {
        long startTime = System.currentTimeMillis();
        try {
            logger.info("Executing shell command: {}", command);
            AppleScriptExecutor.ExecutionResult result = appleScriptExecutor.executeShell(command);
            long duration = System.currentTimeMillis() - startTime;

            boolean success = result.exitCode() == 0;
            String output = result.output();
            String error = success ? null : output;

            if (success) {
                logger.info("Shell command completed in {}ms", duration);
            } else {
                logger.warn("Shell command failed with exit code {}: {}", result.exitCode(), error);
            }

            return new ExecutionResult(success, output, error, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Shell command execution error: {}", e.getMessage(), e);
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
