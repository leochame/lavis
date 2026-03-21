package com.lavis.feature.scheduler;

import com.lavis.infra.persistence.entity.ScheduledTaskEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class TaskRules {

    public static final String SCHEDULE_MODE_CRON = "CRON";
    public static final String SCHEDULE_MODE_LOOP = "LOOP";
    public static final String EXECUTION_MODE_COMMAND = "COMMAND";
    public static final String EXECUTION_MODE_REQUEST = "REQUEST";
    public static final String EXECUTION_MODE_SCRIPT = "SCRIPT";
    public static final String SOURCE_TYPE_MANUAL = "MANUAL";
    public static final String SOURCE_TYPE_FILE = "FILE";

    private static final String DEFAULT_LOOP_CRON_PLACEHOLDER = "0 * * * * *";

    public void validateAndNormalize(ScheduledTaskEntity task) {
        if (!hasText(task.getName())) {
            throw new IllegalArgumentException("Task name is required");
        }
        if (task.getEnabled() == null) {
            task.setEnabled(true);
        }
        if (task.getRunCount() == null) {
            task.setRunCount(0);
        }
        if (task.getPenaltyPoints() == null) {
            task.setPenaltyPoints(0);
        }
        if (task.getAutoPaused() == null) {
            task.setAutoPaused(false);
        }
        if (!hasText(task.getSourceType())) {
            task.setSourceType(SOURCE_TYPE_MANUAL);
        }

        String scheduleMode = normalizeScheduleMode(task.getScheduleMode());
        task.setScheduleMode(scheduleMode);
        if (SCHEDULE_MODE_CRON.equals(scheduleMode)) {
            task.setCronExpression(normalizeCronExpression(task.getCronExpression()));
            task.setIntervalSeconds(null);
        } else {
            task.setIntervalSeconds(validateLoopInterval(task.getIntervalSeconds()));
            if (!hasText(task.getCronExpression())) {
                task.setCronExpression(DEFAULT_LOOP_CRON_PLACEHOLDER);
            }
        }

        String executionMode = normalizeExecutionMode(task.getExecutionMode());
        task.setExecutionMode(executionMode);

        boolean fileBacked = SOURCE_TYPE_FILE.equalsIgnoreCase(task.getSourceType());
        if (EXECUTION_MODE_REQUEST.equals(executionMode)) {
            String requestText = resolveRequestText(task.getRequestContent(), task.getCommand());
            if (!fileBacked && !hasText(requestText)) {
                throw new IllegalArgumentException("requestContent is required for REQUEST execution mode");
            }
            if (!fileBacked) {
                task.setRequestContent(requestText);
            }
            if (task.getRequestUseOrchestrator() == null) {
                task.setRequestUseOrchestrator(false);
            }
        } else {
            if (!fileBacked && !hasText(task.getCommand())) {
                throw new IllegalArgumentException("command is required for COMMAND execution mode");
            }
            if (task.getRequestUseOrchestrator() == null) {
                task.setRequestUseOrchestrator(false);
            }
        }

        if (fileBacked && !hasText(task.getSourcePath())) {
            throw new IllegalArgumentException("sourcePath is required for file-backed tasks");
        }
        if (!hasText(task.getCommand())) {
            task.setCommand("[loaded from .task]");
        }
    }

    public LocalDateTime computeNextRunAt(ScheduledTaskEntity task, LocalDateTime fromTime) {
        String scheduleMode = normalizeScheduleMode(task.getScheduleMode());
        if (SCHEDULE_MODE_LOOP.equals(scheduleMode)) {
            int intervalSeconds = validateLoopInterval(task.getIntervalSeconds());
            return fromTime.plusSeconds(intervalSeconds);
        }
        CronExpression cronExpression = CronExpression.parse(normalizeCronExpression(task.getCronExpression()));
        return cronExpression.next(fromTime);
    }

    public String normalizeScheduleMode(String mode) {
        String normalized = hasText(mode) ? mode.trim().toUpperCase() : SCHEDULE_MODE_CRON;
        if (!SCHEDULE_MODE_CRON.equals(normalized) && !SCHEDULE_MODE_LOOP.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported scheduleMode: " + mode + " (allowed: CRON, LOOP)");
        }
        return normalized;
    }

    public String normalizeCronExpression(String cron) {
        if (!hasText(cron)) {
            throw new IllegalArgumentException("cronExpression is required for CRON schedule mode");
        }
        String trimmed = cron.trim();
        String[] parts = trimmed.split("\\s+");
        String normalized = switch (parts.length) {
            case 5 -> "0 " + trimmed;
            case 6 -> trimmed;
            default -> throw new IllegalArgumentException(
                    "Invalid cron expression: " + cron + " (expected 5 or 6 fields)");
        };

        try {
            CronExpression.parse(normalized);
            return normalized;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron expression: " + cron, e);
        }
    }

    public int validateLoopInterval(Integer intervalSeconds) {
        if (intervalSeconds == null || intervalSeconds <= 0) {
            throw new IllegalArgumentException("intervalSeconds must be > 0 for LOOP schedule mode");
        }
        return intervalSeconds;
    }

    private String normalizeExecutionMode(String mode) {
        String normalized = hasText(mode) ? mode.trim().toUpperCase() : EXECUTION_MODE_COMMAND;
        if (!EXECUTION_MODE_COMMAND.equals(normalized) && !EXECUTION_MODE_REQUEST.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported executionMode: " + mode + " (allowed: COMMAND, REQUEST)");
        }
        return normalized;
    }

    private String resolveRequestText(String requestContent, String command) {
        if (hasText(requestContent)) {
            return requestContent.trim();
        }
        if (!hasText(command)) {
            return null;
        }
        String trimmed = command.trim();
        if (trimmed.startsWith("request-task:")) {
            return trimmed.substring("request-task:".length()).trim();
        }
        if (trimmed.startsWith("request:")) {
            return trimmed.substring("request:".length()).trim();
        }
        return trimmed;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
