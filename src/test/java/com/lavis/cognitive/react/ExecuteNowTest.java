package com.lavis.cognitive.react;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExecuteNow 类单元测试
 *
 * 测试动作集合类的各种功能
 */
@DisplayName("ExecuteNow Tests")
class ExecuteNowTest {

    @Nested
    @DisplayName("hasActions() method")
    class HasActionsTests {

        @Test
        @DisplayName("Should return true when actions list is not empty")
        void shouldReturnTrueWhenActionsNotEmpty() {
            ExecuteNow executeNow = ExecuteNow.single("Click", Action.click(100, 100));
            assertTrue(executeNow.hasActions());
        }

        @Test
        @DisplayName("Should return false when actions list is empty")
        void shouldReturnFalseWhenActionsEmpty() {
            ExecuteNow executeNow = ExecuteNow.builder()
                    .intent("Empty")
                    .actions(List.of())
                    .build();
            assertFalse(executeNow.hasActions());
        }

        @Test
        @DisplayName("Should return false when actions list is null")
        void shouldReturnFalseWhenActionsNull() {
            ExecuteNow executeNow = ExecuteNow.builder()
                    .intent("Null actions")
                    .actions(null)
                    .build();
            assertFalse(executeNow.hasActions());
        }
    }

    @Nested
    @DisplayName("getActionCount() method")
    class GetActionCountTests {

        @Test
        @DisplayName("Should return correct count for single action")
        void shouldReturnCorrectCountForSingleAction() {
            ExecuteNow executeNow = ExecuteNow.single("Click", Action.click(100, 100));
            assertEquals(1, executeNow.getActionCount());
        }

        @Test
        @DisplayName("Should return correct count for multiple actions")
        void shouldReturnCorrectCountForMultipleActions() {
            ExecuteNow executeNow = ExecuteNow.batch("Fill form",
                    Action.click(100, 100),
                    Action.type("username"),
                    Action.key("tab"),
                    Action.type("password"));
            assertEquals(4, executeNow.getActionCount());
        }

        @Test
        @DisplayName("Should return 0 for empty actions")
        void shouldReturnZeroForEmptyActions() {
            ExecuteNow executeNow = ExecuteNow.builder()
                    .intent("Empty")
                    .actions(List.of())
                    .build();
            assertEquals(0, executeNow.getActionCount());
        }

        @Test
        @DisplayName("Should return 0 for null actions")
        void shouldReturnZeroForNullActions() {
            ExecuteNow executeNow = ExecuteNow.builder()
                    .intent("Null")
                    .actions(null)
                    .build();
            assertEquals(0, executeNow.getActionCount());
        }
    }

    @Nested
    @DisplayName("getFirstAction() method")
    class GetFirstActionTests {

        @Test
        @DisplayName("Should return first action")
        void shouldReturnFirstAction() {
            Action firstAction = Action.click(100, 100);
            ExecuteNow executeNow = ExecuteNow.batch("Test",
                    firstAction,
                    Action.type("text"));

            assertEquals(firstAction, executeNow.getFirstAction());
        }

        @Test
        @DisplayName("Should return null when no actions")
        void shouldReturnNullWhenNoActions() {
            ExecuteNow executeNow = ExecuteNow.builder()
                    .intent("Empty")
                    .actions(List.of())
                    .build();
            assertNull(executeNow.getFirstAction());
        }
    }

    @Nested
    @DisplayName("getLastAction() method")
    class GetLastActionTests {

        @Test
        @DisplayName("Should return last action")
        void shouldReturnLastAction() {
            Action lastAction = Action.key("enter");
            ExecuteNow executeNow = ExecuteNow.batch("Test",
                    Action.click(100, 100),
                    Action.type("text"),
                    lastAction);

            assertEquals(lastAction, executeNow.getLastAction());
        }

        @Test
        @DisplayName("Should return null when no actions")
        void shouldReturnNullWhenNoActions() {
            ExecuteNow executeNow = ExecuteNow.builder()
                    .intent("Empty")
                    .actions(List.of())
                    .build();
            assertNull(executeNow.getLastAction());
        }

        @Test
        @DisplayName("Should return same action for single action list")
        void shouldReturnSameActionForSingleActionList() {
            Action action = Action.click(100, 100);
            ExecuteNow executeNow = ExecuteNow.single("Click", action);

            assertEquals(action, executeNow.getFirstAction());
            assertEquals(action, executeNow.getLastAction());
        }
    }

    @Nested
    @DisplayName("hasBoundaryAction() method")
    class HasBoundaryActionTests {

        @Test
        @DisplayName("Should return true when contains click")
        void shouldReturnTrueWhenContainsClick() {
            ExecuteNow executeNow = ExecuteNow.batch("Test",
                    Action.type("text"),
                    Action.click(100, 100));
            assertTrue(executeNow.hasBoundaryAction());
        }

        @Test
        @DisplayName("Should return true when contains enter key")
        void shouldReturnTrueWhenContainsEnterKey() {
            ExecuteNow executeNow = ExecuteNow.batch("Test",
                    Action.type("text"),
                    Action.key("enter"));
            assertTrue(executeNow.hasBoundaryAction());
        }

        @Test
        @DisplayName("Should return true when contains scroll")
        void shouldReturnTrueWhenContainsScroll() {
            ExecuteNow executeNow = ExecuteNow.single("Scroll", Action.scroll(3));
            assertTrue(executeNow.hasBoundaryAction());
        }

        @Test
        @DisplayName("Should return false when no boundary actions")
        void shouldReturnFalseWhenNoBoundaryActions() {
            ExecuteNow executeNow = ExecuteNow.batch("Type only",
                    Action.type("username"),
                    Action.key("tab"),
                    Action.type("password"));
            assertFalse(executeNow.hasBoundaryAction());
        }

        @Test
        @DisplayName("Should return false when no actions")
        void shouldReturnFalseWhenNoActions() {
            ExecuteNow executeNow = ExecuteNow.builder()
                    .intent("Empty")
                    .actions(List.of())
                    .build();
            assertFalse(executeNow.hasBoundaryAction());
        }
    }

    @Nested
    @DisplayName("getFirstBoundaryIndex() method")
    class GetFirstBoundaryIndexTests {

        @Test
        @DisplayName("Should return index of first boundary action")
        void shouldReturnIndexOfFirstBoundaryAction() {
            ExecuteNow executeNow = ExecuteNow.batch("Test",
                    Action.type("text"),
                    Action.key("tab"),
                    Action.click(100, 100),
                    Action.type("more"));

            assertEquals(2, executeNow.getFirstBoundaryIndex());
        }

        @Test
        @DisplayName("Should return 0 when first action is boundary")
        void shouldReturnZeroWhenFirstActionIsBoundary() {
            ExecuteNow executeNow = ExecuteNow.batch("Test",
                    Action.click(100, 100),
                    Action.type("text"));

            assertEquals(0, executeNow.getFirstBoundaryIndex());
        }

        @Test
        @DisplayName("Should return -1 when no boundary actions")
        void shouldReturnMinusOneWhenNoBoundaryActions() {
            ExecuteNow executeNow = ExecuteNow.batch("Type only",
                    Action.type("text"),
                    Action.key("tab"));

            assertEquals(-1, executeNow.getFirstBoundaryIndex());
        }

        @Test
        @DisplayName("Should return -1 when no actions")
        void shouldReturnMinusOneWhenNoActions() {
            ExecuteNow executeNow = ExecuteNow.builder()
                    .intent("Empty")
                    .actions(List.of())
                    .build();

            assertEquals(-1, executeNow.getFirstBoundaryIndex());
        }
    }

    @Nested
    @DisplayName("getActionDescriptions() method")
    class GetActionDescriptionsTests {

        @Test
        @DisplayName("Should return list of descriptions")
        void shouldReturnListOfDescriptions() {
            ExecuteNow executeNow = ExecuteNow.batch("Test",
                    Action.click(100, 200),
                    Action.type("hello"));

            List<String> descriptions = executeNow.getActionDescriptions();

            assertEquals(2, descriptions.size());
            assertEquals("click(100,200)", descriptions.get(0));
            assertEquals("type(\"hello\")", descriptions.get(1));
        }

        @Test
        @DisplayName("Should return empty list when no actions")
        void shouldReturnEmptyListWhenNoActions() {
            ExecuteNow executeNow = ExecuteNow.builder()
                    .intent("Empty")
                    .actions(List.of())
                    .build();

            assertTrue(executeNow.getActionDescriptions().isEmpty());
        }
    }

    @Nested
    @DisplayName("getSummary() method")
    class GetSummaryTests {

        @Test
        @DisplayName("Should return formatted summary")
        void shouldReturnFormattedSummary() {
            ExecuteNow executeNow = ExecuteNow.batch("Fill login form",
                    Action.type("admin"),
                    Action.key("tab"),
                    Action.type("password"));

            String summary = executeNow.getSummary();

            assertTrue(summary.contains("Fill login form"));
            assertTrue(summary.contains("3 个动作"));
            assertTrue(summary.contains("type(\"admin\")"));
        }

        @Test
        @DisplayName("Should return no actions message when empty")
        void shouldReturnNoActionsMessageWhenEmpty() {
            ExecuteNow executeNow = ExecuteNow.builder()
                    .intent("Empty")
                    .actions(List.of())
                    .build();

            assertEquals("无动作", executeNow.getSummary());
        }

        @Test
        @DisplayName("Should handle null intent")
        void shouldHandleNullIntent() {
            ExecuteNow executeNow = ExecuteNow.builder()
                    .actions(List.of(Action.click(100, 100)))
                    .build();

            String summary = executeNow.getSummary();
            assertTrue(summary.contains("未知意图"));
        }
    }

    @Nested
    @DisplayName("Static factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("single() should create ExecuteNow with one action")
        void singleShouldCreateExecuteNowWithOneAction() {
            Action action = Action.click(500, 300);
            ExecuteNow executeNow = ExecuteNow.single("Click button", action);

            assertEquals("Click button", executeNow.getIntent());
            assertEquals(1, executeNow.getActionCount());
            assertEquals(action, executeNow.getFirstAction());
        }

        @Test
        @DisplayName("batch() with List should create ExecuteNow with multiple actions")
        void batchWithListShouldCreateExecuteNow() {
            List<Action> actions = List.of(
                    Action.click(100, 100),
                    Action.type("text"),
                    Action.key("enter"));

            ExecuteNow executeNow = ExecuteNow.batch("Submit form", actions);

            assertEquals("Submit form", executeNow.getIntent());
            assertEquals(3, executeNow.getActionCount());
        }

        @Test
        @DisplayName("batch() with varargs should create ExecuteNow with multiple actions")
        void batchWithVarargsShouldCreateExecuteNow() {
            ExecuteNow executeNow = ExecuteNow.batch("Fill form",
                    Action.click(100, 100),
                    Action.type("username"),
                    Action.key("tab"),
                    Action.type("password"),
                    Action.key("enter"));

            assertEquals("Fill form", executeNow.getIntent());
            assertEquals(5, executeNow.getActionCount());
        }
    }

    @Nested
    @DisplayName("toString() method")
    class ToStringTests {

        @Test
        @DisplayName("toString should return summary")
        void toStringShouldReturnSummary() {
            ExecuteNow executeNow = ExecuteNow.single("Click", Action.click(100, 100));
            assertEquals(executeNow.getSummary(), executeNow.toString());
        }
    }
}
