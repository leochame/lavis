package com.lavis.agent.react;

import com.lavis.feature.scheduler.TaskRules;
import com.lavis.infra.persistence.entity.ScheduledTaskEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("TaskRules Schedule Compatibility Tests")
class DecisionBundleSchemaTest {

    private final TaskRules rules = new TaskRules();

    @Test
    @DisplayName("Should normalize CRON mode and clear loop interval")
    void shouldNormalizeCronMode() {
        ScheduledTaskEntity task = baseTask();
        task.setScheduleMode("cron");
        task.setCronExpression("0 9 * * *");
        task.setIntervalSeconds(99);

        rules.validateAndNormalize(task);

        assertEquals(TaskRules.SCHEDULE_MODE_CRON, task.getScheduleMode());
        assertEquals("0 0 9 * * *", task.getCronExpression());
        assertNull(task.getIntervalSeconds());
    }

    @Test
    @DisplayName("Should normalize LOOP mode and keep placeholder cron")
    void shouldNormalizeLoopMode() {
        ScheduledTaskEntity task = baseTask();
        task.setScheduleMode("loop");
        task.setIntervalSeconds(15);
        task.setCronExpression(null);

        rules.validateAndNormalize(task);

        assertEquals(TaskRules.SCHEDULE_MODE_LOOP, task.getScheduleMode());
        assertEquals(15, task.getIntervalSeconds());
        assertEquals("0 * * * * *", task.getCronExpression());
    }

    @Test
    @DisplayName("Should reject invalid loop interval")
    void shouldRejectInvalidLoopInterval() {
        ScheduledTaskEntity task = baseTask();
        task.setScheduleMode("LOOP");
        task.setIntervalSeconds(0);

        assertThrows(IllegalArgumentException.class, () -> rules.validateAndNormalize(task));
    }

    private ScheduledTaskEntity baseTask() {
        ScheduledTaskEntity task = new ScheduledTaskEntity();
        task.setName("compat");
        task.setSourceType(TaskRules.SOURCE_TYPE_MANUAL);
        task.setExecutionMode(TaskRules.EXECUTION_MODE_COMMAND);
        task.setCommand("echo ok");
        task.setEnabled(true);
        task.setRunCount(0);
        task.setPenaltyPoints(0);
        task.setAutoPaused(false);
        return task;
    }
}
