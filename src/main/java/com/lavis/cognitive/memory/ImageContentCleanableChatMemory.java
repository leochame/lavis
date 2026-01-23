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
 * - 自动清理超过 N 轮（默认 2 轮）之前的 ImageContent
 * - 保留 TextContent，添加占位符说明图片已被清理
 * - 可节省 90% 以上的堆内存，避免长时间运行导致 OOM
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
    private final int keepRecentRounds; // 保留最近 N 轮（每轮 = 用户消息 + AI 响应 = 2 条消息）
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 创建新的 ChatMemory 实例
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
     * 创建默认实例（保留最近 2 轮）
     */
    public static ImageContentCleanableChatMemory withMaxMessages(int maxMessages) {
        return new ImageContentCleanableChatMemory(maxMessages, 2);
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
     * 策略：将超过 keepRecentRounds 轮之前的 ImageContent 移除或替换为占位符
     */
    private void cleanupOldImageContents() {
        int keepRecentCount = keepRecentRounds * 2; // 每轮 = 2 条消息（用户 + AI）
        
        if (messages.size() <= keepRecentCount) {
            // 消息太少，不需要清理
            return;
        }

        int cleanedCount = 0;
        // 清理更早的消息中的 ImageContent（保留最近 keepRecentCount 条消息）
        for (int i = 0; i < messages.size() - keepRecentCount; i++) {
            ChatMessage message = messages.get(i);
            
            if (message instanceof UserMessage userMsg) {
                // 检查是否有 ImageContent
                boolean hasImage = userMsg.contents().stream()
                        .anyMatch(content -> content instanceof ImageContent);
                
                if (hasImage) {
                    // 移除 ImageContent，只保留 TextContent
                    List<Content> newContents = new ArrayList<>();
                    boolean hasTextContent = false;
                    
                    for (Content content : userMsg.contents()) {
                        if (content instanceof TextContent) {
                            newContents.add(content);
                            hasTextContent = true;
                        }
                        // 跳过 ImageContent
                    }
                    
                    // 如果有 TextContent，添加一个占位符说明图片已被清理
                    if (hasTextContent) {
                        // 手动提取所有 TextContent 的文本
                        StringBuilder textBuilder = new StringBuilder();
                        for (Content content : userMsg.contents()) {
                            if (content instanceof TextContent textContent) {
                                textBuilder.append(textContent.text());
                            }
                        }
                        String originalText = textBuilder.toString();
                        if (originalText != null && !originalText.isBlank()) {
                            // 创建新的 UserMessage，移除 ImageContent
                            UserMessage cleanedMessage = UserMessage.userMessage(
                                    TextContent.from(originalText + "\n\n[注：历史截图已清理以节省内存]")
                            );
                            messages.set(i, cleanedMessage);
                            cleanedCount++;
                        }
                    } else {
                        // 如果没有 TextContent，只保留占位符
                        UserMessage cleanedMessage = UserMessage.userMessage(
                                TextContent.from("[历史截图已清理以节省内存]")
                        );
                        messages.set(i, cleanedMessage);
                        cleanedCount++;
                    }
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

