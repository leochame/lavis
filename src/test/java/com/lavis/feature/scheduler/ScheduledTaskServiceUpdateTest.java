package com.lavis.feature.scheduler;

import com.lavis.infra.persistence.entity.ScheduledTaskEntity;
import com.lavis.infra.persistence.repository.ScheduledTaskRepository;
import com.lavis.infra.persistence.repository.TaskRunLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskServiceUpdateTest {

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private ScheduledTaskRepository scheduledTaskRepository;

    @Mock
    private TaskRunLogRepository taskRunLogRepository;

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private TaskFileLoader taskFileLoader;

    private ScheduledTaskService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledTaskService(
                taskScheduler,
                scheduledTaskRepository,
                taskRunLogRepository,
                taskExecutor,
                taskFileLoader,
                new TaskRules()
        );

        when(scheduledTaskRepository.save(any(ScheduledTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void updateTask_shouldAllowClearingDescriptionWithEmptyString() {
        ScheduledTaskEntity existing = manualTask("task-1");
        existing.setDescription("old description");
        when(scheduledTaskRepository.findById("task-1")).thenReturn(Optional.of(existing));

        TaskRequest request = new TaskRequest();
        request.setDescription("");

        ScheduledTaskEntity updated = service.updateTask("task-1", request);

        assertEquals("", updated.getDescription());

        ArgumentCaptor<ScheduledTaskEntity> captor = ArgumentCaptor.forClass(ScheduledTaskEntity.class);
        verify(scheduledTaskRepository).save(captor.capture());
        assertEquals("", captor.getValue().getDescription());
    }

    @Test
    void updateTask_shouldAllowClearingRequestContentWhenSwitchingToCommandMode() {
        ScheduledTaskEntity existing = manualTask("task-2");
        existing.setExecutionMode(TaskRules.EXECUTION_MODE_REQUEST);
        existing.setCommand("request: old");
        existing.setRequestContent("old content");
        when(scheduledTaskRepository.findById("task-2")).thenReturn(Optional.of(existing));

        TaskRequest request = new TaskRequest();
        request.setExecutionMode(TaskRules.EXECUTION_MODE_COMMAND);
        request.setCommand("echo new");
        request.setRequestContent("");

        ScheduledTaskEntity updated = service.updateTask("task-2", request);

        assertEquals(TaskRules.EXECUTION_MODE_COMMAND, updated.getExecutionMode());
        assertEquals("echo new", updated.getCommand());
        assertEquals("", updated.getRequestContent());
    }

    private ScheduledTaskEntity manualTask(String id) {
        ScheduledTaskEntity task = new ScheduledTaskEntity();
        task.setId(id);
        task.setSourceType(TaskRules.SOURCE_TYPE_MANUAL);
        task.setName("demo");
        task.setScheduleMode(TaskRules.SCHEDULE_MODE_CRON);
        task.setCronExpression("0 0 9 * * *");
        task.setExecutionMode(TaskRules.EXECUTION_MODE_COMMAND);
        task.setCommand("echo ok");
        task.setEnabled(true);
        task.setRunCount(0);
        task.setPenaltyPoints(0);
        task.setAutoPaused(false);
        return task;
    }
}
