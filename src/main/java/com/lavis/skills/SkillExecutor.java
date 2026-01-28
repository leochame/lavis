package com.lavis.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lavis.action.AppleScriptExecutor;
import com.lavis.skills.model.ParsedSkill;
import com.lavis.skills.model.SkillExecutionContext;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Skill 执行器 - 重构版本
 *
 * 核心改进：上下文注入（Context Injection）
 *
 * 执行流程：
 * 1. LLM 决定调用某个 Skill（如 java_coding）
 * 2. SkillExecutor 拦截调用
 * 3. 【关键】读取 Skill 的 content（Markdown 正文），构建执行上下文
 * 4. 将上下文注入到 Agent 的对话中
 * 5. 执行具体指令（或让 Agent 基于上下文生成回复）
 *
 * 这样 Agent 就能真正"学会"文档里的知识，而不仅仅是运行脚本。
 */
@Component
public class SkillExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SkillExecutor.class);

    private final AppleScriptExecutor appleScriptExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 上下文注入回调。
     * 当设置后，执行 agent: 命令时会先注入上下文，再执行。
     * 这个回调由 AgentService 设置，用于将知识注入到对话中。
     */
    private BiFunction<SkillExecutionContext, String, String> contextInjectionCallback;

    public SkillExecutor(AppleScriptExecutor appleScriptExecutor) {
        this.appleScriptExecutor = appleScriptExecutor;
    }

    /**
     * 设置上下文注入回调。
     * AgentService 调用此方法注册回调，实现知识注入。
     *
     * @param callback (context, goal) -> response
     */
    public void setContextInjectionCallback(BiFunction<SkillExecutionContext, String, String> callback) {
        this.contextInjectionCallback = callback;
        logger.info("Context injection callback registered");
    }

    /**
     * 执行 Skill（使用结构化参数）- 新版本
     *
     * @param skill  解析后的技能
     * @param params 来自 LLM Function Call 的 JSON 参数
     * @return 执行结果
     */
    public ExecutionResult executeWithContext(ParsedSkill skill, Map<String, Object> params) {
        // 构建执行上下文（包含知识注入）
        SkillExecutionContext context = skill.buildExecutionContext(params);

        String command = context.getResolvedCommand();
        if (command == null || command.isEmpty()) {
            // 如果没有命令，但有知识内容，则纯粹作为知识注入
            if (context.getKnowledgeContent() != null && !context.getKnowledgeContent().isBlank()) {
                return executeKnowledgeInjection(context);
            }
            return new ExecutionResult(false, null, "Skill has no command or knowledge defined", 0);
        }

        logger.info("Executing skill '{}' with context injection", skill.getName());
        logger.debug("Knowledge content length: {} chars",
                context.getKnowledgeContent() != null ? context.getKnowledgeContent().length() : 0);

        return executeCommandWithContext(context, command);
    }

    /**
     * 执行 Skill（兼容旧版本 Map<String, String> 参数）
     */
    public ExecutionResult execute(ParsedSkill skill, Map<String, String> params) {
        // 转换为 Object Map
        Map<String, Object> objectParams = new HashMap<>();
        if (params != null) {
            objectParams.putAll(params);
        }
        return executeWithContext(skill, objectParams);
    }

    /**
     * 执行 Skill（使用 JSON 字符串参数）
     * 这是 LLM Function Call 的入口点
     */
    public ExecutionResult executeFromJson(ParsedSkill skill, String paramsJson) {
        try {
            Map<String, Object> params = objectMapper.readValue(
                    paramsJson,
                    new TypeReference<Map<String, Object>>() {}
            );
            return executeWithContext(skill, params);
        } catch (Exception e) {
            logger.error("Failed to parse skill parameters: {}", e.getMessage());
            return new ExecutionResult(false, null, "Invalid parameters: " + e.getMessage(), 0);
        }
    }

    /**
     * 执行原始命令（不带上下文）
     */
    public ExecutionResult executeCommand(String command) {
        return executeCommandWithContext(null, command);
    }

    // ==================== Private Methods ====================

    /**
     * 带上下文的命令执行
     */
    private ExecutionResult executeCommandWithContext(SkillExecutionContext context, String command) {
        if (command.startsWith("agent:")) {
            return executeAgentCommand(context, command.substring(6).trim());
        } else if (command.startsWith("shell:")) {
            return executeShellCommand(command.substring(6).trim());
        } else {
            // Default to shell execution
            return executeShellCommand(command);
        }
    }

    /**
     * 执行 Agent 命令（带上下文注入）
     *
     * 这是解决"Context Gap"的核心方法：
     * 1. 如果有上下文，先将知识注入到对话中
     * 2. 然后执行 Agent 命令
     */
    private ExecutionResult executeAgentCommand(SkillExecutionContext context, String goal) {
        long startTime = System.currentTimeMillis();
        try {
            logger.info("Executing agent command with context: {}", goal);

            String response;
            if (contextInjectionCallback != null && context != null) {
                // 使用上下文注入回调
                logger.info("Injecting skill knowledge into agent context");
                response = contextInjectionCallback.apply(context, goal);
            } else {
                // 降级：无上下文注入，直接执行
                logger.warn("No context injection callback, executing without knowledge injection");
                response = "Context injection not available. Goal: " + goal;
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Agent command completed in {}ms", duration);
            return new ExecutionResult(true, response, null, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Agent command failed: {}", e.getMessage(), e);
            return new ExecutionResult(false, null, e.getMessage(), duration);
        }
    }

    /**
     * 纯知识注入（无命令执行）
     * 用于那些只提供指导而不执行具体操作的 Skill
     */
    private ExecutionResult executeKnowledgeInjection(SkillExecutionContext context) {
        long startTime = System.currentTimeMillis();
        try {
            logger.info("Executing pure knowledge injection for skill: {}", context.getSkillName());

            if (contextInjectionCallback != null) {
                // 注入知识，让 Agent 基于知识回复
                String response = contextInjectionCallback.apply(context,
                        "Apply the skill knowledge to assist the user.");
                long duration = System.currentTimeMillis() - startTime;
                return new ExecutionResult(true, response, null, duration);
            } else {
                // 返回知识内容作为结果
                String injection = context.toSystemPromptInjection();
                long duration = System.currentTimeMillis() - startTime;
                return new ExecutionResult(true, injection, null, duration);
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Knowledge injection failed: {}", e.getMessage(), e);
            return new ExecutionResult(false, null, e.getMessage(), duration);
        }
    }

    /**
     * 执行 Shell 命令
     */
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
