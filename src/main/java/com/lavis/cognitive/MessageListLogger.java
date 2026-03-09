package com.lavis.cognitive;

import dev.langchain4j.data.message.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MessageList 专用日志记录器 - 增量追加模式
 *
 * 核心特性：
 * 1. 追踪每items对话轮times的has been 记录消息数量
 * 2. 只记录新增的消息（增量追加）
 * 3. 避免重复记录相同的消息
 * 4. 格式化输出，便于阅读
 */
@Slf4j
@Component
public class MessageListLogger {

    private static final Logger MESSAGE_LOG = LoggerFactory.getLogger("MESSAGE_LIST");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Path LOG_DIR = Paths.get(".lavis", "logs");
    private static final Path MESSAGE_LOG_FILE = LOG_DIR.resolve("message-list.log");

    // 追踪每items对话轮timeshas been 记录的消息数量（线程安全）
    private final ConcurrentHashMap<String, AtomicInteger> recordedMessageCount = new ConcurrentHashMap<>();

    // when前对话轮times ID（用于区分不同的对话）
    private volatile String currentTurnId = null;

    public MessageListLogger() {
        try {
            Files.createDirectories(LOG_DIR);
        } catch (IOException e) {
            log.error("Failed to create log directory: {}", LOG_DIR, e);
        }
    }

    /**
     * start新的对话轮times
     */
    public void startNewTurn(String turnId) {
        this.currentTurnId = turnId;
        recordedMessageCount.putIfAbsent(turnId, new AtomicInteger(0));

        // 记录对话start标记
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("╔").append("═".repeat(78)).append("╗\n");
        sb.append(String.format("║ NEW TURN: %s | Time: %s%s║\n",
                turnId,
                LocalDateTime.now().format(TIME_FORMATTER),
                " ".repeat(78 - 32 - turnId.length())));
        sb.append("╚").append("═".repeat(78)).append("╝\n");

        writeToFile(sb.toString());
    }

    /**
     * endwhen前对话轮times
     */
    public void endTurn(int totalMessages, int llmLatencyMs, int toolCallCount) {
        if (currentTurnId == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("┌").append("─".repeat(78)).append("┐\n");
        sb.append(String.format("│ TURN END | Messages: %d | LLM: %dms | Tools: %d%s│\n",
                totalMessages, llmLatencyMs, toolCallCount,
                " ".repeat(78 - 50 - String.valueOf(totalMessages).length()
                        - String.valueOf(llmLatencyMs).length()
                        - String.valueOf(toolCallCount).length())));
        sb.append("└").append("─".repeat(78)).append("┘\n\n");

        writeToFile(sb.toString());
    }

    /**
     * 增量记录 MessageList（只记录新增的消息）
     *
     * @param messages      完整的消息columns表
     * @param llmLatencyMs  LLM 响应耗时
     * @param toolCallCount 工具调用数量
     */
    public void logMessageList(List<ChatMessage> messages, int llmLatencyMs, int toolCallCount) {
        if (currentTurnId == null) {
            // if没有显式start Turn，自动创建一items
            startNewTurn("turn-" + System.currentTimeMillis());
        }

        AtomicInteger recorded = recordedMessageCount.get(currentTurnId);
        if (recorded == null) {
            recorded = new AtomicInteger(0);
            recordedMessageCount.put(currentTurnId, recorded);
        }

        int alreadyRecorded = recorded.get();
        int totalMessages = messages.size();

        // 只记录新增的消息
        if (totalMessages > alreadyRecorded) {
            StringBuilder sb = new StringBuilder();

            // 记录迭代info
            sb.append(String.format("┌─ Iteration | Time: %s | LLM: %dms | Tools: %d ─┐\n",
                    LocalDateTime.now().format(TIME_FORMATTER), llmLatencyMs, toolCallCount));

            // 只追加新消息
            for (int i = alreadyRecorded; i < totalMessages; i++) {
                ChatMessage msg = messages.get(i);
                sb.append(formatMessage(i + 1, msg));
            }

            sb.append("└").append("─".repeat(78)).append("┘\n");

            writeToFile(sb.toString());

            // 更新has been 记录数量
            recorded.set(totalMessages);
        }
    }

    /**
     * 格式化单records消息
     */
    private String formatMessage(int index, ChatMessage message) {
        StringBuilder sb = new StringBuilder();
        sb.append("│ ");
        sb.append(String.format("[%d] ", index));

        if (message instanceof SystemMessage sysMsg) {
            sb.append("SYSTEM\n");
            sb.append("│   ").append(truncate(sysMsg.text(), 180)).append("\n");
        } else if (message instanceof UserMessage userMsg) {
            sb.append("USER");
            if (hasImage(userMsg)) {
                sb.append(" [+IMAGE]");
            }
            sb.append("\n");
            String text = extractText(userMsg);
            if (!text.isEmpty()) {
                sb.append("│   ").append(truncate(text, 180)).append("\n");
            }
        } else if (message instanceof AiMessage aiMsg) {
            sb.append("ASSISTANT");
            if (aiMsg.hasToolExecutionRequests()) {
                sb.append(" [TOOL_CALLS: ").append(aiMsg.toolExecutionRequests().size()).append("]");
            }
            sb.append("\n");
            if (aiMsg.text() != null && !aiMsg.text().isBlank()) {
                sb.append("│   Text: ").append(truncate(aiMsg.text(), 180)).append("\n");
            }
            if (aiMsg.hasToolExecutionRequests()) {
                for (var req : aiMsg.toolExecutionRequests()) {
                    sb.append("│   -> ").append(req.name()).append("(")
                            .append(truncate(req.arguments(), 80)).append(")\n");
                }
            }
        } else if (message instanceof ToolExecutionResultMessage toolMsg) {
            sb.append("TOOL_RESULT\n");
            sb.append("│   Tool: ").append(toolMsg.toolName()).append("\n");
            sb.append("│   Result: ").append(truncate(toolMsg.text(), 150)).append("\n");
        } else {
            sb.append("UNKNOWN\n");
            sb.append("│   ").append(truncate(message.toString(), 180)).append("\n");
        }

        return sb.toString();
    }

    /**
     * 检查 UserMessage 是否包含图片
     */
    private boolean hasImage(UserMessage msg) {
        return msg.contents().stream().anyMatch(c -> c instanceof ImageContent);
    }

    /**
     * 提取 UserMessage 的文本内容
     */
    private String extractText(UserMessage msg) {
        if (msg.hasSingleText()) {
            return msg.singleText();
        }
        return msg.contents().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .findFirst()
                .orElse("");
    }

    /**
     * 截断文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 写入文件
     */
    private void writeToFile(String content) {
        try {
            Files.writeString(MESSAGE_LOG_FILE, content,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to write message list log", e);
        }
    }
}
