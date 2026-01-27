# Lavis 增强计划（第二部分）：定时任务 + Skills 系统

> 本文档是《Enhancement-Plan-Memory-Cron-Skills.md》的续篇，包含定时任务和 Skills 系统的完整实现。

---

## 二、定时任务系统（续）

### 2.3.2 核心实现（续）

**ScheduledTaskService.java**

```java
package com.lavis.scheduler;

import com.lavis.scheduler.model.ScheduledTask;
import com.lavis.scheduler.model.TaskRunLog;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class ScheduledTaskService {

    private final TaskScheduler taskScheduler;
    private final TaskStore taskStore;
    private final TaskExecutor taskExecutor;

    // 存储正在运行的任务
    private final Map<String, ScheduledFuture<?>> runningTasks = new ConcurrentHashMap<>();

    public ScheduledTaskService(
            TaskScheduler taskScheduler,
            TaskStore taskStore,
            TaskExecutor taskExecutor) {
        this.taskScheduler = taskScheduler;
        this.taskStore = taskStore;
        this.taskExecutor = taskExecutor;

        // 启动时加载所有任务
        loadAndScheduleAllTasks();
    }

    /**
     * 创建新任务
     */
    public ScheduledTask createTask(ScheduledTask task) {
        task.setId(UUID.randomUUID().toString());
        task.setCreatedAt(LocalDateTime.now());
        task.setEnabled(true);

        // 保存到磁盘
        taskStore.saveTask(task);

        // 调度任务
        if (task.isEnabled()) {
            scheduleTask(task);
        }

        return task;
    }

    /**
     * 调度任务
     */
    private void scheduleTask(ScheduledTask task) {
        ScheduledFuture<?> future = taskScheduler.schedule(
            () -> executeTask(task),
            new CronTrigger(task.getCron())
        );

        runningTasks.put(task.getId(), future);
    }

    /**
     * 执行任务
     */
    private void executeTask(ScheduledTask task) {
        TaskRunLog log = new TaskRunLog();
        log.setTaskId(task.getId());
        log.setStartTime(LocalDateTime.now());

        try {
            // 执行任务
            String result = taskExecutor.execute(task);

            log.setStatus("SUCCESS");
            log.setResult(result);

            // 更新任务状态
            task.setLastRunAt(LocalDateTime.now());
            task.setLastRunStatus("SUCCESS");

        } catch (Exception e) {
            log.setStatus("FAILED");
            log.setError(e.getMessage());

            task.setLastRunStatus("FAILED");
        } finally {
            log.setEndTime(LocalDateTime.now());

            // 保存执行日志
            taskStore.saveRunLog(log);

            // 更新任务
            taskStore.saveTask(task);
        }
    }

    /**
     * 停止任务
     */
    public void stopTask(String taskId) {
        ScheduledFuture<?> future = runningTasks.get(taskId);
        if (future != null) {
            future.cancel(false);
            runningTasks.remove(taskId);
        }

        // 更新任务状态
        ScheduledTask task = taskStore.getTask(taskId);
        if (task != null) {
            task.setEnabled(false);
            taskStore.saveTask(task);
        }
    }

    /**
     * 删除任务
     */
    public void deleteTask(String taskId) {
        stopTask(taskId);
        taskStore.deleteTask(taskId);
    }

    /**
     * 获取所有任务
     */
    public List<ScheduledTask> getAllTasks() {
        return taskStore.getAllTasks();
    }

    /**
     * 获取任务执行历史
     */
    public List<TaskRunLog> getTaskHistory(String taskId) {
        return taskStore.getRunLogs(taskId);
    }

    /**
     * 加载并调度所有任务
     */
    private void loadAndScheduleAllTasks() {
        List<ScheduledTask> tasks = taskStore.getAllTasks();
        for (ScheduledTask task : tasks) {
            if (task.isEnabled()) {
                scheduleTask(task);
            }
        }
    }
}
```

**TaskExecutor.java**

```java
package com.lavis.scheduler;

import com.lavis.cognitive.AgentService;
import com.lavis.scheduler.model.ScheduledTask;
import org.springframework.stereotype.Service;

@Service
public class TaskExecutor {

    private final AgentService agentService;

    public TaskExecutor(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 执行任务
     */
    public String execute(ScheduledTask task) {
        // 根据任务类型执行不同的逻辑
        String command = task.getCommand();

        if (command.startsWith("agent:")) {
            // 执行 Agent 任务
            String goal = command.substring(6);
            return agentService.executeTask(goal);

        } else if (command.startsWith("shell:")) {
            // 执行 Shell 命令
            String shellCommand = command.substring(6);
            return executeShellCommand(shellCommand);

        } else {
            throw new IllegalArgumentException("Unknown command type: " + command);
        }
    }

    /**
     * 执行 Shell 命令
     */
    private String executeShellCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            process.waitFor();
            return "Shell command executed successfully";
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute shell command", e);
        }
    }
}
```

**TaskStore.java**

```java
package com.lavis.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lavis.scheduler.model.ScheduledTask;
import com.lavis.scheduler.model.TaskRunLog;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TaskStore {

    private static final String TASK_DIR = System.getProperty("user.home") + "/.lavis/tasks";
    private static final String LOG_DIR = System.getProperty("user.home") + "/.lavis/task-logs";
    private final ObjectMapper objectMapper;

    public TaskStore() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

        // 创建目录
        try {
            Files.createDirectories(Paths.get(TASK_DIR));
            Files.createDirectories(Paths.get(LOG_DIR));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create task directories", e);
        }
    }

    /**
     * 保存任务
     */
    public void saveTask(ScheduledTask task) {
        Path taskFile = Paths.get(TASK_DIR, task.getId() + ".json");
        try {
            objectMapper.writeValue(taskFile.toFile(), task);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save task", e);
        }
    }

    /**
     * 获取任务
     */
    public ScheduledTask getTask(String taskId) {
        Path taskFile = Paths.get(TASK_DIR, taskId + ".json");
        if (!Files.exists(taskFile)) {
            return null;
        }

        try {
            return objectMapper.readValue(taskFile.toFile(), ScheduledTask.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load task", e);
        }
    }

    /**
     * 获取所有任务
     */
    public List<ScheduledTask> getAllTasks() {
        try {
            return Files.list(Paths.get(TASK_DIR))
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".json"))
                .map(file -> {
                    try {
                        return objectMapper.readValue(file.toFile(), ScheduledTask.class);
                    } catch (IOException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to list tasks", e);
        }
    }

    /**
     * 删除任务
     */
    public void deleteTask(String taskId) {
        Path taskFile = Paths.get(TASK_DIR, taskId + ".json");
        try {
            Files.deleteIfExists(taskFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete task", e);
        }
    }

    /**
     * 保存执行日志
     */
    public void saveRunLog(TaskRunLog log) {
        Path logFile = Paths.get(LOG_DIR, log.getTaskId() + ".jsonl");
        try {
            String json = objectMapper.writeValueAsString(log);
            Files.write(
                logFile,
                (json + "\n").getBytes(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to save run log", e);
        }
    }

    /**
     * 获取任务执行日志
     */
    public List<TaskRunLog> getRunLogs(String taskId) {
        Path logFile = Paths.get(LOG_DIR, taskId + ".jsonl");
        if (!Files.exists(logFile)) {
            return new ArrayList<>();
        }

        try {
            return Files.readAllLines(logFile).stream()
                .map(line -> {
                    try {
                        return objectMapper.readValue(line, TaskRunLog.class);
                    } catch (IOException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load run logs", e);
        }
    }
}
```

**TaskRunLog.java**

```java
package com.lavis.scheduler.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TaskRunLog {
    private String taskId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;      // SUCCESS, FAILED
    private String result;
    private String error;
}
```

### 2.3.3 REST API

**SchedulerController.java**

```java
package com.lavis.controller;

import com.lavis.scheduler.ScheduledTaskService;
import com.lavis.scheduler.model.ScheduledTask;
import com.lavis.scheduler.model.TaskRunLog;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    private final ScheduledTaskService scheduledTaskService;

    public SchedulerController(ScheduledTaskService scheduledTaskService) {
        this.scheduledTaskService = scheduledTaskService;
    }

    /**
     * 创建任务
     */
    @PostMapping("/tasks")
    public ScheduledTask createTask(@RequestBody ScheduledTask task) {
        return scheduledTaskService.createTask(task);
    }

    /**
     * 获取所有任务
     */
    @GetMapping("/tasks")
    public List<ScheduledTask> getAllTasks() {
        return scheduledTaskService.getAllTasks();
    }

    /**
     * 停止任务
     */
    @PostMapping("/tasks/{taskId}/stop")
    public void stopTask(@PathVariable String taskId) {
        scheduledTaskService.stopTask(taskId);
    }

    /**
     * 删除任务
     */
    @DeleteMapping("/tasks/{taskId}")
    public void deleteTask(@PathVariable String taskId) {
        scheduledTaskService.deleteTask(taskId);
    }

    /**
     * 获取任务执行历史
     */
    @GetMapping("/tasks/{taskId}/history")
    public List<TaskRunLog> getTaskHistory(@PathVariable String taskId) {
        return scheduledTaskService.getTaskHistory(taskId);
    }
}
```

### 2.3.4 使用示例

**创建每日签到任务**

```bash
curl -X POST http://localhost:8080/api/scheduler/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "name": "原神每日签到",
    "cron": "0 9 * * *",
    "action": "执行原神签到",
    "command": "agent:打开浏览器，访问原神签到页面，完成签到"
  }'
```

**查看所有任务**

```bash
curl http://localhost:8080/api/scheduler/tasks
```

**查看任务执行历史**

```bash
curl http://localhost:8080/api/scheduler/tasks/{taskId}/history
```

---

## 三、Skills 插件系统

### 3.1 设计目标

- **可扩展**：用户可以自定义工具和技能
- **Markdown 格式**：使用 Markdown 定义技能（参考 Clawdbot）
- **动态加载**：无需重启即可加载新技能
- **安全隔离**：技能在沙箱中执行

### 3.2 Clawdbot 的实现参考

**核心文件**：`/Users/leocham/Documents/code/Agent_dev/clawdbot/src/agents/skills/`

**技能格式**：
```markdown
---
name: screenshot
description: Take a screenshot
category: system
---

# Screenshot Tool

Takes a screenshot of the current screen.

## Usage

```bash
screencapture -x ~/screenshot.png
```
```

### 3.3 Lavis 实现方案

#### 3.3.1 项目结构

```
src/main/java/com/lavis/
├── skills/
│   ├── SkillManager.java           # 技能管理器
│   ├── SkillLoader.java            # 技能加载器
│   ├── SkillExecutor.java          # 技能执行器
│   └── model/
│       ├── Skill.java              # 技能模型
│       └── SkillMetadata.java      # 技能元数据
└── config/
    └── SkillsConfig.java           # 技能配置
```

**技能目录结构**：
```
~/.lavis/skills/
├── screenshot/
│   └── SKILL.md
├── genshin-signin/
│   └── SKILL.md
└── custom-tool/
    └── SKILL.md
```

#### 3.3.2 核心实现

**Skill.java**

```java
package com.lavis.skills.model;

import lombok.Data;
import java.util.Map;

@Data
public class Skill {
    private String id;
    private SkillMetadata metadata;
    private String content;         // Markdown 内容
    private String command;         // 执行命令
    private Map<String, String> parameters;
}
```

**SkillMetadata.java**

```java
package com.lavis.skills.model;

import lombok.Data;

@Data
public class SkillMetadata {
    private String name;
    private String description;
    private String category;
    private String version;
    private String author;
}
```

**SkillManager.java**

```java
package com.lavis.skills;

import com.lavis.skills.model.Skill;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SkillManager {

    private final SkillLoader skillLoader;
    private final SkillExecutor skillExecutor;

    // 技能缓存
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    public SkillManager(SkillLoader skillLoader, SkillExecutor skillExecutor) {
        this.skillLoader = skillLoader;
        this.skillExecutor = skillExecutor;

        // 启动时加载所有技能
        loadAllSkills();
    }

    /**
     * 加载所有技能
     */
    public void loadAllSkills() {
        List<Skill> loadedSkills = skillLoader.loadSkills();
        for (Skill skill : loadedSkills) {
            skills.put(skill.getId(), skill);
        }
    }

    /**
     * 获取技能
     */
    public Skill getSkill(String skillId) {
        return skills.get(skillId);
    }

    /**
     * 获取所有技能
     */
    public List<Skill> getAllSkills() {
        return new ArrayList<>(skills.values());
    }

    /**
     * 执行技能
     */
    public String executeSkill(String skillId, Map<String, String> parameters) {
        Skill skill = skills.get(skillId);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + skillId);
        }

        return skillExecutor.execute(skill, parameters);
    }

    /**
     * 重新加载技能
     */
    public void reloadSkills() {
        skills.clear();
        loadAllSkills();
    }
}
```

**SkillLoader.java**

```java
package com.lavis.skills;

import com.lavis.skills.model.Skill;
import com.lavis.skills.model.SkillMetadata;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SkillLoader {

    private static final String SKILLS_DIR = System.getProperty("user.home") + "/.lavis/skills";

    /**
     * 加载所有技能
     */
    public List<Skill> loadSkills() {
        List<Skill> skills = new ArrayList<>();

        try {
            Files.createDirectories(Paths.get(SKILLS_DIR));

            Files.list(Paths.get(SKILLS_DIR))
                .filter(Files::isDirectory)
                .forEach(skillDir -> {
                    Path skillFile = skillDir.resolve("SKILL.md");
                    if (Files.exists(skillFile)) {
                        try {
                            Skill skill = parseSkillFile(skillFile);
                            skills.add(skill);
                        } catch (IOException e) {
                            // Log error
                        }
                    }
                });

        } catch (IOException e) {
            throw new RuntimeException("Failed to load skills", e);
        }

        return skills;
    }

    /**
     * 解析技能文件
     */
    private Skill parseSkillFile(Path skillFile) throws IOException {
        String content = Files.readString(skillFile);

        Skill skill = new Skill();
        skill.setId(skillFile.getParent().getFileName().toString());
        skill.setContent(content);

        // 解析 frontmatter
        SkillMetadata metadata = parseFrontmatter(content);
        skill.setMetadata(metadata);

        // 提取命令
        String command = extractCommand(content);
        skill.setCommand(command);

        return skill;
    }

    /**
     * 解析 frontmatter
     */
    private SkillMetadata parseFrontmatter(String content) {
        Pattern pattern = Pattern.compile("^---\\n(.*?)\\n---", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);

        SkillMetadata metadata = new SkillMetadata();

        if (matcher.find()) {
            String frontmatter = matcher.group(1);
            String[] lines = frontmatter.split("\\n");

            for (String line : lines) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();

                    switch (key) {
                        case "name":
                            metadata.setName(value);
                            break;
                        case "description":
                            metadata.setDescription(value);
                            break;
                        case "category":
                            metadata.setCategory(value);
                            break;
                        case "version":
                            metadata.setVersion(value);
                            break;
                        case "author":
                            metadata.setAuthor(value);
                            break;
                    }
                }
            }
        }

        return metadata;
    }

    /**
     * 提取命令
     */
    private String extractCommand(String content) {
        // 提取代码块中的命令
        Pattern pattern = Pattern.compile("```(?:bash|shell)\\n(.*?)\\n```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return "";
    }
}
```

**SkillExecutor.java**

```java
package com.lavis.skills;

import com.lavis.skills.model.Skill;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;

@Service
public class SkillExecutor {

    /**
     * 执行技能
     */
    public String execute(Skill skill, Map<String, String> parameters) {
        String command = skill.getCommand();

        // 替换参数
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            command = command.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }

        // 执行命令
        return executeCommand(command);
    }

    /**
     * 执行 Shell 命令
     */
    private String executeCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", command});

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );

            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            process.waitFor();
            return output.toString();

        } catch (Exception e) {
            throw new RuntimeException("Failed to execute command", e);
        }
    }
}
```

### 3.3.3 REST API

**SkillsController.java**

```java
package com.lavis.controller;

import com.lavis.skills.SkillManager;
import com.lavis.skills.model.Skill;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills")
public class SkillsController {

    private final SkillManager skillManager;

    public SkillsController(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    /**
     * 获取所有技能
     */
    @GetMapping
    public List<Skill> getAllSkills() {
        return skillManager.getAllSkills();
    }

    /**
     * 获取技能详情
     */
    @GetMapping("/{skillId}")
    public Skill getSkill(@PathVariable String skillId) {
        return skillManager.getSkill(skillId);
    }

    /**
     * 执行技能
     */
    @PostMapping("/{skillId}/execute")
    public String executeSkill(
            @PathVariable String skillId,
            @RequestBody Map<String, String> parameters) {
        return skillManager.executeSkill(skillId, parameters);
    }

    /**
     * 重新加载技能
     */
    @PostMapping("/reload")
    public void reloadSkills() {
        skillManager.reloadSkills();
    }
}
```

### 3.3.4 技能示例

**截图技能**（`~/.lavis/skills/screenshot/SKILL.md`）

```markdown
---
name: screenshot
description: Take a screenshot of the current screen
category: system
version: 1.0.0
author: Lavis Team
---

# Screenshot Tool

Takes a screenshot of the current screen and saves it to the specified path.

## Parameters

- `output`: Output file path (default: ~/screenshot.png)

## Usage

```bash
screencapture -x {{output}}
```
```

**原神签到技能**（`~/.lavis/skills/genshin-signin/SKILL.md`）

```markdown
---
name: genshin-signin
description: Genshin Impact daily check-in
category: automation
version: 1.0.0
author: Community
---

# Genshin Impact Daily Check-in

Automatically performs daily check-in for Genshin Impact.

## Parameters

- `username`: Your Genshin account username
- `password`: Your Genshin account password

## Usage

```bash
# This is a placeholder - actual implementation would use Agent
echo "Performing Genshin check-in for {{username}}"
```
```

### 3.3.5 集成到 Agent

**修改 AgentTools.java**

```java
@Service
public class AgentTools {

    private final SkillManager skillManager;

    @Tool("Execute a skill")
    public String executeSkill(
            @P("Skill ID") String skillId,
            @P("Parameters") Map<String, String> parameters) {

        return skillManager.executeSkill(skillId, parameters);
    }

    @Tool("List available skills")
    public String listSkills() {
        List<Skill> skills = skillManager.getAllSkills();
        return skills.stream()
            .map(skill -> skill.getMetadata().getName() + ": " +
                         skill.getMetadata().getDescription())
            .collect(Collectors.joining("\n"));
    }
}
```

---

## 四、实施计划

### 4.1 第一阶段：记忆管理（1 周）

- [ ] 实现 `MemoryManager`
- [ ] 实现 `ImageCleanupService`
- [ ] 实现 `ContextCompactor`
- [ ] 实现 `SessionStore`
- [ ] 集成到 `AgentService`
- [ ] 测试长时间运行（24 小时）

### 4.2 第二阶段：定时任务（1 周）

- [ ] 实现 `ScheduledTaskService`
- [ ] 实现 `TaskExecutor`
- [ ] 实现 `TaskStore`
- [ ] 实现 REST API
- [ ] 创建前端 UI（任务管理面板）
- [ ] 测试定时任务（每日签到）

### 4.3 第三阶段：Skills 系统（2 周）

- [ ] 实现 `SkillManager`
- [ ] 实现 `SkillLoader`
- [ ] 实现 `SkillExecutor`
- [ ] 实现 REST API
- [ ] 创建示例技能（screenshot, genshin-signin）
- [ ] 集成到 Agent Tools
- [ ] 创建前端 UI（技能市场）

### 4.4 第四阶段：测试与优化（1 周）

- [ ] 端到端测试
- [ ] 性能优化
- [ ] 文档完善
- [ ] 用户手册

---

## 五、总结

通过参考 Clawdbot 的成熟实现，Lavis 可以获得以下增强：

1. **记忆管理系统**：
   - 支持 7×24 小时长期运行
   - 自动清理历史截图和音频
   - 智能压缩对话历史
   - 会话持久化

2. **定时任务系统**：
   - 灵活的 Cron 调度
   - 任务持久化和恢复
   - 执行历史记录
   - 错误处理和重试

3. **Skills 插件系统**：
   - Markdown 格式定义技能
   - 动态加载和热重载
   - 参数化执行
   - 与 Agent 深度集成

这些功能将使 Lavis 从一个桌面自动化工具升级为一个完整的 AI 智能体平台，具备企业级的稳定性和可扩展性。

---

## 六、参考资料

- Clawdbot 源码：`/Users/leocham/Documents/code/Agent_dev/clawdbot/`
- Spring Boot 官方文档：https://spring.io/projects/spring-boot
- LangChain4j 文档：https://docs.langchain4j.dev/
- Cron 表达式：https://crontab.guru/
