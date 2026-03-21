package com.lavis.agent.react;

import com.lavis.feature.scheduler.TaskRules;
import com.lavis.infra.persistence.entity.ScheduledTaskEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TaskRules Request Fallback Compatibility Tests")
class ExecuteNowTest {

    private final TaskRules rules = new TaskRules();

    @Test
    @DisplayName("Should use requestContent when provided in REQUEST mode")
    void shouldUseRequestContent() {
        ScheduledTaskEntity task = requestTask("hello from request content", "request: ignored");

        rules.validateAndNormalize(task);

        assertEquals("hello from request content", task.getRequestContent());
    }

    @Test
    @DisplayName("Should fallback to command text when requestContent missing")
    void shouldFallbackToCommandText() {
        ScheduledTaskEntity task = requestTask(null, "request: hello from command");

        rules.validateAndNormalize(task);

        assertEquals("hello from command", task.getRequestContent());
    }

    @Test
    @DisplayName("Should default requestUseOrchestrator to false when null")
    void shouldDefaultRequestUseOrchestrator() {
        ScheduledTaskEntity task = requestTask("ping", "request: ping");
        task.setRequestUseOrchestrator(null);

        rules.validateAndNormalize(task);

        assertFalse(task.getRequestUseOrchestrator());
    }

    @Test
    @DisplayName("Should keep explicit requestUseOrchestrator true")
    void shouldKeepRequestUseOrchestratorTrue() {
        ScheduledTaskEntity task = requestTask("ping", "request: ping");
        task.setRequestUseOrchestrator(true);

        rules.validateAndNormalize(task);

        assertTrue(task.getRequestUseOrchestrator());
    }

    private ScheduledTaskEntity requestTask(String requestContent, String command) {
        ScheduledTaskEntity task = new ScheduledTaskEntity();
        task.setName("request-task");
        task.setSourceType(TaskRules.SOURCE_TYPE_MANUAL);
        task.setScheduleMode(TaskRules.SCHEDULE_MODE_CRON);
        task.setCronExpression("0 0 9 * * *");
        task.setExecutionMode(TaskRules.EXECUTION_MODE_REQUEST);
        task.setRequestContent(requestContent);
        task.setCommand(command);
        task.setEnabled(true);
        task.setRunCount(0);
        task.setPenaltyPoints(0);
        task.setAutoPaused(false);
        return task;
    }
}
