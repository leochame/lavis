package com.lavis.feature.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TaskFileLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadAllTaskHeaders_shouldParseFrontmatterWithCrLf() throws Exception {
        Path taskFile = tempDir.resolve("daily.task.md");
        String content = "---\r\n"
                + "id: daily-brief\r\n"
                + "enabled: true\r\n"
                + "cron: \"0 9 * * 1-5\"\r\n"
                + "mode: request\r\n"
                + "---\r\n"
                + "Summarize today's priorities.\r\n";
        Files.writeString(taskFile, content);

        TaskFileLoader loader = new TaskFileLoader();
        ReflectionTestUtils.setField(loader, "tasksDirectory", tempDir.toString());
        ReflectionTestUtils.setField(loader, "hotReloadEnabled", false);

        List<TaskFileLoader.TaskDefinition> defs = loader.loadAllTaskHeaders();

        assertEquals(1, defs.size());
        TaskFileLoader.TaskDefinition def = defs.get(0);
        assertEquals("daily-brief", def.id());
        assertEquals("CRON", def.scheduleMode());
        assertEquals("0 9 * * 1-5", def.cronExpression());
    }

    @Test
    void loadExecutionDefinition_shouldKeepBodyWithCrLfInput() throws Exception {
        Path taskFile = tempDir.resolve("ops.task.md");
        String content = "---\r\n"
                + "id: ops-run\r\n"
                + "enabled: true\r\n"
                + "cron: \"0 */2 * * *\"\r\n"
                + "mode: request\r\n"
                + "---\r\n"
                + "Run quick ops check.\r\n";
        Files.writeString(taskFile, content);

        TaskFileLoader loader = new TaskFileLoader();

        TaskFileLoader.TaskDefinition def = loader
                .loadExecutionDefinition(taskFile.toString())
                .orElseThrow();

        assertEquals("ops-run", def.id());
        assertFalse(def.body().isBlank());
        assertEquals("Run quick ops check.", def.body());
    }
}
