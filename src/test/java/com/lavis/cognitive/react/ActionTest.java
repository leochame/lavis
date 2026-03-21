package com.lavis.agent.react;

import com.lavis.feature.scheduler.TaskRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("TaskRules Cron Compatibility Tests")
class ActionTest {

    private final TaskRules rules = new TaskRules();

    @Test
    @DisplayName("Should normalize 5-field cron expressions")
    void shouldNormalizeFiveFieldCron() {
        assertEquals("0 0 9 * * *", rules.normalizeCronExpression("0 9 * * *"));
    }

    @Test
    @DisplayName("Should preserve 6-field cron expressions")
    void shouldPreserveSixFieldCron() {
        assertEquals("0 30 9 * * *", rules.normalizeCronExpression("0 30 9 * * *"));
    }

    @Test
    @DisplayName("Should reject invalid cron expression")
    void shouldRejectInvalidCron() {
        assertThrows(IllegalArgumentException.class, () -> rules.normalizeCronExpression("* * *"));
    }
}
