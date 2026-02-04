package com.lavis.cognitive.react;

import com.lavis.action.RobotDriver;
import com.lavis.perception.ScreenCapturer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * LocalExecutor 类单元测试
 *
 * 测试本地执行器的各种功能
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LocalExecutor Tests")
class LocalExecutorTest {

    @Mock
    private RobotDriver robotDriver;

    @Mock
    private ScreenCapturer screenCapturer;

    private LocalExecutor localExecutor;

    @BeforeEach
    void setUp() {
        localExecutor = new LocalExecutor(robotDriver, screenCapturer);
        // Mock coordinate conversion
        when(screenCapturer.toLogicalSafe(anyInt(), anyInt()))
                .thenAnswer(invocation -> new Point(invocation.getArgument(0), invocation.getArgument(1)));
    }

    @Nested
    @DisplayName("executeBatch() method tests")
    class ExecuteBatchTests {

        @Test
        @DisplayName("Should return empty result for null executeNow")
        void shouldReturnEmptyResultForNullExecuteNow() {
            LocalExecutor.BatchExecutionResult result = localExecutor.executeBatch(null);

            assertNotNull(result);
            assertFalse(result.hasResults());
            assertEquals(0, result.getExecutedCount());
        }

        @Test
        @DisplayName("Should return empty result for executeNow without actions")
        void shouldReturnEmptyResultForExecuteNowWithoutActions() {
            ExecuteNow executeNow = ExecuteNow.builder()
                    .intent("Empty")
                    .actions(List.of())
                    .build();

            LocalExecutor.BatchExecutionResult result = localExecutor.executeBatch(executeNow);

            assertFalse(result.hasResults());
        }

        @Test
        @DisplayName("Should execute single action successfully")
        void shouldExecuteSingleActionSuccessfully() {
            ExecuteNow executeNow = ExecuteNow.single("Click button", Action.click(500, 300));

            LocalExecutor.BatchExecutionResult result = localExecutor.executeBatch(executeNow);

            assertTrue(result.hasResults());
            assertEquals(1, result.getExecutedCount());
            assertTrue(result.isAllSuccess());
            verify(robotDriver).clickAt(500, 300);
        }

        @Test
        @DisplayName("Should execute multiple non-boundary actions")
        void shouldExecuteMultipleNonBoundaryActions() {
            ExecuteNow executeNow = ExecuteNow.batch("Fill form",
                    Action.type("username"),
                    Action.key("tab"),
                    Action.type("password"));

            LocalExecutor.BatchExecutionResult result = localExecutor.executeBatch(executeNow);

            assertEquals(3, result.getExecutedCount());
            assertTrue(result.isAllSuccess());
            assertFalse(result.isHitBoundary());
            verify(robotDriver).type("username");
            verify(robotDriver).pressKeys(java.awt.event.KeyEvent.VK_TAB);
            verify(robotDriver).type("password");
        }

        @Test
        @DisplayName("Should stop at boundary action")
        void shouldStopAtBoundaryAction() {
            ExecuteNow executeNow = ExecuteNow.batch("Click and type",
                    Action.click(100, 100),
                    Action.type("text"),
                    Action.key("enter"));

            LocalExecutor.BatchExecutionResult result = localExecutor.executeBatch(executeNow);

            // Should stop after click (boundary action)
            assertEquals(1, result.getExecutedCount());
            assertTrue(result.isHitBoundary());
            verify(robotDriver).clickAt(100, 100);
            verify(robotDriver, never()).type(anyString());
        }

        @Test
        @DisplayName("Should stop at enter key (boundary)")
        void shouldStopAtEnterKey() {
            ExecuteNow executeNow = ExecuteNow.batch("Type and submit",
                    Action.type("text"),
                    Action.key("enter"),
                    Action.type("more text"));

            LocalExecutor.BatchExecutionResult result = localExecutor.executeBatch(executeNow);

            assertEquals(2, result.getExecutedCount());
            assertTrue(result.isHitBoundary());
            verify(robotDriver).type("text");
            verify(robotDriver).pressKeys(java.awt.event.KeyEvent.VK_ENTER);
            verify(robotDriver, never()).type("more text");
        }

        @Test
        @DisplayName("Should handle execution exception")
        void shouldHandleExecutionException() {
            doThrow(new RuntimeException("Robot error")).when(robotDriver).clickAt(anyInt(), anyInt());

            ExecuteNow executeNow = ExecuteNow.single("Click", Action.click(100, 100));

            LocalExecutor.BatchExecutionResult result = localExecutor.executeBatch(executeNow);

            assertEquals(1, result.getExecutedCount());
            assertFalse(result.isAllSuccess());
            assertEquals(1, result.getFailureCount());
        }
    }

    @Nested
    @DisplayName("executeAction() method tests")
    class ExecuteActionTests {

        @Test
        @DisplayName("Should execute click action")
        void shouldExecuteClickAction() {
            Action action = Action.click(500, 300);

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertTrue(result.isSuccess());
            verify(robotDriver).clickAt(500, 300);
            verify(screenCapturer).recordClickPosition(500, 300);
        }

        @Test
        @DisplayName("Should execute double click action")
        void shouldExecuteDoubleClickAction() {
            Action action = Action.doubleClick(200, 400);

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertTrue(result.isSuccess());
            verify(robotDriver).doubleClickAt(200, 400);
        }

        @Test
        @DisplayName("Should execute right click action")
        void shouldExecuteRightClickAction() {
            Action action = Action.rightClick(300, 500);

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertTrue(result.isSuccess());
            verify(robotDriver).rightClickAt(300, 500);
        }

        @Test
        @DisplayName("Should execute type action")
        void shouldExecuteTypeAction() {
            Action action = Action.type("hello world");

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertTrue(result.isSuccess());
            verify(robotDriver).type("hello world");
        }

        @Test
        @DisplayName("Should execute key action - enter")
        void shouldExecuteKeyActionEnter() {
            Action action = Action.key("enter");

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertTrue(result.isSuccess());
            verify(robotDriver).pressKeys(java.awt.event.KeyEvent.VK_ENTER);
        }

        @Test
        @DisplayName("Should execute key action - tab")
        void shouldExecuteKeyActionTab() {
            Action action = Action.key("tab");

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertTrue(result.isSuccess());
            verify(robotDriver).pressKeys(java.awt.event.KeyEvent.VK_TAB);
        }

        @Test
        @DisplayName("Should execute key action - escape")
        void shouldExecuteKeyActionEscape() {
            Action action = Action.key("escape");

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertTrue(result.isSuccess());
            verify(robotDriver).pressKeys(java.awt.event.KeyEvent.VK_ESCAPE);
        }

        @Test
        @DisplayName("Should execute key action - backspace")
        void shouldExecuteKeyActionBackspace() {
            Action action = Action.key("backspace");

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertTrue(result.isSuccess());
            verify(robotDriver).pressKeys(java.awt.event.KeyEvent.VK_BACK_SPACE);
        }

        @Test
        @DisplayName("Should execute scroll action")
        void shouldExecuteScrollAction() {
            Action action = Action.scroll(5);

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertTrue(result.isSuccess());
            verify(robotDriver).scroll(5);
        }

        @Test
        @DisplayName("Should execute drag action")
        void shouldExecuteDragAction() {
            Action action = Action.drag(100, 100, 500, 500);

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertTrue(result.isSuccess());
            verify(robotDriver).drag(100, 100, 500, 500);
        }

        @Test
        @DisplayName("Should execute wait action")
        void shouldExecuteWaitAction() {
            Action action = Action.wait(100);

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertTrue(result.isSuccess());
            assertTrue(result.getMessage().contains("100ms"));
        }

        @Test
        @DisplayName("Should fail for null action")
        void shouldFailForNullAction() {
            LocalExecutor.ActionResult result = localExecutor.executeAction(null);

            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().contains("无效"));
        }

        @Test
        @DisplayName("Should fail for action without type")
        void shouldFailForActionWithoutType() {
            Action action = Action.builder().build();

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should fail for click without coords")
        void shouldFailForClickWithoutCoords() {
            Action action = Action.builder().type(Action.ActionType.CLICK).build();

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().contains("坐标无效"));
        }

        @Test
        @DisplayName("Should fail for type without text")
        void shouldFailForTypeWithoutText() {
            Action action = Action.builder().type(Action.ActionType.TYPE).build();

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().contains("文本为空"));
        }

        @Test
        @DisplayName("Should fail for key without key name")
        void shouldFailForKeyWithoutKeyName() {
            Action action = Action.builder().type(Action.ActionType.KEY).build();

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().contains("按键名称为空"));
        }

        @Test
        @DisplayName("Should fail for unsupported key")
        void shouldFailForUnsupportedKey() {
            Action action = Action.key("unknownkey123");

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().contains("不支持的按键"));
        }

        @Test
        @DisplayName("Should fail for scroll without amount")
        void shouldFailForScrollWithoutAmount() {
            Action action = Action.builder().type(Action.ActionType.SCROLL).build();

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().contains("滚动量为空"));
        }

        @Test
        @DisplayName("Should fail for drag without coords")
        void shouldFailForDragWithoutCoords() {
            Action action = Action.builder().type(Action.ActionType.DRAG).build();

            LocalExecutor.ActionResult result = localExecutor.executeAction(action);

            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().contains("拖拽坐标无效"));
        }
    }

    @Nested
    @DisplayName("ActionResult tests")
    class ActionResultTests {

        @Test
        @DisplayName("success() should create successful result")
        void successShouldCreateSuccessfulResult() {
            Action action = Action.click(100, 100);
            LocalExecutor.ActionResult result = LocalExecutor.ActionResult.success(action, "Clicked");

            assertTrue(result.isSuccess());
            assertEquals("Clicked", result.getMessage());
            assertEquals(action, result.getAction());
        }

        @Test
        @DisplayName("failed() should create failed result")
        void failedShouldCreateFailedResult() {
            Action action = Action.click(100, 100);
            LocalExecutor.ActionResult result = LocalExecutor.ActionResult.failed(action, "Error");

            assertFalse(result.isSuccess());
            assertEquals("Error", result.getMessage());
        }

        @Test
        @DisplayName("toString() should format correctly")
        void toStringShouldFormatCorrectly() {
            Action action = Action.click(100, 100);
            LocalExecutor.ActionResult success = LocalExecutor.ActionResult.success(action, "Done");
            LocalExecutor.ActionResult failure = LocalExecutor.ActionResult.failed(action, "Error");

            assertTrue(success.toString().contains("✅"));
            assertTrue(failure.toString().contains("❌"));
        }
    }

    @Nested
    @DisplayName("BatchExecutionResult tests")
    class BatchExecutionResultTests {

        @Test
        @DisplayName("empty() should create empty result")
        void emptyShouldCreateEmptyResult() {
            LocalExecutor.BatchExecutionResult result = LocalExecutor.BatchExecutionResult.empty();

            assertFalse(result.hasResults());
            assertEquals(0, result.getExecutedCount());
            assertTrue(result.isAllSuccess());
            assertFalse(result.isHitBoundary());
        }

        @Test
        @DisplayName("getSuccessCount() should return correct count")
        void getSuccessCountShouldReturnCorrectCount() {
            List<LocalExecutor.ActionResult> results = List.of(
                    LocalExecutor.ActionResult.success(Action.click(100, 100), "OK"),
                    LocalExecutor.ActionResult.success(Action.type("text"), "OK"),
                    LocalExecutor.ActionResult.failed(Action.key("enter"), "Error"));

            LocalExecutor.BatchExecutionResult batchResult = new LocalExecutor.BatchExecutionResult(
                    "Test", results, 3, false, false);

            assertEquals(2, batchResult.getSuccessCount());
            assertEquals(1, batchResult.getFailureCount());
        }

        @Test
        @DisplayName("getSummary() should include all information")
        void getSummaryShouldIncludeAllInformation() {
            List<LocalExecutor.ActionResult> results = List.of(
                    LocalExecutor.ActionResult.success(Action.click(100, 100), "Clicked"));

            LocalExecutor.BatchExecutionResult batchResult = new LocalExecutor.BatchExecutionResult(
                    "Click button", results, 1, true, true);

            String summary = batchResult.getSummary();

            assertTrue(summary.contains("Click button"));
            assertTrue(summary.contains("Executed: 1/1"));
            assertTrue(summary.contains("Success: 1"));
            assertTrue(summary.contains("Paused at boundary"));
        }

        @Test
        @DisplayName("toString() should format correctly")
        void toStringShouldFormatCorrectly() {
            LocalExecutor.BatchExecutionResult result = new LocalExecutor.BatchExecutionResult(
                    "Test", List.of(), 0, false, true);

            String str = result.toString();
            assertTrue(str.contains("BatchResult"));
            assertTrue(str.contains("Test"));
        }
    }
}
