package com.lavis.entry.http;

import com.lavis.infra.persistence.entity.ScheduledTaskEntity;
import com.lavis.infra.persistence.entity.TaskRunLogEntity;
import com.lavis.feature.scheduler.ScheduledTaskService;
import com.lavis.feature.scheduler.SchedulerStatus;
import com.lavis.feature.scheduler.TaskInterpretRequest;
import com.lavis.feature.scheduler.TaskInterpretResult;
import com.lavis.feature.scheduler.TaskInterpretService;
import com.lavis.feature.scheduler.TaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerController.class);

    private final ScheduledTaskService scheduledTaskService;
    private final TaskInterpretService taskInterpretService;

    public SchedulerController(ScheduledTaskService scheduledTaskService,
                               TaskInterpretService taskInterpretService) {
        this.scheduledTaskService = scheduledTaskService;
        this.taskInterpretService = taskInterpretService;
    }

    @PostMapping("/tasks")
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody TaskRequest request) {
        return handleCommand("create task", () -> {
            ScheduledTaskEntity task = scheduledTaskService.createTask(request);
            return successBody("task", task, "message", "Task created successfully");
        });
    }

    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> getAllTasks() {
        return handleRead("get tasks", () -> {
            List<ScheduledTaskEntity> tasks = scheduledTaskService.getAllTasks();
            return successBody("tasks", tasks);
        });
    }

    @PostMapping("/tasks/interpret")
    public ResponseEntity<Map<String, Object>> interpretTask(@RequestBody TaskInterpretRequest request) {
        return handleCommand("interpret task", () -> {
            TaskInterpretResult result = taskInterpretService.interpret(request);
            return successBody("result", result);
        });
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<Map<String, Object>> getTask(@PathVariable String id) {
        try {
            return scheduledTaskService.getTask(id)
                    .map(task -> ResponseEntity.ok(successBody("task", task)))
                    .orElseGet(() -> notFound("Task not found: " + id));
        } catch (Exception e) {
            return internalError("get task " + id, e);
        }
    }

    @PutMapping("/tasks/{id}")
    public ResponseEntity<Map<String, Object>> updateTask(@PathVariable String id,
                                                          @RequestBody TaskRequest request) {
        return handleCommand("update task " + id, () -> {
            ScheduledTaskEntity task = scheduledTaskService.updateTask(id, request);
            return successBody("task", task, "message", "Task updated successfully");
        });
    }

    @PostMapping("/tasks/{id}/start")
    public ResponseEntity<Map<String, Object>> startTask(@PathVariable String id) {
        return handleCommand("start task " + id, () -> {
            ScheduledTaskEntity task = scheduledTaskService.startTask(id);
            return successBody("task", task, "message", "Task started successfully");
        });
    }

    @PostMapping("/tasks/{id}/run")
    public ResponseEntity<Map<String, Object>> runTaskNow(@PathVariable String id) {
        return handleCommand("run task now " + id, () -> {
            ScheduledTaskEntity task = scheduledTaskService.runTaskNow(id);
            return successBody("task", task, "message", "Task executed immediately");
        });
    }

    @PostMapping("/tasks/{id}/stop")
    public ResponseEntity<Map<String, Object>> stopTask(@PathVariable String id) {
        return handleCommand("stop task " + id, () -> {
            ScheduledTaskEntity task = scheduledTaskService.stopTask(id);
            return successBody("task", task, "message", "Task stopped successfully");
        });
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Map<String, Object>> deleteTask(@PathVariable String id) {
        return handleCommand("delete task " + id, () -> {
            scheduledTaskService.deleteTask(id);
            return successBody("message", "Task deleted successfully");
        });
    }

    @GetMapping("/tasks/{id}/history")
    public ResponseEntity<Map<String, Object>> getTaskHistory(@PathVariable String id,
                                                              @RequestParam(defaultValue = "50") int limit) {
        return handleRead("get task history " + id, () -> {
            List<TaskRunLogEntity> history = scheduledTaskService.getTaskHistory(id, limit);
            return successBody("history", history);
        });
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return handleRead("get scheduler status", () -> {
            SchedulerStatus status = scheduledTaskService.getStatus();
            return successBody("status", status);
        });
    }

    private ResponseEntity<Map<String, Object>> handleCommand(String action,
                                                              Supplier<Map<String, Object>> actionSupplier) {
        try {
            return ResponseEntity.ok(actionSupplier.get());
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to {}: {}", action, e.getMessage());
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            return internalError(action, e);
        }
    }

    private ResponseEntity<Map<String, Object>> handleRead(String action,
                                                           Supplier<Map<String, Object>> actionSupplier) {
        try {
            return ResponseEntity.ok(actionSupplier.get());
        } catch (Exception e) {
            return internalError(action, e);
        }
    }

    private ResponseEntity<Map<String, Object>> notFound(String message) {
        return ResponseEntity.status(404).body(errorBody(message));
    }

    private ResponseEntity<Map<String, Object>> internalError(String action, Exception e) {
        logger.error("Error {}", action, e);
        return ResponseEntity.internalServerError().body(errorBody("Internal server error: " + e.getMessage()));
    }

    private Map<String, Object> successBody(String key, Object value) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put(key, value);
        return body;
    }

    private Map<String, Object> successBody(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> body = successBody(key1, value1);
        body.put(key2, value2);
        return body;
    }

    private Map<String, Object> errorBody(String errorMessage) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("error", errorMessage);
        return body;
    }
}
