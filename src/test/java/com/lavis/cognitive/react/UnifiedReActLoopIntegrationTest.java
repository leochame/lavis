package com.lavis.cognitive.react;

import com.lavis.action.RobotDriver;
import com.lavis.cognitive.orchestrator.TaskOrchestrator;
import com.lavis.perception.ScreenCapturer;
import com.lavis.service.llm.LlmFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.Dimension;
import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 统一 ReAct 决策循环集成测试
 *
 * 测试 TaskOrchestrator.executeGoal() 的端到端流程
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unified ReAct Loop Integration Tests")
class UnifiedReActLoopIntegrationTest {

    @Mock
    private ScreenCapturer screenCapturer;

    @Mock
    private LocalExecutor localExecutor;

    @Mock
    private LlmFactory llmFactory;

    @Mock
    private ChatLanguageModel chatModel;

    @Mock
    private RobotDriver robotDriver;

    private TaskOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        // Create orchestrator with mocked dependencies
        orchestrator = new TaskOrchestrator();

        // Inject mocked dependencies
        ReflectionTestUtils.setField(orchestrator, "screenCapturer", screenCapturer);
        ReflectionTestUtils.setField(orchestrator, "localExecutor", localExecutor);
        ReflectionTestUtils.setField(orchestrator, "maxIterations", 10);
        ReflectionTestUtils.setField(orchestrator, "maxConsecutiveFailures", 3);

        // Setup screen capturer mock
        when(screenCapturer.getScreenSize()).thenReturn(new Dimension(1920, 1080));
        when(screenCapturer.toLogicalSafe(anyInt(), anyInt()))
                .thenAnswer(inv -> new Point(inv.getArgument(0), inv.getArgument(1)));
    }

    /**
     * Helper method to create a mock ChatResponse from JSON string
     */
    private ChatResponse mockChatResponse(String jsonResponse) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(jsonResponse))
                .build();
    }

    @Nested
    @DisplayName("Successful task completion")
    class SuccessfulCompletionTests {

        @Test
        @DisplayName("Should complete task when LLM returns goal complete")
        void shouldCompleteTaskWhenLlmReturnsGoalComplete() throws Exception {
            // Setup LLM to return goal complete on first call
            String llmResponse = """
                    {
                        "thought": "I can see the login was successful, the dashboard is now visible",
                        "last_action_result": "success",
                        "execute_now": null,
                        "is_goal_complete": true,
                        "completion_summary": "Successfully logged in and reached the dashboard"
                    }
                    """;

            when(llmFactory.getModel()).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenReturn(mockChatResponse(llmResponse));
            when(screenCapturer.captureScreenWithCursorAsBase64()).thenReturn("base64screenshot");

            TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal("Login to the app");

            assertTrue(result.isSuccess());
            assertTrue(result.getMessage().contains("任务完成"));
            assertTrue(result.getMessage().contains("Successfully logged in"));
        }

        @Test
        @DisplayName("Should execute actions and complete after multiple iterations")
        void shouldExecuteActionsAndCompleteAfterMultipleIterations() throws Exception {
            // First call: execute click action
            String firstResponse = """
                    {
                        "thought": "I see the login button, clicking it",
                        "last_action_result": "none",
                        "execute_now": {
                            "intent": "Click login button",
                            "actions": [{"type": "click", "coords": [500, 300]}]
                        },
                        "is_goal_complete": false
                    }
                    """;

            // Second call: goal complete
            String secondResponse = """
                    {
                        "thought": "Login successful, dashboard visible",
                        "last_action_result": "success",
                        "execute_now": null,
                        "is_goal_complete": true,
                        "completion_summary": "Login completed"
                    }
                    """;

            when(llmFactory.getModel()).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenReturn(mockChatResponse(firstResponse))
                    .thenReturn(mockChatResponse(secondResponse));
            when(screenCapturer.captureScreenWithCursorAsBase64()).thenReturn("base64screenshot");
            when(localExecutor.executeBatch(any(ExecuteNow.class)))
                    .thenReturn(new LocalExecutor.BatchExecutionResult(
                            "Click login button",
                            java.util.List.of(LocalExecutor.ActionResult.success(Action.click(500, 300), "Clicked")),
                            1, true, true));

            TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal("Login to the app");

            assertTrue(result.isSuccess());
            verify(localExecutor, times(1)).executeBatch(any(ExecuteNow.class));
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should fail when LLM model is not available")
        void shouldFailWhenLlmModelNotAvailable() {
            when(llmFactory.getModel()).thenReturn(null);

            TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal("Test goal");

            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().contains("无法获取 LLM 模型"));
        }

        @Test
        @DisplayName("Should handle LLM parsing errors gracefully")
        void shouldHandleLlmParsingErrorsGracefully() throws Exception {
            // Return invalid JSON multiple times to trigger consecutive failures
            String invalidResponse = "This is not valid JSON";

            when(llmFactory.getModel()).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenReturn(mockChatResponse(invalidResponse));
            when(screenCapturer.captureScreenWithCursorAsBase64()).thenReturn("base64screenshot");

            TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal("Test goal");

            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().contains("连续失败次数过多"));
        }

        @Test
        @DisplayName("Should handle screenshot capture failure")
        void shouldHandleScreenshotCaptureFailure() throws Exception {
            // First call fails, second succeeds with goal complete
            String successResponse = """
                    {
                        "thought": "Done",
                        "last_action_result": "none",
                        "execute_now": null,
                        "is_goal_complete": true,
                        "completion_summary": "Completed"
                    }
                    """;

            when(llmFactory.getModel()).thenReturn(chatModel);
            when(screenCapturer.captureScreenWithCursorAsBase64())
                    .thenThrow(new RuntimeException("Screenshot failed"))
                    .thenReturn("base64screenshot");
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenReturn(mockChatResponse(successResponse));

            TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal("Test goal");

            // Should recover and complete
            assertTrue(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("Iteration limits")
    class IterationLimitTests {

        @Test
        @DisplayName("Should stop at max iterations")
        void shouldStopAtMaxIterations() throws Exception {
            // Always return actions, never complete
            String neverCompleteResponse = """
                    {
                        "thought": "Still working",
                        "last_action_result": "success",
                        "execute_now": {
                            "intent": "Keep trying",
                            "actions": [{"type": "wait", "duration": 100}]
                        },
                        "is_goal_complete": false
                    }
                    """;

            when(llmFactory.getModel()).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenReturn(mockChatResponse(neverCompleteResponse));
            when(screenCapturer.captureScreenWithCursorAsBase64()).thenReturn("base64screenshot");
            when(localExecutor.executeBatch(any(ExecuteNow.class)))
                    .thenReturn(new LocalExecutor.BatchExecutionResult(
                            "Wait", java.util.List.of(), 1, false, true));

            TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal("Infinite task");

            assertFalse(result.isSuccess());
            assertTrue(result.isPartial());
            assertTrue(result.getMessage().contains("最大迭代次数"));
        }
    }

    @Nested
    @DisplayName("Action execution")
    class ActionExecutionTests {

        @Test
        @DisplayName("Should execute batch actions correctly")
        void shouldExecuteBatchActionsCorrectly() throws Exception {
            String batchActionResponse = """
                    {
                        "thought": "Filling login form",
                        "last_action_result": "none",
                        "execute_now": {
                            "intent": "Fill login form",
                            "actions": [
                                {"type": "type", "text": "admin"},
                                {"type": "key", "key": "tab"},
                                {"type": "type", "text": "password"}
                            ]
                        },
                        "is_goal_complete": false
                    }
                    """;

            String completeResponse = """
                    {
                        "thought": "Form filled",
                        "last_action_result": "success",
                        "execute_now": null,
                        "is_goal_complete": true,
                        "completion_summary": "Form filled successfully"
                    }
                    """;

            when(llmFactory.getModel()).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenReturn(mockChatResponse(batchActionResponse))
                    .thenReturn(mockChatResponse(completeResponse));
            when(screenCapturer.captureScreenWithCursorAsBase64()).thenReturn("base64screenshot");
            when(localExecutor.executeBatch(any(ExecuteNow.class)))
                    .thenReturn(new LocalExecutor.BatchExecutionResult(
                            "Fill login form",
                            java.util.List.of(
                                    LocalExecutor.ActionResult.success(Action.type("admin"), "Typed"),
                                    LocalExecutor.ActionResult.success(Action.key("tab"), "Tab pressed"),
                                    LocalExecutor.ActionResult.success(Action.type("password"), "Typed")),
                            3, false, true));

            TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal("Login");

            assertTrue(result.isSuccess());
            verify(localExecutor, times(1)).executeBatch(argThat(executeNow ->
                    executeNow.getActionCount() == 3 &&
                            executeNow.getIntent().equals("Fill login form")));
        }

        @Test
        @DisplayName("Should handle partial action execution failure")
        void shouldHandlePartialActionExecutionFailure() throws Exception {
            String actionResponse = """
                    {
                        "thought": "Clicking button",
                        "last_action_result": "none",
                        "execute_now": {
                            "intent": "Click button",
                            "actions": [{"type": "click", "coords": [500, 300]}]
                        },
                        "is_goal_complete": false
                    }
                    """;

            String retryResponse = """
                    {
                        "thought": "Retrying click",
                        "last_action_result": "failed",
                        "execute_now": {
                            "intent": "Retry click",
                            "actions": [{"type": "click", "coords": [510, 310]}]
                        },
                        "is_goal_complete": false
                    }
                    """;

            String completeResponse = """
                    {
                        "thought": "Success",
                        "last_action_result": "success",
                        "execute_now": null,
                        "is_goal_complete": true,
                        "completion_summary": "Done"
                    }
                    """;

            when(llmFactory.getModel()).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenReturn(mockChatResponse(actionResponse))
                    .thenReturn(mockChatResponse(retryResponse))
                    .thenReturn(mockChatResponse(completeResponse));
            when(screenCapturer.captureScreenWithCursorAsBase64()).thenReturn("base64screenshot");
            when(localExecutor.executeBatch(any(ExecuteNow.class)))
                    .thenReturn(new LocalExecutor.BatchExecutionResult(
                            "Click", java.util.List.of(
                            LocalExecutor.ActionResult.failed(Action.click(500, 300), "Missed")),
                            1, true, false))
                    .thenReturn(new LocalExecutor.BatchExecutionResult(
                            "Retry", java.util.List.of(
                            LocalExecutor.ActionResult.success(Action.click(510, 310), "Clicked")),
                            1, true, true));

            TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal("Click task");

            assertTrue(result.isSuccess());
            verify(localExecutor, times(2)).executeBatch(any(ExecuteNow.class));
        }
    }

    @Nested
    @DisplayName("Validation tests")
    class ValidationTests {

        @Test
        @DisplayName("Should reject invalid decision bundle")
        void shouldRejectInvalidDecisionBundle() throws Exception {
            // Missing required fields
            String invalidResponse = """
                    {
                        "thought": "",
                        "is_goal_complete": false
                    }
                    """;

            String validResponse = """
                    {
                        "thought": "Valid thought",
                        "last_action_result": "none",
                        "execute_now": null,
                        "is_goal_complete": true,
                        "completion_summary": "Done"
                    }
                    """;

            when(llmFactory.getModel()).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenReturn(mockChatResponse(invalidResponse))
                    .thenReturn(mockChatResponse(validResponse));
            when(screenCapturer.captureScreenWithCursorAsBase64()).thenReturn("base64screenshot");

            TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal("Test");

            // Should eventually succeed after validation failure
            assertTrue(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("Interrupt handling")
    class InterruptTests {

        @Test
        @DisplayName("Should handle interrupt during execution")
        void shouldHandleInterruptDuringExecution() throws Exception {
            String actionResponse = """
                    {
                        "thought": "Working",
                        "last_action_result": "none",
                        "execute_now": {
                            "intent": "Do something",
                            "actions": [{"type": "wait", "duration": 1000}]
                        },
                        "is_goal_complete": false
                    }
                    """;

            when(llmFactory.getModel()).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenReturn(mockChatResponse(actionResponse));
            when(screenCapturer.captureScreenWithCursorAsBase64()).thenReturn("base64screenshot");
            when(localExecutor.executeBatch(any(ExecuteNow.class)))
                    .thenAnswer(inv -> {
                        // Simulate interrupt during execution
                        orchestrator.interrupt();
                        return new LocalExecutor.BatchExecutionResult(
                                "Wait", java.util.List.of(), 1, false, true);
                    });

            TaskOrchestrator.OrchestratorResult result = orchestrator.executeGoal("Interruptible task");

            assertFalse(result.isSuccess());
            assertTrue(result.isPartial());
            assertTrue(result.getMessage().contains("中断"));
        }
    }
}
