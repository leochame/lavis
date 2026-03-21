package com.lavis.agent.react;

import com.lavis.feature.scheduler.TaskRules;
import com.lavis.infra.persistence.entity.ScheduledTaskEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Legacy react package smoke tests.
 *
 * The historical ReactTaskContext class was removed during architecture
 * refactoring. This test keeps compatibility-focused checks for scheduling
 * rules that now cover the same runtime decision boundaries.
 */
@DisplayName("TaskRules Compatibility Tests")
class ReactTaskContextTest {

    private final TaskRules rules = new TaskRules();

    @Test
    @DisplayName("Should normalize 5-field cron into Spring 6-field format")
    void shouldNormalizeFiveFieldCron() {
        assertEquals("0 0 9 * * 1-5", rules.normalizeCronExpression("0 9 * * 1-5"));
    }

    @Test
    @DisplayName("Should reject invalid schedule mode")
    void shouldRejectInvalidScheduleMode() {
        assertThrows(IllegalArgumentException.class, () -> rules.normalizeScheduleMode("bad-mode"));
    }

    @Test
    @DisplayName("Should compute next run for loop mode")
    void shouldComputeNextRunForLoopMode() {
        ScheduledTaskEntity task = new ScheduledTaskEntity();
        task.setName("loop-task");
        task.setSourceType(TaskRules.SOURCE_TYPE_MANUAL);
        task.setScheduleMode(TaskRules.SCHEDULE_MODE_LOOP);
        task.setIntervalSeconds(30);
        task.setExecutionMode(TaskRules.EXECUTION_MODE_COMMAND);
        task.setCommand("echo ok");
        task.setEnabled(true);

        rules.validateAndNormalize(task);
        LocalDateTime from = LocalDateTime.of(2026, 3, 21, 10, 0, 0);
        LocalDateTime next = rules.computeNextRunAt(task, from);

        assertEquals(from.plusSeconds(30), next);
    }
}
