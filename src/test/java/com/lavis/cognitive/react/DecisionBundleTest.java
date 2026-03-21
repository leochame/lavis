package com.lavis.agent.react;

import com.lavis.feature.scheduler.TaskRules;
import com.lavis.infra.persistence.entity.ScheduledTaskEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("TaskRules Execution Compatibility Tests")
class DecisionBundleTest {

    private final TaskRules rules = new TaskRules();

    @Test
    @DisplayName("Should normalize default execution mode to COMMAND")
    void shouldNormalizeDefaultExecutionMode() {
        ScheduledTaskEntity task = baseTask();
        task.setExecutionMode(null);

        rules.validateAndNormalize(task);

        assertEquals(TaskRules.EXECUTION_MODE_COMMAND, task.getExecutionMode());
    }

    @Test
    @DisplayName("Should normalize request execution mode case-insensitively")
    void shouldNormalizeRequestExecutionMode() {
        ScheduledTaskEntity task = baseTask();
        task.setExecutionMode("request");
        task.setRequestContent("hello");

        rules.validateAndNormalize(task);

        assertEquals(TaskRules.EXECUTION_MODE_REQUEST, task.getExecutionMode());
    }

    @Test
    @DisplayName("Should reject unsupported execution mode")
    void shouldRejectUnsupportedExecutionMode() {
        ScheduledTaskEntity task = baseTask();
        task.setExecutionMode("SCRIPT");
        assertThrows(IllegalArgumentException.class, () -> rules.validateAndNormalize(task));
    }

    private ScheduledTaskEntity baseTask() {
        ScheduledTaskEntity task = new ScheduledTaskEntity();
        task.setName("compat");
        task.setSourceType(TaskRules.SOURCE_TYPE_MANUAL);
        task.setScheduleMode(TaskRules.SCHEDULE_MODE_CRON);
        task.setCronExpression("0 0 9 * * *");
        task.setCommand("echo ok");
        task.setEnabled(true);
        task.setRunCount(0);
        task.setPenaltyPoints(0);
        task.setAutoPaused(false);
        return task;
    }
}
