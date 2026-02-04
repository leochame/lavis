package com.lavis.cognitive.react;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReactTaskContext 类单元测试
 *
 * 测试任务上下文类的各种功能
 */
@DisplayName("ReactTaskContext Tests")
class ReactTaskContextTest {

    private ReactTaskContext context;

    @BeforeEach
    void setUp() {
        context = new ReactTaskContext("Login to the application");
    }

    @Nested
    @DisplayName("Constructor tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should initialize with goal")
        void shouldInitializeWithGoal() {
            assertEquals("Login to the application", context.getGlobalGoal());
        }

        @Test
        @DisplayName("Should generate unique context ID")
        void shouldGenerateUniqueContextId() {
            assertNotNull(context.getContextId());
            assertEquals(8, context.getContextId().length());

            ReactTaskContext another = new ReactTaskContext("Another goal");
            assertNotEquals(context.getContextId(), another.getContextId());
        }

        @Test
        @DisplayName("Should initialize with zero counters")
        void shouldInitializeWithZeroCounters() {
            assertEquals(0, context.getTotalIterations());
            assertEquals(0, context.getSuccessfulActions());
            assertEquals(0, context.getFailedActions());
            assertEquals(0, context.getConsecutiveFailures());
        }

        @Test
        @DisplayName("Should initialize with creation time")
        void shouldInitializeWithCreationTime() {
            assertNotNull(context.getCreatedAt());
        }
    }

    @Nested
    @DisplayName("Intent management tests")
    class IntentManagementTests {

        @Test
        @DisplayName("startIntent should set current intent")
        void startIntentShouldSetCurrentIntent() {
            context.startIntent("Enter username");
            assertEquals("Enter username", context.getCurrentIntent());
        }

        @Test
        @DisplayName("completeIntent should add to completed list on success")
        void completeIntentShouldAddToCompletedListOnSuccess() {
            context.startIntent("Enter username");
            context.completeIntent(true, "Username entered successfully");

            assertEquals(1, context.getCompletedIntents().size());
            assertNull(context.getCurrentIntent());
        }

        @Test
        @DisplayName("completeIntent should reset consecutive failures on success")
        void completeIntentShouldResetConsecutiveFailuresOnSuccess() {
            context.setConsecutiveFailures(3);
            context.startIntent("Click button");
            context.completeIntent(true, "Button clicked");

            assertEquals(0, context.getConsecutiveFailures());
        }

        @Test
        @DisplayName("completeIntent should increment consecutive failures on failure")
        void completeIntentShouldIncrementConsecutiveFailuresOnFailure() {
            context.startIntent("Click button");
            context.completeIntent(false, "Button not found");

            assertEquals(1, context.getConsecutiveFailures());
            assertEquals("Button not found", context.getLastError());
        }

        @Test
        @DisplayName("completeIntent should do nothing when no current intent")
        void completeIntentShouldDoNothingWhenNoCurrentIntent() {
            context.completeIntent(true, "Result");
            assertTrue(context.getCompletedIntents().isEmpty());
        }
    }

    @Nested
    @DisplayName("Action recording tests")
    class ActionRecordingTests {

        @Test
        @DisplayName("recordAction should add to recent actions")
        void recordActionShouldAddToRecentActions() {
            Action action = Action.click(100, 100);
            context.recordAction(action, true, "Clicked successfully");

            assertEquals(1, context.getRecentActions().size());
            assertEquals(1, context.getSuccessfulActions());
        }

        @Test
        @DisplayName("recordAction should increment failed count on failure")
        void recordActionShouldIncrementFailedCountOnFailure() {
            Action action = Action.click(100, 100);
            context.recordAction(action, false, "Click missed");

            assertEquals(1, context.getFailedActions());
        }

        @Test
        @DisplayName("recordAction should maintain max recent actions limit")
        void recordActionShouldMaintainMaxRecentActionsLimit() {
            // Add more than MAX_RECENT_ACTIONS (10)
            for (int i = 0; i < 15; i++) {
                context.recordAction(Action.click(i * 10, i * 10), true, "Click " + i);
            }

            assertEquals(10, context.getRecentActions().size());
        }

        @Test
        @DisplayName("recordRoundActions should set last round summary")
        void recordRoundActionsShouldSetLastRoundSummary() {
            ExecuteNow executeNow = ExecuteNow.batch("Fill form",
                    Action.click(100, 100),
                    Action.type("admin"));
            List<String> results = List.of("Clicked", "Typed");

            context.recordRoundActions(executeNow, results);

            assertNotNull(context.getLastRoundActionsSummary());
            assertTrue(context.getLastRoundActionsSummary().contains("Fill form"));
            assertTrue(context.getLastRoundActionsSummary().contains("click(100,100)"));
        }

        @Test
        @DisplayName("recordRoundActions should handle null executeNow")
        void recordRoundActionsShouldHandleNullExecuteNow() {
            context.recordRoundActions(null, List.of());
            assertNull(context.getLastRoundActionsSummary());
        }
    }

    @Nested
    @DisplayName("Iteration tracking tests")
    class IterationTrackingTests {

        @Test
        @DisplayName("incrementIteration should increase counter")
        void incrementIterationShouldIncreaseCounter() {
            context.incrementIteration();
            assertEquals(1, context.getTotalIterations());

            context.incrementIteration();
            assertEquals(2, context.getTotalIterations());
        }
    }

    @Nested
    @DisplayName("Context injection tests")
    class ContextInjectionTests {

        @Test
        @DisplayName("generateContextInjection should include global goal")
        void generateContextInjectionShouldIncludeGlobalGoal() {
            String injection = context.generateContextInjection();
            assertTrue(injection.contains("Login to the application"));
        }

        @Test
        @DisplayName("generateContextInjection should include progress")
        void generateContextInjectionShouldIncludeProgress() {
            context.incrementIteration();
            context.incrementIteration();

            String injection = context.generateContextInjection();
            assertTrue(injection.contains("Iteration: 2"));
        }

        @Test
        @DisplayName("generateContextInjection should include completed intents")
        void generateContextInjectionShouldIncludeCompletedIntents() {
            context.startIntent("Enter username");
            context.completeIntent(true, "Done");
            context.startIntent("Enter password");
            context.completeIntent(true, "Done");

            String injection = context.generateContextInjection();
            assertTrue(injection.contains("Completed Intents"));
            assertTrue(injection.contains("Enter username"));
        }

        @Test
        @DisplayName("generateContextInjection should include last round actions")
        void generateContextInjectionShouldIncludeLastRoundActions() {
            ExecuteNow executeNow = ExecuteNow.single("Click", Action.click(100, 100));
            context.recordRoundActions(executeNow, List.of("Clicked"));

            String injection = context.generateContextInjection();
            assertTrue(injection.contains("Last Round Actions"));
        }

        @Test
        @DisplayName("generateContextInjection should include recovery warning")
        void generateContextInjectionShouldIncludeRecoveryWarning() {
            context.setInRecoveryMode(true);
            context.setLastError("Element not found");

            String injection = context.generateContextInjection();
            assertTrue(injection.contains("Warning"));
            assertTrue(injection.contains("Element not found"));
        }
    }

    @Nested
    @DisplayName("Recovery mode tests")
    class RecoveryModeTests {

        @Test
        @DisplayName("shouldEnterRecoveryMode should return true when threshold reached")
        void shouldEnterRecoveryModeShouldReturnTrueWhenThresholdReached() {
            context.setConsecutiveFailures(5);
            assertTrue(context.shouldEnterRecoveryMode(5));
        }

        @Test
        @DisplayName("shouldEnterRecoveryMode should return false when below threshold")
        void shouldEnterRecoveryModeShouldReturnFalseWhenBelowThreshold() {
            context.setConsecutiveFailures(3);
            assertFalse(context.shouldEnterRecoveryMode(5));
        }

        @Test
        @DisplayName("enterRecoveryMode should set flag")
        void enterRecoveryModeShouldSetFlag() {
            context.enterRecoveryMode();
            assertTrue(context.isInRecoveryMode());
        }

        @Test
        @DisplayName("exitRecoveryMode should reset flag and failures")
        void exitRecoveryModeShouldResetFlagAndFailures() {
            context.setConsecutiveFailures(5);
            context.enterRecoveryMode();

            context.exitRecoveryMode();

            assertFalse(context.isInRecoveryMode());
            assertEquals(0, context.getConsecutiveFailures());
        }
    }

    @Nested
    @DisplayName("Execution summary tests")
    class ExecutionSummaryTests {

        @Test
        @DisplayName("getExecutionSummary should include all stats")
        void getExecutionSummaryShouldIncludeAllStats() {
            context.incrementIteration();
            context.incrementIteration();
            context.startIntent("Test");
            context.completeIntent(true, "Done");
            context.recordAction(Action.click(100, 100), true, "Clicked");
            context.recordAction(Action.type("text"), false, "Failed");

            String summary = context.getExecutionSummary();

            assertTrue(summary.contains("Context ID"));
            assertTrue(summary.contains("Goal"));
            assertTrue(summary.contains("Iterations: 2"));
            assertTrue(summary.contains("Completed Intents: 1"));
            assertTrue(summary.contains("1 success"));
            assertTrue(summary.contains("1 failed"));
        }
    }

    @Nested
    @DisplayName("IntentRecord tests")
    class IntentRecordTests {

        @Test
        @DisplayName("IntentRecord should store all fields")
        void intentRecordShouldStoreAllFields() {
            ReactTaskContext.IntentRecord record = new ReactTaskContext.IntentRecord(
                    "Click button", true, "Button clicked");

            assertEquals("Click button", record.intent());
            assertTrue(record.success());
            assertEquals("Button clicked", record.result());
        }
    }

    @Nested
    @DisplayName("ActionRecord tests")
    class ActionRecordTests {

        @Test
        @DisplayName("ActionRecord should store all fields")
        void actionRecordShouldStoreAllFields() {
            Action action = Action.click(100, 100);
            ReactTaskContext.ActionRecord record = new ReactTaskContext.ActionRecord(
                    action, true, "Clicked at (100,100)");

            assertEquals(action, record.action());
            assertTrue(record.success());
            assertEquals("Clicked at (100,100)", record.result());
        }
    }
}
