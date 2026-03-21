package com.lavis.feature.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TaskFileLoader {

    private static final Logger logger = LoggerFactory.getLogger(TaskFileLoader.class);
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n(.*)$", Pattern.DOTALL);
    private static final String TASK_FILE_GLOB = "*.task.md";

    private final Yaml yaml = new Yaml();
    private final List<Runnable> reloadListeners = new CopyOnWriteArrayList<>();

    @Value("${tasks.directory:.task}")
    private String tasksDirectory;

    @Value("${tasks.hot-reload.enabled:true}")
    private boolean hotReloadEnabled;

    private WatchService watchService;
    private Thread watchThread;

    public Path getTasksDirectory() {
        return Paths.get(tasksDirectory);
    }

    public List<TaskDefinition> loadAllTaskHeaders() {
        Path tasksPath = getTasksDirectory();
        ensureTasksDirectory(tasksPath);

        List<TaskDefinition> definitions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tasksPath, TASK_FILE_GLOB)) {
            for (Path taskFile : stream) {
                loadTaskFile(taskFile, false).ifPresent(definitions::add);
            }
        } catch (IOException e) {
            logger.error("Failed to scan .task directory: {}", tasksPath, e);
        }
        definitions.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return definitions;
    }

    public Optional<TaskDefinition> loadExecutionDefinition(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return Optional.empty();
        }
        return loadTaskFile(Paths.get(sourcePath), true);
    }

    public void addReloadListener(Runnable listener) {
        reloadListeners.add(listener);
    }

    public void startWatching() {
        if (!hotReloadEnabled) {
            logger.info(".task hot reload is disabled");
            return;
        }

        Path tasksPath = getTasksDirectory();
        ensureTasksDirectory(tasksPath);

        try {
            watchService = FileSystems.getDefault().newWatchService();
            tasksPath.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY
            );

            watchThread = new Thread(this::watchLoop, "task-file-watcher");
            watchThread.setDaemon(true);
            watchThread.start();
            logger.info("Started watching .task directory: {}", tasksPath.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to start .task watcher: {}", tasksPath, e);
        }
    }

    public void stopWatching() {
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                logger.warn("Failed to close .task watch service", e);
            }
            watchService = null;
        }
    }

    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take();
                boolean shouldReload = false;

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    if (changed != null && changed.toString().endsWith(".task.md")) {
                        shouldReload = true;
                    }
                }

                if (shouldReload) {
                    logger.info("Detected .task file changes, reloading metadata");
                    notifyReloadListeners();
                }

                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void notifyReloadListeners() {
        for (Runnable listener : reloadListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                logger.error("Task reload listener failed", e);
            }
        }
    }

    private Optional<TaskDefinition> loadTaskFile(Path taskFile, boolean includeBody) {
        if (!Files.isRegularFile(taskFile)) {
            return Optional.empty();
        }

        try {
            String content = Files.readString(taskFile);
            Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
            if (!matcher.matches()) {
                logger.warn("Invalid .task file format (missing front-matter): {}", taskFile);
                return Optional.empty();
            }

            String frontmatter = matcher.group(1);
            String body = matcher.group(2).trim();

            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = yaml.load(frontmatter);
            if (metadata == null) {
                metadata = Collections.emptyMap();
            }

            String fileName = taskFile.getFileName().toString();
            String defaultId = fileName.endsWith(".task.md")
                    ? fileName.substring(0, fileName.length() - ".task.md".length())
                    : fileName;

            String cron = getString(metadata, "cron");
            Integer everySeconds = getInteger(metadata, "every_seconds");
            String scheduleMode = everySeconds != null ? "LOOP" : "CRON";
            String mode = normalizeMode(getString(metadata, "mode"));
            String id = getString(metadata, "id");
            String name = getString(metadata, "name");

            if (cron == null && everySeconds == null) {
                logger.warn(".task file must define cron or every_seconds: {}", taskFile);
                return Optional.empty();
            }

            return Optional.of(new TaskDefinition(
                    id != null ? id : defaultId,
                    name != null ? name : defaultId,
                    getString(metadata, "description"),
                    getBoolean(metadata, "enabled", true),
                    scheduleMode,
                    cron,
                    everySeconds,
                    mode,
                    getBoolean(metadata, "use_orchestrator", false),
                    taskFile.toAbsolutePath(),
                    includeBody ? body : null
            ));
        } catch (Exception e) {
            logger.error("Failed to parse .task file: {}", taskFile, e);
            return Optional.empty();
        }
    }

    private void ensureTasksDirectory(Path tasksPath) {
        if (Files.exists(tasksPath)) {
            return;
        }
        try {
            Files.createDirectories(tasksPath);
            logger.info("Created .task directory: {}", tasksPath.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to create .task directory: {}", tasksPath, e);
        }
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "REQUEST";
        }
        String normalized = mode.trim().toUpperCase();
        if ("SCRIPT".equals(normalized)) {
            return "SCRIPT";
        }
        return "REQUEST";
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString().trim() : null;
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString().trim());
    }

    public record TaskDefinition(
            String id,
            String name,
            String description,
            boolean enabled,
            String scheduleMode,
            String cronExpression,
            Integer intervalSeconds,
            String executionMode,
            boolean useOrchestrator,
            Path sourcePath,
            String body
    ) {
    }
}
