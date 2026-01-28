package com.lavis.controller;

import com.lavis.entity.ScheduledTaskEntity;
import com.lavis.entity.TaskRunLogEntity;
import com.lavis.scheduler.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerController.class);

    private final ScheduledTaskService scheduledTaskService;

    public SchedulerController(ScheduledTaskService scheduledTaskService) {
        this.scheduledTaskService = scheduledTaskService;
    }

    @PostMapping("/tasks")
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody CreateTaskRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            ScheduledTaskEntity task = scheduledTaskService.createTask(request);
            response.put("success", true);
            response.put("task", task);
            response.put("message", "Task created successfully");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to create task: {}", e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            logger.error("Error creating task", e);
            response.put("success", false);
            response.put("error", "Internal server error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> getAllTasks() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<ScheduledTaskEntity> tasks = scheduledTaskService.getAllTasks();
            response.put("success", true);
            response.put("tasks", tasks);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting tasks", e);
            response.put("success", false);
            response.put("error", "Internal server error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<Map<String, Object>> getTask(@PathVariable String id) {
        Map<String, Object> response = new HashMap<>();
        try {
            return scheduledTaskService.getTask(id)
                    .map(task -> {
                        response.put("success", true);
                        response.put("task", task);
                        return ResponseEntity.ok(response);
                    })
                    .orElseGet(() -> {
                        response.put("success", false);
                        response.put("error", "Task not found: " + id);
                        return ResponseEntity.notFound().build();
                    });
        } catch (Exception e) {
            logger.error("Error getting task: {}", id, e);
            response.put("success", false);
            response.put("error", "Internal server error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PutMapping("/tasks/{id}")
    public ResponseEntity<Map<String, Object>> updateTask(@PathVariable String id,
                                                          @RequestBody UpdateTaskRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            ScheduledTaskEntity task = scheduledTaskService.updateTask(id, request);
            response.put("success", true);
            response.put("task", task);
            response.put("message", "Task updated successfully");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to update task {}: {}", id, e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            logger.error("Error updating task: {}", id, e);
            response.put("success", false);
            response.put("error", "Internal server error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/tasks/{id}/start")
    public ResponseEntity<Map<String, Object>> startTask(@PathVariable String id) {
        Map<String, Object> response = new HashMap<>();
        try {
            ScheduledTaskEntity task = scheduledTaskService.startTask(id);
            response.put("success", true);
            response.put("task", task);
            response.put("message", "Task started successfully");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to start task {}: {}", id, e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            logger.error("Error starting task: {}", id, e);
            response.put("success", false);
            response.put("error", "Internal server error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/tasks/{id}/stop")
    public ResponseEntity<Map<String, Object>> stopTask(@PathVariable String id) {
        Map<String, Object> response = new HashMap<>();
        try {
            ScheduledTaskEntity task = scheduledTaskService.stopTask(id);
            response.put("success", true);
            response.put("task", task);
            response.put("message", "Task stopped successfully");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to stop task {}: {}", id, e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            logger.error("Error stopping task: {}", id, e);
            response.put("success", false);
            response.put("error", "Internal server error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Map<String, Object>> deleteTask(@PathVariable String id) {
        Map<String, Object> response = new HashMap<>();
        try {
            scheduledTaskService.deleteTask(id);
            response.put("success", true);
            response.put("message", "Task deleted successfully");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to delete task {}: {}", id, e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            logger.error("Error deleting task: {}", id, e);
            response.put("success", false);
            response.put("error", "Internal server error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/tasks/{id}/history")
    public ResponseEntity<Map<String, Object>> getTaskHistory(@PathVariable String id,
                                                              @RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<TaskRunLogEntity> history = scheduledTaskService.getTaskHistory(id, limit);
            response.put("success", true);
            response.put("history", history);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting task history: {}", id, e);
            response.put("success", false);
            response.put("error", "Internal server error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> response = new HashMap<>();
        try {
            SchedulerStatus status = scheduledTaskService.getStatus();
            response.put("success", true);
            response.put("status", status);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting scheduler status", e);
            response.put("success", false);
            response.put("error", "Internal server error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
