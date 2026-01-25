package com.lavis.cognitive.memory;

import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 支持细粒度清理 ImageContent 的自定义 ChatMemory 实现
 *
 * 【内存安全策略】
 * - 自动清理超过 N 轮之前的 ImageContent
 * - 保留 TextContent，添加占位符说明图片已被清理
 * - 可节省 90% 以上的堆内存，避免长时间运行导致 OOM
 *
 * 【轮次定义】
 * - 一轮 = 一个 UserMessage（通常包含截图）
 * - 实际消息序列：UserMessage -> AiMessage -> ToolResultMessage -> AiMessage...
 * - 基于 UserMessage 数量计算轮次，而非固定消息数
 *
 * 实现方式：
 * - 内部使用 LinkedList 存储消息，支持修改
 * - 在 add() 方法中自动触发清理逻辑
 * - 线程安全（使用读写锁）
 */
@Slf4j
public class ImageContentCleanableChatMemory implements ChatMemory {

    private final LinkedList<ChatMessage> messages;
    private final int maxMessages;
    private final int keepRecentRounds; // 保留最近 N 轮（每轮 = 一个 UserMessage）
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 创建新的 ChatMemory 实例
     *
     * 
     * @param maxMessages 最大消息数
     * @param keepRecentRounds 保留最近 N 轮的完整内容（包括 ImageContent）
     */
    public ImageContentCleanableChatMemory(int maxMessages, int keepRecentRounds) {
        this.messages = new LinkedList<>();
        this.maxMessages = maxMessages;
        this.keepRecentRounds = keepRecentRounds;
    }

    /**
     * 创建默认实例（保留最近 4 轮）
     * 4 轮可以保留足够的视觉上下文用于反思和对比
     */
    public static ImageContentCleanableChatMemory withMaxMessages(int maxMessages) {
        return new ImageContentCleanableChatMemory(maxMessages, 4);
    }

    @Override
    public Object id() {
        return this;
    }

    @Override
    public void add(ChatMessage message) {
        lock.writeLock().lock();
        try {
            messages.add(message);

            // 如果超过最大消息数，移除最旧的消息
            while (messages.size() > maxMessages) {
                messages.removeFirst();
            }

            // 自动清理旧消息中的 ImageContent
            cleanupOldImageContents();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<ChatMessage> messages() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(messages);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            messages.clear();
            log.debug("🧹 ChatMemory 已清空");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 【内存安全】清理旧消息中的 ImageContent
     *
     * 策略：基于 UserMessage 数量计算轮次
     * - 从后往前找到第 N 个 UserMessage 的位置
     * - 该位置之前的所有 UserMessage 中的 ImageContent 都清理掉
     */
    private void cleanupOldImageContents() {
        // 1. 从后往前收集所有 UserMessage 的索引
        List<Integer> userMessageIndices = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                userMessageIndices.add(i);
            }
        }

        // 2. 如果 UserMessage 数量不超过保留轮次，不需要清理
        if (userMessageIndices.size() <= keepRecentRounds) {
            return;
        }

        // 3. 找到需要保留的最早 UserMessage 的索引
        // userMessageIndices 是倒序的，所以 index=keepRecentRounds-1 是第 N 个最近的 UserMessage
        int keepFromIndex = userMessageIndices.get(keepRecentRounds - 1);

        // 4. 清理 keepFromIndex 之前的所有 UserMessage 中的 ImageContent
        int cleanedCount = 0;
        for (int i = 0; i < keepFromIndex; i++) {
            ChatMessage message = messages.get(i);

            if (message instanceof UserMessage userMsg) {
                // 检查是否有 ImageContent
                boolean hasImage = userMsg.contents().stream()
                        .anyMatch(content -> content instanceof ImageContent);

                if (hasImage) {
                    // 提取所有 TextContent 的文本
                    StringBuilder textBuilder = new StringBuilder();
                    for (Content content : userMsg.contents()) {
                        if (content instanceof TextContent textContent) {
                            textBuilder.append(textContent.text());
                        }
                    }
                    String originalText = textBuilder.toString();

                    // 创建新的 UserMessage，移除 ImageContent
                    UserMessage cleanedMessage;
                    if (!originalText.isBlank()) {
                        cleanedMessage = UserMessage.userMessage(
                                TextContent.from(originalText + "\n\n[注：历史截图已清理以节省内存]")
                        );
                    } else {
                        cleanedMessage = UserMessage.userMessage(
                                TextContent.from("[历史截图已清理以节省内存]")
                        );
                    }
                    messages.set(i, cleanedMessage);
                    cleanedCount++;
                }
            }
        }

        if (cleanedCount > 0) {
            log.info("🧹 清理了 {} 条历史消息中的旧截图，节省内存（保留最近 {} 轮完整内容）",
                    cleanedCount, keepRecentRounds);
        }
    }

    /**
     * 手动触发清理（用于测试或特殊场景）
     */
    public void forceCleanup() {
        lock.writeLock().lock();
        try {
            cleanupOldImageContents();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取当前消息数量
     */
    public int size() {
        lock.readLock().lock();
        try {
            return messages.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}

