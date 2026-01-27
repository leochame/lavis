# Lavis 增强计划：记忆管理 + 定时任务 + Skills 系统

## 概述

本文档参考 Clawdbot 的成熟实现，为 Lavis 设计三个核心增强功能：

1. **记忆管理系统**：支持长期运行，自动清理和压缩历史记忆
2. **定时任务系统**：实现 7×24 小时自动化任务（如每日签到）
3. **Skills 插件系统**：允许用户自定义工具和扩展功能

---

## 一、记忆管理系统（Memory Management）

### 1.1 设计目标

- **长期运行**：支持 7×24 小时运行，不会因内存溢出而崩溃
- **自动清理**：历史截图和音频自动清理
- **智能压缩**：对话历史自动总结，避免 context overflow
- **持久化**：会话历史保存到磁盘，重启后可恢复

### 1.2 Clawdbot 的实现参考

**核心文件**：`/Users/leocham/Documents/code/Agent_dev/clawdbot/src/agents/memory/`

**关键特性**：
```typescript
// 1. 自动清理图片内容
class ImageContentCleanableChatMemory {
    // 清理超过 N 条消息的图片内容
    cleanOldImages(maxMessages: number): void;
}

// 2. 会话持久化（JSONL 格式）
class SessionStore {
    // 增量写入，避免全量加载
    appendMessage(sessionId: string, message: Message): void;
}

// 3. 自动压缩
class ContextCompactor {
    // 当 token 数超过阈值时，自动总结
    compact(messages: Message[]): Message[];
}
```

### 1.3 Lavis 实现方案

#### 1.3.1 项目结构

```
src/main/java/com/lavis/
├── memory/
│   ├── MemoryManager.java           # 记忆管理器
│   ├── ImageCleanupService.java     # 图片清理服务
│   ├── ContextCompactor.java        # 上下文压缩器
│   └── SessionStore.java            # 会话持久化
└── config/
    └── MemoryConfig.java            # 记忆配置
```

#### 1.3.2 核心实现

**MemoryManager.java**

```java
package com.lavis.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MemoryManager {

    private final ImageCleanupService imageCleanupService;
    private final ContextCompactor contextCompactor;
    private final SessionStore sessionStore;

    // 内存中的消息缓存
    private final Map<String, List<ChatMessage>> messageCache = new ConcurrentHashMap<>();

    // 配置参数
    private static final int MAX_MESSAGES_IN_MEMORY = 50;  // 内存中最多保留 50 条消息
    private static final int MAX_IMAGES_IN_MEMORY = 10;    // 内存中最多保留 10 张图片
    private static final int MAX_TOKENS = 100000;          // 最大 token 数

    public MemoryManager(
            ImageCleanupService imageCleanupService,
            ContextCompactor contextCompactor,
            SessionStore sessionStore) {
        this.imageCleanupService = imageCleanupService;
        this.contextCompactor = contextCompactor;
        this.sessionStore = sessionStore;
    }

    /**
     * 添加消息到记忆
     */
    public void addMessage(String sessionId, ChatMessage message) {
        // 1. 添加到内存缓存
        messageCache.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);

        // 2. 持久化到磁盘
        sessionStore.appendMessage(sessionId, message);

        // 3. 检查是否需要清理
        checkAndCleanup(sessionId);
    }

    /**
     * 获取会话消息
     */
    public List<ChatMessage> getMessages(String sessionId) {
        return messageCache.getOrDefault(sessionId, new ArrayList<>());
    }

    /**
     * 检查并清理记忆
     */
    private void checkAndCleanup(String sessionId) {
        List<ChatMessage> messages = messageCache.get(sessionId);
        if (messages == null) return;

        // 1. 清理旧图片（保留最近 10 张）
        if (countImages(messages) > MAX_IMAGES_IN_MEMORY) {
            imageCleanupService.cleanOldImages(messages, MAX_IMAGES_IN_MEMORY);
        }

        // 2. 压缩上下文（如果 token 数过多）
        if (estimateTokens(messages) > MAX_TOKENS) {
            List<ChatMessage> compacted = contextCompactor.compact(messages);
            messageCache.put(sessionId, compacted);
            sessionStore.saveSession(sessionId, compacted);
        }

        // 3. 清理过多消息（保留最近 50 条）
        if (messages.size() > MAX_MESSAGES_IN_MEMORY) {
            List<ChatMessage> recent = messages.subList(
                messages.size() - MAX_MESSAGES_IN_MEMORY,
                messages.size()
            );
            messageCache.put(sessionId, new ArrayList<>(recent));
        }
    }

    /**
     * 定时清理任务（每小时执行一次）
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    public void scheduledCleanup() {
        for (String sessionId : messageCache.keySet()) {
            checkAndCleanup(sessionId);
        }

        // 清理磁盘上的旧会话文件（超过 30 天）
        sessionStore.cleanOldSessions(30);
    }

    /**
     * 统计消息中的图片数量
     */
    private int countImages(List<ChatMessage> messages) {
        return (int) messages.stream()
            .filter(msg -> msg.toString().contains("ImageContent"))
            .count();
    }

    /**
     * 估算 token 数（简单实现：字符数 / 4）
     */
    private int estimateTokens(List<ChatMessage> messages) {
        int totalChars = messages.stream()
            .mapToInt(msg -> msg.text().length())
            .sum();
        return totalChars / 4;
    }

    /**
     * 清空会话记忆
     */
    public void clearSession(String sessionId) {
        messageCache.remove(sessionId);
        sessionStore.deleteSession(sessionId);
    }
}
```

**ImageCleanupService.java**

```java
package com.lavis.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ImageCleanupService {

    /**
     * 清理旧图片，只保留最近 N 张
     */
    public void cleanOldImages(List<ChatMessage> messages, int keepCount) {
        int imageCount = 0;

        // 从后往前遍历，保留最近的图片
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);

            if (message instanceof UserMessage) {
                UserMessage userMsg = (UserMessage) message;
                List<Content> contents = userMsg.contents();

                // 统计并清理图片
                for (Content content : contents) {
                    if (content instanceof ImageContent) {
                        imageCount++;
                        if (imageCount > keepCount) {
                            // 清理图片内容（替换为占位符）
                            ((ImageContent) content).image().url(); // 清空 URL
                        }
                    }
                }
            }
        }
    }

    /**
     * 清理所有图片内容
     */
    public void cleanAllImages(List<ChatMessage> messages) {
        cleanOldImages(messages, 0);
    }
}
```

**ContextCompactor.java**

```java
package com.lavis.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ContextCompactor {

    private final ChatLanguageModel summaryModel;

    public ContextCompactor(ChatLanguageModel summaryModel) {
        this.summaryModel = summaryModel;
    }

    /**
     * 压缩上下文：总结历史对话
     */
    public List<ChatMessage> compact(List<ChatMessage> messages) {
        if (messages.size() <= 10) {
            return messages; // 消息太少，不需要压缩
        }

        // 1. 保留最近 10 条消息
        List<ChatMessage> recentMessages = messages.subList(
            messages.size() - 10,
            messages.size()
        );

        // 2. 总结之前的消息
        List<ChatMessage> oldMessages = messages.subList(0, messages.size() - 10);
        String summary = summarizeMessages(oldMessages);

        // 3. 构建新的消息列表
        List<ChatMessage> compacted = new ArrayList<>();
        compacted.add(SystemMessage.from("历史对话总结：" + summary));
        compacted.addAll(recentMessages);

        return compacted;
    }

    /**
     * 使用 LLM 总结消息
     */
    private String summarizeMessages(List<ChatMessage> messages) {
        String prompt = "请总结以下对话的关键信息（200 字以内）：\n\n" +
            messages.stream()
                .map(ChatMessage::text)
                .collect(java.util.stream.Collectors.joining("\n"));

        return summaryModel.generate(prompt);
    }
}
```

**SessionStore.java**

```java
package com.lavis.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class SessionStore {

    private static final String SESSION_DIR = System.getProperty("user.home") + "/.lavis/sessions";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionStore() {
        // 创建会话目录
        try {
            Files.createDirectories(Paths.get(SESSION_DIR));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create session directory", e);
        }
    }

    /**
     * 追加消息到会话文件（JSONL 格式）
     */
    public void appendMessage(String sessionId, ChatMessage message) {
        Path sessionFile = getSessionFile(sessionId);

        try (BufferedWriter writer = Files.newBufferedWriter(
                sessionFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {

            // 写入 JSON 行
            String json = objectMapper.writeValueAsString(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "type", message.type().toString(),
                "text", message.text()
            ));
            writer.write(json);
            writer.newLine();

        } catch (IOException e) {
            throw new RuntimeException("Failed to append message", e);
        }
    }

    /**
     * 保存完整会话
     */
    public void saveSession(String sessionId, List<ChatMessage> messages) {
        Path sessionFile = getSessionFile(sessionId);

        try (BufferedWriter writer = Files.newBufferedWriter(sessionFile)) {
            for (ChatMessage message : messages) {
                String json = objectMapper.writeValueAsString(Map.of(
                    "timestamp", LocalDateTime.now().toString(),
                    "type", message.type().toString(),
                    "text", message.text()
                ));
                writer.write(json);
                writer.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save session", e);
        }
    }

    /**
     * 加载会话
     */
    public List<ChatMessage> loadSession(String sessionId) {
        Path sessionFile = getSessionFile(sessionId);
        if (!Files.exists(sessionFile)) {
            return new ArrayList<>();
        }

        List<ChatMessage> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(sessionFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 解析 JSON 行并转换为 ChatMessage
                // 这里简化处理，实际需要根据 type 创建不同类型的消息
                messages.add(SystemMessage.from(line));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load session", e);
        }

        return messages;
    }

    /**
     * 删除会话
     */
    public void deleteSession(String sessionId) {
        Path sessionFile = getSessionFile(sessionId);
        try {
            Files.deleteIfExists(sessionFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete session", e);
        }
    }

    /**
     * 清理旧会话（超过指定天数）
     */
    public void cleanOldSessions(int daysToKeep) {
        try {
            Files.list(Paths.get(SESSION_DIR))
                .filter(Files::isRegularFile)
                .filter(file -> {
                    try {
                        long lastModified = Files.getLastModifiedTime(file).toMillis();
                        long daysOld = (System.currentTimeMillis() - lastModified) / (1000 * 60 * 60 * 24);
                        return daysOld > daysToKeep;
                    } catch (IOException e) {
                        return false;
                    }
                })
                .forEach(file -> {
                    try {
                        Files.delete(file);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
        } catch (IOException e) {
            throw new RuntimeException("Failed to clean old sessions", e);
        }
    }

    /**
     * 获取会话文件路径
     */
    private Path getSessionFile(String sessionId) {
        return Paths.get(SESSION_DIR, sessionId + ".jsonl");
    }
}
```

#### 1.3.3 配置文件

**application.properties**

```properties
# 记忆管理配置
lavis.memory.max-messages=50
lavis.memory.max-images=10
lavis.memory.max-tokens=100000
lavis.memory.cleanup-interval=3600000
lavis.memory.session-retention-days=30
```

#### 1.3.4 集成到现有系统

**修改 AgentService.java**

```java
@Service
public class AgentService {

    private final MemoryManager memoryManager;

    public AgentService(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    public String chat(String message) {
        String sessionId = "main"; // 单会话

        // 1. 获取历史消息
        List<ChatMessage> history = memoryManager.getMessages(sessionId);

        // 2. 添加用户消息
        UserMessage userMessage = UserMessage.from(message);
        memoryManager.addMessage(sessionId, userMessage);

        // 3. 调用 LLM
        String response = chatModel.generate(history);

        // 4. 保存 AI 响应
        AiMessage aiMessage = AiMessage.from(response);
        memoryManager.addMessage(sessionId, aiMessage);

        return response;
    }
}
```

---

## 二、定时任务系统（Scheduled Tasks）

### 2.1 设计目标

- **灵活调度**：支持 Cron 表达式和一次性任务
- **持久化**：任务配置保存到磁盘，重启后恢复
- **执行历史**：记录任务执行历史
- **错误处理**：任务失败自动重试

### 2.2 Clawdbot 的实现参考

**核心文件**：`/Users/leocham/Documents/code/Agent_dev/clawdbot/src/cron/service.ts`

**关键特性**：
```typescript
// 1. 任务定义
interface CronJob {
    id: string;
    cron: string;        // "0 9 * * *" (每天 9 点)
    action: string;      // "执行原神签到"
    enabled: boolean;
}

// 2. 任务调度
class CronService {
    schedule(job: CronJob): void;
    unschedule(jobId: string): void;
    getRunHistory(jobId: string): RunLog[];
}
```

### 2.3 Lavis 实现方案

#### 2.3.1 项目结构

```
src/main/java/com/lavis/
├── scheduler/
│   ├── ScheduledTaskService.java    # 任务调度服务
│   ├── TaskStore.java               # 任务持久化
│   ├── TaskExecutor.java            # 任务执行器
│   └── model/
│       ├── ScheduledTask.java       # 任务模型
│       └── TaskRunLog.java          # 执行日志
└── config/
    └── SchedulerConfig.java         # 调度器配置
```

#### 2.3.2 核心实现

**ScheduledTask.java**

```java
package com.lavis.scheduler.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ScheduledTask {
    private String id;
    private String name;
    private String cron;              // Cron 表达式
    private String action;            // 任务描述（如"执行原神签到"）
    private String command;           // 实际执行的命令
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime lastRunAt;
    private String lastRunStatus;     // SUCCESS, FAILED
}
```