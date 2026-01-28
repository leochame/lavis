package com.lavis.scheduler;

import com.lavis.entity.ScheduledTaskEntity;
import com.lavis.entity.TaskRunLogEntity;
import com.lavis.repository.ScheduledTaskRepository;
import com.lavis.repository.TaskRunLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
public class TaskStore {

    private final ScheduledTaskRepository scheduledTaskRepository;
    private final TaskRunLogRepository taskRunLogRepository;

    public TaskStore(ScheduledTaskRepository scheduledTaskRepository,
                     TaskRunLogRepository taskRunLogRepository) {
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.taskRunLogRepository = taskRunLogRepository;
    }

    @Transactional
    public ScheduledTaskEntity saveTask(ScheduledTaskEntity task) {
        return scheduledTaskRepository.save(task);
    }

    public Optional<ScheduledTaskEntity> getTask(String taskId) {
        return scheduledTaskRepository.findById(taskId);
    }

    public List<ScheduledTaskEntity> getAllTasks() {
        return scheduledTaskRepository.findAll();
    }

    public List<ScheduledTaskEntity> findEnabledTasks() {
        return scheduledTaskRepository.findByEnabled(true);
    }

    @Transactional
    public void deleteTask(String taskId) {
        scheduledTaskRepository.deleteById(taskId);
    }

    @Transactional
    public TaskRunLogEntity saveRunLog(TaskRunLogEntity log) {
        return taskRunLogRepository.save(log);
    }

    public List<TaskRunLogEntity> getTaskHistory(String taskId, int limit) {
        return taskRunLogRepository.findByTaskIdOrderByStartTimeDesc(taskId)
                .stream()
                .limit(limit)
                .toList();
    }
}
