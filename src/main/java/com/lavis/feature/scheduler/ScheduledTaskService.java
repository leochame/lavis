package com.lavis.feature.scheduler;

import com.lavis.infra.persistence.entity.ScheduledTaskEntity;
import com.lavis.infra.persistence.entity.TaskRunLogEntity;
import com.lavis.infra.persistence.repository.ScheduledTaskRepository;
import com.lavis.infra.persistence.repository.TaskRunLogRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;

@Service
public class ScheduledTaskService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledTaskService.class);

    private static final int AUTO_PAUSE_THRESHOLD = 3;

    private final TaskScheduler taskScheduler;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final TaskRunLogRepository taskRunLogRepository;
    private final TaskExecutor taskExecutor;
    private final TaskFileLoader taskFileLoader;
    private final TaskRules taskRules;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> runningTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> taskLocks = new ConcurrentHashMap<>();
    private final ExecutorService executionQueue = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "scheduled-task-queue");
        thread.setDaemon(true);
        return thread;
    });

    public ScheduledTaskService(TaskScheduler taskScheduler,
                                ScheduledTaskRepository scheduledTaskRepository,
                                TaskRunLogRepository taskRunLogRepository,
                                TaskExecutor taskExecutor,
                                TaskFileLoader taskFileLoader,
                                TaskRules taskRules) {
        this.taskScheduler = taskScheduler;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.taskRunLogRepository = taskRunLogRepository;
        this.taskExecutor = taskExecutor;
        this.taskFileLoader = taskFileLoader;
        this.taskRules = taskRules;
    }

    @PostConstruct
    public void init() {
        logger.info("Initializing ScheduledTaskService...");
        syncTaskFilesToStore();
        loadAndScheduleAllTasks();
        taskFileLoader.addReloadListener(this::reloadFileTasks);
        taskFileLoader.startWatching();
        logger.info("ScheduledTaskService initialized with {} running tasks", runningTasks.size());
    }

    @PreDestroy
    public void destroy() {
        taskFileLoader.stopWatching();
        executionQueue.shutdownNow();
    }

    public ScheduledTaskEntity createTask(TaskRequest request) {
        logger.info("Creating manual task: {}", request.getName());

        ScheduledTaskEntity task = new ScheduledTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setSourceType(TaskRules.SOURCE_TYPE_MANUAL);
        task.setSourcePath(null);
        task.setName(request.getName());
        task.setDescription(request.getDescription());
        task.setCronExpression(request.getCronExpression());
        task.setScheduleMode(request.getScheduleMode());
        task.setIntervalSeconds(request.getIntervalSeconds());
        task.setExecutionMode(request.getExecutionMode());
        task.setCommand(request.getCommand());
        task.setRequestContent(request.getRequestContent());
        task.setRequestUseOrchestrator(Boolean.TRUE.equals(request.getRequestUseOrchestrator()));
        task.setEnabled(request.getEnabled());
        task.setCreatedAt(LocalDateTime.now());
        task.setRunCount(0);
        task.setPenaltyPoints(0);
        task.setAutoPaused(false);

        taskRules.validateAndNormalize(task);

        task = saveTask(task);
        if (task.getEnabled()) {
            scheduleTask(task);
        } else {
            task.setNextRunAt(null);
            task = saveTask(task);
        }

        logger.info("Manual task created: {} ({})", task.getName(), task.getId());
        return task;
    }

    public ScheduledTaskEntity updateTask(String taskId, TaskRequest request) {
        logger.info("Updating task: {}", taskId);

        ScheduledTaskEntity task = findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (TaskRules.SOURCE_TYPE_FILE.equalsIgnoreCase(task.getSourceType())) {
            throw new IllegalArgumentException("File-backed tasks are managed in .task files and cannot be edited here");
        }

        String previousScheduleMode = task.getScheduleMode();
        String previousCron = task.getCronExpression();
        Integer previousInterval = task.getIntervalSeconds();
        Boolean previousEnabled = task.getEnabled();

        if (request.getName() != null) {
            task.setName(request.getName());
        }
        if (request.getDescription() != null) {
            task.setDescription(request.getDescription());
        }
        if (request.getScheduleMode() != null) {
            task.setScheduleMode(request.getScheduleMode());
        }
        if (request.getCronExpression() != null) {
            task.setCronExpression(request.getCronExpression());
        }
        if (request.getIntervalSeconds() != null) {
            task.setIntervalSeconds(request.getIntervalSeconds());
        }
        if (request.getExecutionMode() != null) {
            task.setExecutionMode(request.getExecutionMode());
        }
        if (request.getCommand() != null) {
            task.setCommand(request.getCommand());
        }
        if (request.getRequestContent() != null) {
            task.setRequestContent(request.getRequestContent());
        }
        if (request.getRequestUseOrchestrator() != null) {
            task.setRequestUseOrchestrator(request.getRequestUseOrchestrator());
        }
        if (request.getEnabled() != null) {
            task.setEnabled(request.getEnabled());
        }

        taskRules.validateAndNormalize(task);

        boolean needsReschedule =
                !Objects.equals(previousEnabled, task.getEnabled()) ||
                !Objects.equals(previousScheduleMode, task.getScheduleMode()) ||
                !Objects.equals(previousCron, task.getCronExpression()) ||
                !Objects.equals(previousInterval, task.getIntervalSeconds());

        task = saveTask(task);

        if (needsReschedule) {
            unscheduleTask(taskId, false);
            if (task.getEnabled()) {
                scheduleTask(task);
            } else {
                task.setNextRunAt(null);
                task = saveTask(task);
            }
        }

        logger.info("Task updated: {} ({})", task.getName(), task.getId());
        return task;
    }

    public ScheduledTaskEntity runTaskNow(String taskId) {
        logger.info("Queueing task for immediate run: {}", taskId);
        ScheduledTaskEntity task = findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        enqueueTask(taskId, false, "manual-run");
        return findTask(taskId).orElse(task);
    }

    public ScheduledTaskEntity startTask(String taskId) {
        logger.info("Starting task: {}", taskId);

        ScheduledTaskEntity task = findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (task.getEnabled()) {
            logger.warn("Task is already enabled: {}", taskId);
            return task;
        }

        task.setEnabled(true);
        task.setAutoPaused(false);
        task.setPenaltyPoints(0);
        task = saveTask(task);
        scheduleTask(task);

        logger.info("Task started: {} ({})", task.getName(), task.getId());
        return task;
    }

    public ScheduledTaskEntity stopTask(String taskId) {
        logger.info("Stopping task: {}", taskId);

        ScheduledTaskEntity task = findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (!task.getEnabled()) {
            logger.warn("Task is already disabled: {}", taskId);
            return task;
        }

        unscheduleTask(taskId, true);
        task.setEnabled(false);
        task.setNextRunAt(null);
        task = saveTask(task);

        logger.info("Task stopped: {} ({})", task.getName(), task.getId());
        return task;
    }

    public void deleteTask(String taskId) {
        logger.info("Deleting task: {}", taskId);

        ScheduledTaskEntity task = findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (TaskRules.SOURCE_TYPE_FILE.equalsIgnoreCase(task.getSourceType())) {
            throw new IllegalArgumentException("File-backed tasks are managed in .task files and cannot be deleted here");
        }

        unscheduleTask(taskId, false);
        deleteTaskById(taskId);

        logger.info("Task deleted: {} ({})", task.getName(), task.getId());
    }

    public List<ScheduledTaskEntity> getAllTasks() {
        return findAllTasks();
    }

    public Optional<ScheduledTaskEntity> getTask(String taskId) {
        return findTask(taskId);
    }

    public List<TaskRunLogEntity> getTaskHistory(String taskId, int limit) {
        return taskRunLogRepository.findByTaskIdOrderByStartTimeDesc(taskId)
                .stream()
                .limit(limit)
                .toList();
    }

    public SchedulerStatus getStatus() {
        List<ScheduledTaskEntity> allTasks = findAllTasks();
        int totalTasks = allTasks.size();
        int enabledTasks = (int) allTasks.stream().filter(ScheduledTaskEntity::getEnabled).count();
        int runningTasksCount = runningTasks.size();

        return new SchedulerStatus(totalTasks, enabledTasks, runningTasksCount);
    }

    private void loadAndScheduleAllTasks() {
        logger.info("Loading enabled tasks from database...");
        List<ScheduledTaskEntity> enabledTasks = findEnabledTasks();
        logger.info("Found {} enabled tasks", enabledTasks.size());

        for (ScheduledTaskEntity task : enabledTasks) {
            try {
                taskRules.validateAndNormalize(task);
                saveTask(task);
                scheduleTask(task);
            } catch (Exception e) {
                logger.error("Failed to schedule task: {} ({})", task.getName(), task.getId(), e);
            }
        }
    }

    private synchronized void reloadFileTasks() {
        logger.info("Reloading .task metadata");
        List<ScheduledTaskEntity> existingFileTasks = findFileTasks();
        for (ScheduledTaskEntity fileTask : existingFileTasks) {
            unscheduleTask(fileTask.getId(), false);
        }

        syncTaskFilesToStore();

        for (ScheduledTaskEntity fileTask : findFileTasks()) {
            if (fileTask.getEnabled()) {
                scheduleTask(fileTask);
            } else {
                fileTask.setNextRunAt(null);
                saveTask(fileTask);
            }
        }
    }

    private void syncTaskFilesToStore() {
        List<TaskFileLoader.TaskDefinition> definitions = taskFileLoader.loadAllTaskHeaders();
        List<ScheduledTaskEntity> existingFileTasks = findFileTasks();

        Set<String> activeFileTaskIds = new HashSet<>();
        for (TaskFileLoader.TaskDefinition definition : definitions) {
            String taskId = toFileTaskId(definition.id());
            activeFileTaskIds.add(taskId);

            ScheduledTaskEntity entity = findTask(taskId).orElseGet(ScheduledTaskEntity::new);
            boolean wasAutoPaused = Boolean.TRUE.equals(entity.getAutoPaused());
            int penaltyPoints = entity.getPenaltyPoints() != null ? entity.getPenaltyPoints() : 0;

            entity.setId(taskId);
            entity.setSourceType(TaskRules.SOURCE_TYPE_FILE);
            entity.setSourcePath(definition.sourcePath().toString());
            entity.setName(definition.name());
            entity.setDescription(definition.description());
            entity.setScheduleMode(definition.scheduleMode());
            entity.setCronExpression(definition.cronExpression());
            entity.setIntervalSeconds(definition.intervalSeconds());
            entity.setExecutionMode(TaskRules.EXECUTION_MODE_SCRIPT.equalsIgnoreCase(definition.executionMode())
                    ? TaskRules.EXECUTION_MODE_COMMAND
                    : TaskRules.EXECUTION_MODE_REQUEST);
            entity.setRequestUseOrchestrator(definition.useOrchestrator());
            entity.setCommand("[loaded from .task]");
            entity.setRequestContent(null);
            entity.setEnabled(!wasAutoPaused && definition.enabled());
            entity.setPenaltyPoints(penaltyPoints);
            entity.setAutoPaused(wasAutoPaused);
            if (entity.getRunCount() == null) {
                entity.setRunCount(0);
            }

            taskRules.validateAndNormalize(entity);
            saveTask(entity);
        }

        for (ScheduledTaskEntity existingFileTask : existingFileTasks) {
            if (!activeFileTaskIds.contains(existingFileTask.getId())) {
                unscheduleTask(existingFileTask.getId(), false);
                deleteTaskById(existingFileTask.getId());
            }
        }
    }

    private void scheduleTask(ScheduledTaskEntity task) {
        try {
            unscheduleTask(task.getId(), false);

            String scheduleMode = taskRules.normalizeScheduleMode(task.getScheduleMode());
            ScheduledFuture<?> future;
            LocalDateTime nextRunAt;

            if (TaskRules.SCHEDULE_MODE_LOOP.equals(scheduleMode)) {
                int intervalSeconds = taskRules.validateLoopInterval(task.getIntervalSeconds());
                Instant firstRun = Instant.now().plusSeconds(intervalSeconds);
                future = taskScheduler.scheduleAtFixedRate(
                        () -> enqueueTask(task.getId(), true, "loop"),
                        firstRun,
                        Duration.ofSeconds(intervalSeconds)
                );
                nextRunAt = LocalDateTime.ofInstant(firstRun, ZoneId.systemDefault());
            } else {
                String normalizedCron = taskRules.normalizeCronExpression(task.getCronExpression());
                task.setCronExpression(normalizedCron);
                CronTrigger trigger = new CronTrigger(normalizedCron);
                future = taskScheduler.schedule(
                        () -> enqueueTask(task.getId(), true, "cron"),
                        trigger
                );
                nextRunAt = taskRules.computeNextRunAt(task, LocalDateTime.now());
            }

            if (future == null) {
                throw new IllegalArgumentException("TaskScheduler returned null future");
            }

            runningTasks.put(task.getId(), future);
            task.setNextRunAt(nextRunAt);
            saveTask(task);
            logger.debug("Task scheduled: {} mode={} nextRunAt={}", task.getId(), scheduleMode, nextRunAt);
        } catch (Exception e) {
            logger.error("Failed to schedule task: {}", task.getId(), e);
            throw new IllegalArgumentException("Failed to schedule task: " + e.getMessage(), e);
        }
    }

    private void unscheduleTask(String taskId, boolean clearNextRunAt) {
        ScheduledFuture<?> future = runningTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
            logger.debug("Task unscheduled: {}", taskId);
        }

        if (clearNextRunAt) {
            findTask(taskId).ifPresent(task -> {
                task.setNextRunAt(null);
                saveTask(task);
            });
        }
    }

    private void enqueueTask(String taskId, boolean triggeredByScheduler, String source) {
        findTask(taskId).ifPresent(task -> {
            task.setLastRunStatus("QUEUED");
            saveTask(task);
        });

        executionQueue.submit(() -> executeTaskById(taskId, triggeredByScheduler, source));
    }

    private void executeTaskById(String taskId, boolean triggeredByScheduler, String source) {
        Object lock = taskLocks.computeIfAbsent(taskId, k -> new Object());
        synchronized (lock) {
            Optional<ScheduledTaskEntity> taskOptional = findTask(taskId);
            if (taskOptional.isEmpty()) {
                logger.warn("Task not found during execution, unscheduling: {}", taskId);
                unscheduleTask(taskId, false);
                return;
            }

            ScheduledTaskEntity task = taskOptional.get();
            if (triggeredByScheduler && !task.getEnabled()) {
                logger.debug("Skip disabled task execution: {}", taskId);
                return;
            }

            executeTask(task, source);
        }
    }

    private void executeTask(ScheduledTaskEntity task, String source) {
        logger.info("Executing task: {} ({}) source={} mode={} executionMode={}",
                task.getName(), task.getId(), source, task.getScheduleMode(), task.getExecutionMode());

        task.setLastRunStatus("RUNNING");
        saveTask(task);

        TaskRunLogEntity log = new TaskRunLogEntity();
        log.setTaskId(task.getId());
        log.setStartTime(LocalDateTime.now());

        try {
            handleExecutionResult(task, log, taskExecutor.execute(task));
        } catch (Exception e) {
            handleExecutionError(task, log, e);
        } finally {
            persistExecutionOutcome(task, log);
        }
    }

    private void handleExecutionResult(ScheduledTaskEntity task,
                                       TaskRunLogEntity log,
                                       TaskExecutor.ExecutionResult result) {
        boolean success = result.isSuccess();
        log.setStatus(success ? "SUCCESS" : "FAILED");
        log.setResult(result.getOutput());
        log.setError(result.getError());
        log.setDurationMs(result.getDurationMs());

        applyPenalty(task, success);
        finalizeTaskRun(task, success ? "SUCCESS" : "FAILED", success ? result.getOutput() : result.getError());

        if (success || !shouldAutoPause(task)) {
            refreshNextRunAt(task);
            logger.info("Task execution completed: {} - {}", task.getName(), task.getLastRunStatus());
            return;
        }

        task.setLastRunStatus("AUTO_PAUSED");
        autoPauseTask(task);
        logger.warn("Task auto-paused after repeated failures: {} ({})", task.getName(), task.getId());
        logger.info("Task execution completed: {} - {}", task.getName(), task.getLastRunStatus());
    }

    private void handleExecutionError(ScheduledTaskEntity task, TaskRunLogEntity log, Exception error) {
        log.setStatus("ERROR");
        log.setError(error.getMessage());
        log.setDurationMs(Duration.between(log.getStartTime(), LocalDateTime.now()).toMillis());

        applyPenalty(task, false);
        boolean autoPause = shouldAutoPause(task);
        finalizeTaskRun(task, autoPause ? "AUTO_PAUSED" : "ERROR", error.getMessage());

        if (autoPause) {
            autoPauseTask(task);
        } else {
            refreshNextRunAt(task);
        }

        logger.error("Task execution error: {} - {}", task.getName(), error.getMessage(), error);
    }

    private void persistExecutionOutcome(ScheduledTaskEntity task, TaskRunLogEntity log) {
        log.setEndTime(LocalDateTime.now());
        if (log.getDurationMs() == null) {
            log.setDurationMs(Duration.between(log.getStartTime(), log.getEndTime()).toMillis());
        }
        saveRunLog(log);
        saveTask(task);
    }

    private void applyPenalty(ScheduledTaskEntity task, boolean success) {
        int penaltyPoints = task.getPenaltyPoints() != null ? task.getPenaltyPoints() : 0;
        if (success) {
            task.setPenaltyPoints(Math.max(0, penaltyPoints - 1));
            task.setAutoPaused(false);
        } else {
            task.setPenaltyPoints(penaltyPoints + 1);
        }
    }

    private void finalizeTaskRun(ScheduledTaskEntity task, String status, String result) {
        task.setLastRunAt(LocalDateTime.now());
        task.setLastRunStatus(status);
        task.setLastRunResult(result);
        task.setRunCount(task.getRunCount() + 1);
    }

    private boolean shouldAutoPause(ScheduledTaskEntity task) {
        return task.getPenaltyPoints() >= AUTO_PAUSE_THRESHOLD;
    }

    private void autoPauseTask(ScheduledTaskEntity task) {
        task.setEnabled(false);
        task.setAutoPaused(true);
        unscheduleTask(task.getId(), true);
    }

    private void refreshNextRunAt(ScheduledTaskEntity task) {
        if (Boolean.TRUE.equals(task.getEnabled()) && runningTasks.containsKey(task.getId())) {
            task.setNextRunAt(taskRules.computeNextRunAt(task, LocalDateTime.now()));
        } else {
            task.setNextRunAt(null);
        }
    }

    private String toFileTaskId(String taskId) {
        return "file:" + taskId;
    }

    private ScheduledTaskEntity saveTask(ScheduledTaskEntity task) {
        return scheduledTaskRepository.save(task);
    }

    private Optional<ScheduledTaskEntity> findTask(String taskId) {
        return scheduledTaskRepository.findById(taskId);
    }

    private List<ScheduledTaskEntity> findAllTasks() {
        return scheduledTaskRepository.findAll();
    }

    private List<ScheduledTaskEntity> findEnabledTasks() {
        return scheduledTaskRepository.findByEnabled(true);
    }

    private List<ScheduledTaskEntity> findFileTasks() {
        return scheduledTaskRepository.findBySourceTypeOrderByCreatedAtDesc(TaskRules.SOURCE_TYPE_FILE);
    }

    private void deleteTaskById(String taskId) {
        scheduledTaskRepository.deleteById(taskId);
    }

    private void saveRunLog(TaskRunLogEntity log) {
        taskRunLogRepository.save(log);
    }

}
