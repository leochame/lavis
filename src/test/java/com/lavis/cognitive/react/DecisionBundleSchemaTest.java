package com.lavis.cognitive.react;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DecisionBundleSchema 单元测试
 *
 * 测试 JSON 解析和验证功能
 */
@DisplayName("DecisionBundleSchema Tests")
class DecisionBundleSchemaTest {

    @Nested
    @DisplayName("parse() method tests")
    class ParseTests {

        @Test
        @DisplayName("Should parse valid JSON successfully")
        void shouldParseValidJson() throws JsonProcessingException {
            String json = """
                    {
                        "thought": "I see the login page",
                        "last_action_result": "success",
                        "execute_now": {
                            "intent": "Enter username",
                            "actions": [
                                {"type": "click", "coords": [500, 300]},
                                {"type": "type", "text": "admin"}
                            ]
                        },
                        "is_goal_complete": false
                    }
                    """;

            DecisionBundle bundle = DecisionBundleSchema.parse(json);

            assertNotNull(bundle);
            assertEquals("I see the login page", bundle.getThought());
            assertEquals("success", bundle.getLastActionResult());
            assertFalse(bundle.isGoalComplete());
            assertNotNull(bundle.getExecuteNow());
            assertEquals("Enter username", bundle.getExecuteNow().getIntent());
            assertEquals(2, bundle.getExecuteNow().getActionCount());
        }

        @Test
        @DisplayName("Should parse JSON with markdown code block")
        void shouldParseJsonWithMarkdownCodeBlock() throws JsonProcessingException {
            String json = """
                    ```json
                    {
                        "thought": "Task completed",
                        "last_action_result": "success",
                        "execute_now": null,
                        "is_goal_complete": true,
                        "completion_summary": "Login successful"
                    }
                    ```
                    """;

            DecisionBundle bundle = DecisionBundleSchema.parse(json);

            assertNotNull(bundle);
            assertTrue(bundle.isGoalComplete());
            assertEquals("Login successful", bundle.getCompletionSummary());
        }

        @Test
        @DisplayName("Should parse JSON with plain code block")
        void shouldParseJsonWithPlainCodeBlock() throws JsonProcessingException {
            String json = """
                    ```
                    {
                        "thought": "Analyzing screen",
                        "last_action_result": "none",
                        "execute_now": {
                            "intent": "Click button",
                            "actions": [{"type": "click", "coords": [100, 200]}]
                        },
                        "is_goal_complete": false
                    }
                    ```
                    """;

            DecisionBundle bundle = DecisionBundleSchema.parse(json);

            assertNotNull(bundle);
            assertEquals("Analyzing screen", bundle.getThought());
        }

        @Test
        @DisplayName("Should throw exception for null input")
        void shouldThrowExceptionForNullInput() {
            assertThrows(IllegalArgumentException.class, () -> {
                DecisionBundleSchema.parse(null);
            });
        }

        @Test
        @DisplayName("Should throw exception for blank input")
        void shouldThrowExceptionForBlankInput() {
            assertThrows(IllegalArgumentException.class, () -> {
                DecisionBundleSchema.parse("   ");
            });
        }

        @Test
        @DisplayName("Should throw exception for invalid JSON")
        void shouldThrowExceptionForInvalidJson() {
            assertThrows(JsonProcessingException.class, () -> {
                DecisionBundleSchema.parse("not a json");
            });
        }
    }

    @Nested
    @DisplayName("parseFromResponse() method tests")
    class ParseFromResponseTests {

        @Test
        @DisplayName("Should extract JSON from mixed text response")
        void shouldExtractJsonFromMixedText() throws JsonProcessingException {
            String response = """
                    Here is my analysis:

                    {
                        "thought": "Found the button",
                        "last_action_result": "success",
                        "execute_now": {
                            "intent": "Click submit",
                            "actions": [{"type": "click", "coords": [500, 500]}]
                        },
                        "is_goal_complete": false
                    }

                    This should work.
                    """;

            DecisionBundle bundle = DecisionBundleSchema.parseFromResponse(response);

            assertNotNull(bundle);
            assertEquals("Found the button", bundle.getThought());
        }

        @Test
        @DisplayName("Should handle response with nested JSON objects")
        void shouldHandleNestedJsonObjects() throws JsonProcessingException {
            String response = """
                    {
                        "thought": "Complex action needed",
                        "last_action_result": "partial",
                        "execute_now": {
                            "intent": "Fill form",
                            "actions": [
                                {"type": "click", "coords": [100, 100]},
                                {"type": "type", "text": "test@example.com"},
                                {"type": "key", "key": "tab"},
                                {"type": "type", "text": "password123"}
                            ]
                        },
                        "is_goal_complete": false
                    }
                    """;

            DecisionBundle bundle = DecisionBundleSchema.parseFromResponse(response);

            assertNotNull(bundle);
            assertEquals(4, bundle.getExecuteNow().getActionCount());
        }

        @Test
        @DisplayName("Should throw exception for empty response")
        void shouldThrowExceptionForEmptyResponse() {
            assertThrows(IllegalArgumentException.class, () -> {
                DecisionBundleSchema.parseFromResponse("");
            });
        }
    }

    @Nested
    @DisplayName("validate() method tests")
    class ValidateTests {

        @Test
        @DisplayName("Should validate complete bundle successfully")
        void shouldValidateCompleteBundleSuccessfully() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Analysis complete")
                    .lastActionResult("success")
                    .executeNow(ExecuteNow.single("Click button", Action.click(500, 300)))
                    .isGoalComplete(false)
                    .build();

            DecisionBundleSchema.ValidationResult result = DecisionBundleSchema.validate(bundle);

            assertTrue(result.isValid());
            assertNull(result.getError());
        }

        @Test
        @DisplayName("Should validate goal complete bundle")
        void shouldValidateGoalCompleteBundle() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Task finished")
                    .lastActionResult("success")
                    .isGoalComplete(true)
                    .completionSummary("Successfully logged in")
                    .build();

            DecisionBundleSchema.ValidationResult result = DecisionBundleSchema.validate(bundle);

            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should reject null bundle")
        void shouldRejectNullBundle() {
            DecisionBundleSchema.ValidationResult result = DecisionBundleSchema.validate(null);

            assertFalse(result.isValid());
            assertEquals("DecisionBundle 为空", result.getError());
        }

        @Test
        @DisplayName("Should reject bundle with empty thought")
        void shouldRejectBundleWithEmptyThought() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("")
                    .lastActionResult("success")
                    .isGoalComplete(false)
                    .build();

            DecisionBundleSchema.ValidationResult result = DecisionBundleSchema.validate(bundle);

            assertFalse(result.isValid());
            assertEquals("thought 字段为空", result.getError());
        }

        @Test
        @DisplayName("Should reject goal complete without summary")
        void shouldRejectGoalCompleteWithoutSummary() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Done")
                    .lastActionResult("success")
                    .isGoalComplete(true)
                    .completionSummary(null)
                    .build();

            DecisionBundleSchema.ValidationResult result = DecisionBundleSchema.validate(bundle);

            assertFalse(result.isValid());
            assertEquals("目标完成但缺少 completion_summary", result.getError());
        }

        @Test
        @DisplayName("Should reject incomplete bundle without executeNow")
        void shouldRejectIncompleteBundleWithoutExecuteNow() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Need to act")
                    .lastActionResult("none")
                    .isGoalComplete(false)
                    .executeNow(null)
                    .build();

            DecisionBundleSchema.ValidationResult result = DecisionBundleSchema.validate(bundle);

            assertFalse(result.isValid());
            assertEquals("目标未完成但缺少 execute_now", result.getError());
        }

        @Test
        @DisplayName("Should reject executeNow with no actions")
        void shouldRejectExecuteNowWithNoActions() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Need to act")
                    .lastActionResult("none")
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.builder().intent("Do something").actions(List.of()).build())
                    .build();

            DecisionBundleSchema.ValidationResult result = DecisionBundleSchema.validate(bundle);

            assertFalse(result.isValid());
            assertEquals("execute_now 中没有动作", result.getError());
        }

        @Test
        @DisplayName("Should reject more than 5 actions")
        void shouldRejectMoreThan5Actions() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Many actions")
                    .lastActionResult("none")
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.batch("Too many",
                            Action.click(100, 100),
                            Action.click(200, 200),
                            Action.click(300, 300),
                            Action.click(400, 400),
                            Action.click(500, 500),
                            Action.click(600, 600)))
                    .build();

            DecisionBundleSchema.ValidationResult result = DecisionBundleSchema.validate(bundle);

            assertFalse(result.isValid());
            assertEquals("动作数量超过限制（最多 5 个）", result.getError());
        }

        @Test
        @DisplayName("Should reject action without type")
        void shouldRejectActionWithoutType() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Invalid action")
                    .lastActionResult("none")
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.single("Click", Action.builder().build()))
                    .build();

            DecisionBundleSchema.ValidationResult result = DecisionBundleSchema.validate(bundle);

            assertFalse(result.isValid());
            assertTrue(result.getError().contains("缺少 type"));
        }

        @Test
        @DisplayName("Should reject click action without coords")
        void shouldRejectClickActionWithoutCoords() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Click without coords")
                    .lastActionResult("none")
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.single("Click",
                            Action.builder().type(Action.ActionType.CLICK).build()))
                    .build();

            DecisionBundleSchema.ValidationResult result = DecisionBundleSchema.validate(bundle);

            assertFalse(result.isValid());
            assertTrue(result.getError().contains("缺少坐标"));
        }

        @Test
        @DisplayName("Should reject type action without text")
        void shouldRejectTypeActionWithoutText() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Type without text")
                    .lastActionResult("none")
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.single("Type",
                            Action.builder().type(Action.ActionType.TYPE).build()))
                    .build();

            DecisionBundleSchema.ValidationResult result = DecisionBundleSchema.validate(bundle);

            assertFalse(result.isValid());
            assertTrue(result.getError().contains("缺少文本"));
        }

        @Test
        @DisplayName("Should reject key action without key name")
        void shouldRejectKeyActionWithoutKeyName() {
            DecisionBundle bundle = DecisionBundle.builder()
                    .thought("Key without name")
                    .lastActionResult("none")
                    .isGoalComplete(false)
                    .executeNow(ExecuteNow.single("Press key",
                            Action.builder().type(Action.ActionType.KEY).build()))
                    .build();

            DecisionBundleSchema.ValidationResult result = DecisionBundleSchema.validate(bundle);

            assertFalse(result.isValid());
            assertTrue(result.getError().contains("缺少按键名称"));
        }
    }

    @Nested
    @DisplayName("JSON serialization/deserialization tests")
    class SerializationTests {

        @Test
        @DisplayName("Should correctly deserialize all action types")
        void shouldDeserializeAllActionTypes() throws JsonProcessingException {
            String json = """
                    {
                        "thought": "Testing all actions",
                        "last_action_result": "none",
                        "execute_now": {
                            "intent": "Test actions",
                            "actions": [
                                {"type": "click", "coords": [100, 200]},
                                {"type": "doubleClick", "coords": [300, 400]},
                                {"type": "rightClick", "coords": [500, 600]},
                                {"type": "type", "text": "hello"},
                                {"type": "key", "key": "enter"}
                            ]
                        },
                        "is_goal_complete": false
                    }
                    """;

            DecisionBundle bundle = DecisionBundleSchema.parse(json);
            List<Action> actions = bundle.getExecuteNow().getActions();

            assertEquals(5, actions.size());
            assertEquals(Action.ActionType.CLICK, actions.get(0).getType());
            assertEquals(Action.ActionType.DOUBLE_CLICK, actions.get(1).getType());
            assertEquals(Action.ActionType.RIGHT_CLICK, actions.get(2).getType());
            assertEquals(Action.ActionType.TYPE, actions.get(3).getType());
            assertEquals(Action.ActionType.KEY, actions.get(4).getType());
        }

        @Test
        @DisplayName("Should correctly deserialize scroll action")
        void shouldDeserializeScrollAction() throws JsonProcessingException {
            String json = """
                    {
                        "thought": "Scrolling",
                        "last_action_result": "none",
                        "execute_now": {
                            "intent": "Scroll down",
                            "actions": [
                                {"type": "scroll", "amount": 3}
                            ]
                        },
                        "is_goal_complete": false
                    }
                    """;

            DecisionBundle bundle = DecisionBundleSchema.parse(json);
            Action scrollAction = bundle.getExecuteNow().getFirstAction();

            assertEquals(Action.ActionType.SCROLL, scrollAction.getType());
            assertEquals(3, scrollAction.getAmount());
        }

        @Test
        @DisplayName("Should correctly deserialize drag action")
        void shouldDeserializeDragAction() throws JsonProcessingException {
            String json = """
                    {
                        "thought": "Dragging",
                        "last_action_result": "none",
                        "execute_now": {
                            "intent": "Drag element",
                            "actions": [
                                {"type": "drag", "coords": [100, 100], "to_coords": [200, 200]}
                            ]
                        },
                        "is_goal_complete": false
                    }
                    """;

            DecisionBundle bundle = DecisionBundleSchema.parse(json);
            Action dragAction = bundle.getExecuteNow().getFirstAction();

            assertEquals(Action.ActionType.DRAG, dragAction.getType());
            assertArrayEquals(new int[]{100, 100}, dragAction.getCoords());
            assertArrayEquals(new int[]{200, 200}, dragAction.getToCoords());
        }

        @Test
        @DisplayName("Should correctly deserialize wait action")
        void shouldDeserializeWaitAction() throws JsonProcessingException {
            String json = """
                    {
                        "thought": "Waiting",
                        "last_action_result": "none",
                        "execute_now": {
                            "intent": "Wait for load",
                            "actions": [
                                {"type": "wait", "duration": 1000}
                            ]
                        },
                        "is_goal_complete": false
                    }
                    """;

            DecisionBundle bundle = DecisionBundleSchema.parse(json);
            Action waitAction = bundle.getExecuteNow().getFirstAction();

            assertEquals(Action.ActionType.WAIT, waitAction.getType());
            assertEquals(1000, waitAction.getDuration());
        }
    }
}
