package com.lavis.scheduler;

import com.lavis.entity.ScheduledTaskEntity;
import com.lavis.entity.TaskRunLogEntity;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class ScheduledTaskService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledTaskService.class);

    private final TaskScheduler taskScheduler;
    private final TaskStore taskStore;
    private final TaskExecutor taskExecutor;
    private final Map<String, ScheduledFuture<?>> runningTasks = new ConcurrentHashMap<>();

    public ScheduledTaskService(TaskScheduler taskScheduler,
                                TaskStore taskStore,
                                TaskExecutor taskExecutor) {
        this.taskScheduler = taskScheduler;
        this.taskStore = taskStore;
        this.taskExecutor = taskExecutor;
    }

    @PostConstruct
    public void init() {
        logger.info("Initializing ScheduledTaskService...");
        loadAndScheduleAllTasks();
        logger.info("ScheduledTaskService initialized with {} running tasks", runningTasks.size());
    }

    public ScheduledTaskEntity createTask(CreateTaskRequest request) {
        logger.info("Creating task: {}", request.getName());

        if (!isValidCronExpression(request.getCronExpression())) {
            throw new IllegalArgumentException("Invalid cron expression: " + request.getCronExpression());
        }

        ScheduledTaskEntity task = new ScheduledTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setName(request.getName());
        task.setDescription(request.getDescription());
        task.setCronExpression(request.getCronExpression());
        task.setCommand(request.getCommand());
        task.setEnabled(request.isEnabled());
        task.setCreatedAt(LocalDateTime.now());
        task.setRunCount(0);

        task = taskStore.saveTask(task);

        if (task.getEnabled()) {
            scheduleTask(task);
        }

        logger.info("Task created: {} ({})", task.getName(), task.getId());
        return task;
    }

    public ScheduledTaskEntity updateTask(String taskId, UpdateTaskRequest request) {
        logger.info("Updating task: {}", taskId);

        ScheduledTaskEntity task = taskStore.getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        boolean needsReschedule = false;

        if (request.getName() != null) {
            task.setName(request.getName());
        }
        if (request.getDescription() != null) {
            task.setDescription(request.getDescription());
        }
        if (request.getCronExpression() != null) {
            if (!isValidCronExpression(request.getCronExpression())) {
                throw new IllegalArgumentException("Invalid cron expression: " + request.getCronExpression());
            }
            task.setCronExpression(request.getCronExpression());
            needsReschedule = true;
        }
        if (request.getCommand() != null) {
            task.setCommand(request.getCommand());
        }
        if (request.getEnabled() != null) {
            boolean wasEnabled = task.getEnabled();
            task.setEnabled(request.getEnabled());
            if (wasEnabled != request.getEnabled()) {
                needsReschedule = true;
            }
        }

        task = taskStore.saveTask(task);

        if (needsReschedule) {
            unscheduleTask(taskId);
            if (task.getEnabled()) {
                scheduleTask(task);
            }
        }

        logger.info("Task updated: {} ({})", task.getName(), task.getId());
        return task;
    }

    public ScheduledTaskEntity startTask(String taskId) {
        logger.info("Starting task: {}", taskId);

        ScheduledTaskEntity task = taskStore.getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (task.getEnabled()) {
            logger.warn("Task is already enabled: {}", taskId);
            return task;
        }

        task.setEnabled(true);
        task = taskStore.saveTask(task);
        scheduleTask(task);

        logger.info("Task started: {} ({})", task.getName(), task.getId());
        return task;
    }

    public ScheduledTaskEntity stopTask(String taskId) {
        logger.info("Stopping task: {}", taskId);

        ScheduledTaskEntity task = taskStore.getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (!task.getEnabled()) {
            logger.warn("Task is already disabled: {}", taskId);
            return task;
        }

        unscheduleTask(taskId);
        task.setEnabled(false);
        task = taskStore.saveTask(task);

        logger.info("Task stopped: {} ({})", task.getName(), task.getId());
        return task;
    }

    public void deleteTask(String taskId) {
        logger.info("Deleting task: {}", taskId);

        ScheduledTaskEntity task = taskStore.getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        unscheduleTask(taskId);
        taskStore.deleteTask(taskId);

        logger.info("Task deleted: {} ({})", task.getName(), task.getId());
    }

    public List<ScheduledTaskEntity> getAllTasks() {
        return taskStore.getAllTasks();
    }

    public Optional<ScheduledTaskEntity> getTask(String taskId) {
        return taskStore.getTask(taskId);
    }

    public List<TaskRunLogEntity> getTaskHistory(String taskId, int limit) {
        return taskStore.getTaskHistory(taskId, limit);
    }

    public SchedulerStatus getStatus() {
        List<ScheduledTaskEntity> allTasks = taskStore.getAllTasks();
        int totalTasks = allTasks.size();
        int enabledTasks = (int) allTasks.stream().filter(ScheduledTaskEntity::getEnabled).count();
        int runningTasksCount = runningTasks.size();

        return new SchedulerStatus(totalTasks, enabledTasks, runningTasksCount);
    }

    private void loadAndScheduleAllTasks() {
        logger.info("Loading enabled tasks from database...");
        List<ScheduledTaskEntity> enabledTasks = taskStore.findEnabledTasks();
        logger.info("Found {} enabled tasks", enabledTasks.size());

        for (ScheduledTaskEntity task : enabledTasks) {
            try {
                scheduleTask(task);
                logger.info("Scheduled task: {} ({})", task.getName(), task.getId());
            } catch (Exception e) {
                logger.error("Failed to schedule task: {} ({})", task.getName(), task.getId(), e);
            }
        }
    }

    private void scheduleTask(ScheduledTaskEntity task) {
        try {
            CronTrigger trigger = new CronTrigger(task.getCronExpression());
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> executeTask(task),
                    trigger
            );
            runningTasks.put(task.getId(), future);
            logger.debug("Task scheduled: {} with cron: {}", task.getId(), task.getCronExpression());
        } catch (Exception e) {
            logger.error("Failed to schedule task: {}", task.getId(), e);
            throw new IllegalArgumentException("Failed to schedule task: " + e.getMessage(), e);
        }
    }

    private void unscheduleTask(String taskId) {
        ScheduledFuture<?> future = runningTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
            logger.debug("Task unscheduled: {}", taskId);
        }
    }

    private void executeTask(ScheduledTaskEntity task) {
        logger.info("Executing task: {} ({})", task.getName(), task.getId());

        TaskRunLogEntity log = new TaskRunLogEntity();
        log.setTaskId(task.getId());
        log.setStartTime(LocalDateTime.now());

        try {
            TaskExecutor.ExecutionResult result = taskExecutor.execute(task);
            log.setStatus(result.isSuccess() ? "SUCCESS" : "FAILED");
            log.setResult(result.getOutput());
            log.setError(result.getError());
            log.setDurationMs(result.getDurationMs());

            task.setLastRunAt(LocalDateTime.now());
            task.setLastRunStatus(log.getStatus());
            task.setRunCount(task.getRunCount() + 1);

            logger.info("Task execution completed: {} - {}", task.getName(), log.getStatus());
        } catch (Exception e) {
            log.setStatus("ERROR");
            log.setError(e.getMessage());
            log.setDurationMs(Duration.between(log.getStartTime(), LocalDateTime.now()).toMillis());

            task.setLastRunAt(LocalDateTime.now());
            task.setLastRunStatus("ERROR");
            task.setRunCount(task.getRunCount() + 1);

            logger.error("Task execution error: {} - {}", task.getName(), e.getMessage(), e);
        } finally {
            log.setEndTime(LocalDateTime.now());
            if (log.getDurationMs() == null) {
                log.setDurationMs(Duration.between(log.getStartTime(), log.getEndTime()).toMillis());
            }
            taskStore.saveRunLog(log);
            taskStore.saveTask(task);
        }
    }

    private boolean isValidCronExpression(String cron) {
        try {
            CronExpression.parse(cron);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
