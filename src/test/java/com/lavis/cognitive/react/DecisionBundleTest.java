package com.lavis.cognitive.react;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DecisionBundle 类单元测试
 *
 * 测试决策包类的各种功能
 */
@DisplayName("DecisionBundle Tests")
class DecisionBundleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("hasActionsToExecute() method")
    class HasActionsToExecuteTests {

        @Test
        @DisplayName("Should return true when not complete and has actions")
        void shouldReturnTrueWhenNotCompleteAndHasActions() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Need to click")
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.single("Click", Action.click(100, 100)))
                    .build();

            assertTrue(bundle.hasActionsToExecute());
        }

        @Test
        @DisplayName("Should return false when goal is complete")
        void shouldReturnFalseWhenGoalIsComplete() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Done")
                    .isGoalComplete(true)
                    .completionSummary("Task finished")
                    .build();

            assertFalse(bundle.hasActionsToExecute());
        }

        @Test
        @DisplayName("Should return false when executeNow is null")
        void shouldReturnFalseWhenExecuteNowIsNull() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Thinking")
                    .isGoalComplete(false)
                    .executeNow(null)
                    .build();

            assertFalse(bundle.hasActionsToExecute());
        }

        @Test
        @DisplayName("Should return false when executeNow has no actions")
        void shouldReturnFalseWhenExecuteNowHasNoActions() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Thinking")
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.builder().intent("Empty").build())
                    .build();

            assertFalse(bundle.hasActionsToExecute());
        }
    }

    @Nested
    @DisplayName("getActionCount() method")
    class GetActionCountTests {

        @Test
        @DisplayName("Should return correct count when has actions")
        void shouldReturnCorrectCountWhenHasActions() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Multiple actions")
                    .executeNow(ExecuteNow.batch("Test",
                            Action.click(100, 100),
                            Action.type("text"),
                            Action.key("enter")))
                    .build();

            assertEquals(3, bundle.getActionCount());
        }

        @Test
        @DisplayName("Should return 0 when executeNow is null")
        void shouldReturnZeroWhenExecuteNowIsNull() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("No actions")
                    .executeNow(null)
                    .build();

            assertEquals(0, bundle.getActionCount());
        }
    }

    @Nested
    @DisplayName("toString() method")
    class ToStringTests {

        @Test
        @DisplayName("Should format complete bundle correctly")
        void shouldFormatCompleteBundleCorrectly() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Done")
                    .isGoalComplete(true)
                    .completionSummary("Login successful")
                    .build();

            String str = bundle.toString();
            assertTrue(str.contains("COMPLETE"));
            assertTrue(str.contains("Login successful"));
        }

        @Test
        @DisplayName("Should format incomplete bundle correctly")
        void shouldFormatIncompleteBundleCorrectly() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("I see the login page and need to enter credentials")
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.batch("Login",
                            Action.click(100, 100),
                            Action.type("admin")))
                    .build();

            String str = bundle.toString();
            assertTrue(str.contains("thought="));
            assertTrue(str.contains("actions=2"));
        }

        @Test
        @DisplayName("Should truncate long thought")
        void shouldTruncateLongThought() {
            String longThought = "This is a very long thought that should be truncated because it exceeds the maximum length allowed for display purposes";
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought(longThought)
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.single("Click", Action.click(100, 100)))
                    .build();

            String str = bundle.toString();
            assertTrue(str.contains("..."));
            assertTrue(str.length() < longThought.length() + 50);
        }
    }

    @Nested
    @DisplayName("JSON serialization/deserialization")
    class JsonTests {

        @Test
        @DisplayName("Should serialize to JSON correctly")
        void shouldSerializeToJsonCorrectly() throws JsonProcessingException {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Test thought")
                    .lastActionResult("success")
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.single("Click", Action.click(500, 300)))
                    .build();

            String json = objectMapper.writeValueAsString(bundle);

            assertTrue(json.contains("\"thought\":\"Test thought\""));
            assertTrue(json.contains("\"last_action_result\":\"success\""));
            assertTrue(json.contains("\"is_goal_complete\":false"));
            assertTrue(json.contains("\"execute_now\""));
        }

        @Test
        @DisplayName("Should deserialize from JSON correctly")
        void shouldDeserializeFromJsonCorrectly() throws JsonProcessingException {
            String json = """
                    {
                        "thought": "Analyzing screen",
                        "last_action_result": "none",
                        "execute_now": {
                            "intent": "Click button",
                            "actions": [{"type": "click", "coords": [500, 300]}]
                        },
                        "is_goal_complete": false
                    }
                    """;

            DecisionBundle bundle = objectMapper.readValue(json, DecisionBundle.class);

            assertEquals("Analyzing screen", bundle.getThought());
            assertEquals("none", bundle.getLastActionResult());
            assertFalse(bundle.isGoalComplete());
            assertNotNull(bundle.getExecuteNow());
            assertEquals("Click button", bundle.getExecuteNow().getIntent());
        }

        @Test
        @DisplayName("Should handle goal complete JSON")
        void shouldHandleGoalCompleteJson() throws JsonProcessingException {
            String json = """
                    {
                        "thought": "Task finished",
                        "last_action_result": "success",
                        "execute_now": null,
                        "is_goal_complete": true,
                        "completion_summary": "Successfully logged in and navigated to dashboard"
                    }
                    """;

            DecisionBundle bundle = objectMapper.readValue(json, DecisionBundle.class);

            assertTrue(bundle.isGoalComplete());
            assertNull(bundle.getExecuteNow());
            assertEquals("Successfully logged in and navigated to dashboard", bundle.getCompletionSummary());
        }

        @Test
        @DisplayName("Should round-trip serialize/deserialize")
        void shouldRoundTripSerializeDeserialize() throws JsonProcessingException {
            DecisionBundle original = DecisionBundle.builder()
                    .thought("Original thought")
                    .lastActionResult("partial")
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.batch("Fill form",
                            Action.click(100, 100),
                            Action.type("username"),
                            Action.key("tab"),
                            Action.type("password")))
                    .build();

            String json = objectMapper.writeValueAsString(original);
            DecisionBundle deserialized = objectMapper.readValue(json, DecisionBundle.class);

            assertEquals(original.getThought(), deserialized.getThought());
            assertEquals(original.getLastActionResult(), deserialized.getLastActionResult());
            assertEquals(original.isGoalComplete(), deserialized.isGoalComplete());
            assertEquals(original.getActionCount(), deserialized.getActionCount());
            assertEquals(original.getExecuteNow().getIntent(), deserialized.getExecuteNow().getIntent());
        }
    }

    @Nested
    @DisplayName("Builder tests")
    class BuilderTests {

        @Test
        @DisplayName("Builder should create bundle with all fields")
        void builderShouldCreateBundleWithAllFields() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("My thought")
                    .lastActionResult("success")
                    .executeNow(ExecuteNow.single("Click", Action.click(100, 100)))
                    .isGoalComplete(false)
                    .completionSummary(null)
                    .build();

            assertEquals("My thought", bundle.getThought());
            assertEquals("success", bundle.getLastActionResult());
            assertNotNull(bundle.getExecuteNow());
            assertFalse(bundle.isGoalComplete());
            assertNull(bundle.getCompletionSummary());
        }

        @Test
        @DisplayName("Builder should create complete bundle")
        void builderShouldCreateCompleteBundle() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Task done")
                    .lastActionResult("success")
                    .isGoalComplete(true)
                    .completionSummary("All steps completed successfully")
                    .build();

            assertTrue(bundle.isGoalComplete());
            assertEquals("All steps completed successfully", bundle.getCompletionSummary());
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle null thought gracefully in toString")
        void shouldHandleNullThoughtGracefully() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought(null)
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.single("Click", Action.click(100, 100)))
                    .build();

            assertDoesNotThrow(() -> bundle.toString());
        }

        @Test
        @DisplayName("Should handle empty thought")
        void shouldHandleEmptyThought() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("")
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.single("Click", Action.click(100, 100)))
                    .build();

            assertEquals("", bundle.getThought());
        }
    }
}
