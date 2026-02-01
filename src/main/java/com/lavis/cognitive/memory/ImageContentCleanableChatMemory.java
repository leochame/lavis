package com.lavis.cognitive.memory;

import com.lavis.memory.TurnContext;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 时序感知的 ChatMemory 实现 (Temporal Context Memory)
 *
 * 【Context Engineering 改造】
 * 原 ImageContentCleanableChatMemory 基于消息计数清理，缺乏对任务时序的理解。
 * 新版本支持：
 * - Turn 感知：识别当前 Turn 和历史 Turn
 * - 锚点保留：保留每个 Turn 的首尾图片
 * - 占位符替换：中间图片替换为占位符，极致压缩 Token
 *
 * 【内存安全策略】
 * - 当前 Turn：全量保留所有 ImageContent
 * - 历史 Turn：仅保留首尾锚点图片，中间替换为占位符
 * - 可节省 95%+ 的历史视觉开销
 *
 * 【轮次定义】
 * - 一轮 (Turn) = 从用户请求到最终回复的完整交互周期
 * - 通过 TurnContext.currentTurnId() 识别当前 Turn
 *
 * 实现方式：
 * - 内部使用 LinkedList 存储消息，支持修改
 * - 在 add() 方法中自动触发清理逻辑
 * - 线程安全（使用读写锁）
 */
@Slf4j
public class ImageContentCleanableChatMemory implements ChatMemory {

    private static final String PLACEHOLDER_FORMAT = "[Visual_Placeholder: %s]";
    private static final String LEGACY_PLACEHOLDER = "[历史截图已清理以节省内存]";

    private final LinkedList<ChatMessage> messages;
    private final int maxMessages;
    private final int keepRecentRounds; // 保留最近 N 轮（每轮 = 一个 UserMessage）
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Turn 追踪：记录每个消息所属的 Turn
    private final Map<Integer, String> messageTurnMap = new HashMap<>();
    // Turn 内图片追踪：记录每个 Turn 的图片位置
    private final Map<String, List<Integer>> turnImagePositions = new HashMap<>();

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
            int position = messages.size();
            messages.add(message);

            // 记录消息所属的 Turn
            String currentTurnId = TurnContext.currentTurnId();
            if (currentTurnId != null) {
                messageTurnMap.put(position, currentTurnId);

                // 如果是包含图片的消息，记录位置
                if (hasImageContent(message)) {
                    turnImagePositions
                            .computeIfAbsent(currentTurnId, k -> new ArrayList<>())
                            .add(position);
                }
            }

            // 如果超过最大消息数，移除最旧的消息
            while (messages.size() > maxMessages) {
                messages.removeFirst();
                // 更新索引映射（所有索引减 1）
                updateIndicesAfterRemoval();
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
            messageTurnMap.clear();
            turnImagePositions.clear();
            log.debug("ChatMemory cleared");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 【Context Engineering】时序感知的图片清理
     *
     * 策略：
     * 1. 当前 Turn 的所有图片：全量保留
     * 2. 历史 Turn 的图片：
     *    - 首张图片（Anchor）：保留
     *    - 末张图片（Result）：保留
     *    - 中间图片（Process）：替换为占位符
     */
    private void cleanupOldImageContents() {
        String currentTurnId = TurnContext.currentTurnId();

        // 遍历所有 Turn
        for (Map.Entry<String, List<Integer>> entry : turnImagePositions.entrySet()) {
            String turnId = entry.getKey();
            List<Integer> imagePositions = entry.getValue();

            // 跳过当前 Turn
            if (turnId.equals(currentTurnId)) {
                continue;
            }

            // 如果该 Turn 只有 1-2 张图片，全部保留
            if (imagePositions.size() <= 2) {
                continue;
            }

            // 压缩中间图片（保留首尾）
            for (int i = 1; i < imagePositions.size() - 1; i++) {
                int position = imagePositions.get(i);
                if (position >= 0 && position < messages.size()) {
                    ChatMessage message = messages.get(position);
                    if (message instanceof UserMessage userMsg && hasImageContent(userMsg)) {
                        UserMessage compacted = compactUserMessage(userMsg, turnId + "_" + i);
                        messages.set(position, compacted);
                    }
                }
            }
        }

        // 兼容旧逻辑：基于 UserMessage 计数的清理（用于没有 Turn 信息的消息）
        cleanupLegacyImageContents();
    }

    /**
     * 兼容旧逻辑：基于 UserMessage 计数的清理
     * 用于处理没有 Turn 信息的历史消息
     */
    private void cleanupLegacyImageContents() {
        // 从后往前收集所有 UserMessage 的索引
        List<Integer> userMessageIndices = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                // 只处理没有 Turn 信息的消息
                if (!messageTurnMap.containsKey(i)) {
                    userMessageIndices.add(i);
                }
            }
        }

        // 如果 UserMessage 数量不超过保留轮次，不需要清理
        if (userMessageIndices.size() <= keepRecentRounds) {
            return;
        }

        // 找到需要保留的最早 UserMessage 的索引
        int keepFromIndex = userMessageIndices.get(keepRecentRounds - 1);

        // 清理 keepFromIndex 之前的所有 UserMessage 中的 ImageContent
        int cleanedCount = 0;
        for (int i = 0; i < keepFromIndex; i++) {
            ChatMessage message = messages.get(i);

            if (message instanceof UserMessage userMsg && hasImageContent(userMsg)) {
                // 跳过有 Turn 信息的消息（由 Turn 感知逻辑处理）
                if (messageTurnMap.containsKey(i)) {
                    continue;
                }

                UserMessage cleanedMessage = compactUserMessage(userMsg, null);
                messages.set(i, cleanedMessage);
                cleanedCount++;
            }
        }

        if (cleanedCount > 0) {
            log.info("Legacy cleanup: {} old images compacted (keeping recent {} rounds)",
                    cleanedCount, keepRecentRounds);
        }
    }

    /**
     * 压缩 UserMessage，将 ImageContent 替换为占位符
     */
    private UserMessage compactUserMessage(UserMessage userMsg, String imageId) {
        StringBuilder textBuilder = new StringBuilder();
        for (Content content : userMsg.contents()) {
            if (content instanceof TextContent textContent) {
                textBuilder.append(textContent.text());
            }
        }
        String originalText = textBuilder.toString();

        String placeholder = imageId != null
                ? String.format(PLACEHOLDER_FORMAT, imageId)
                : LEGACY_PLACEHOLDER;

        if (!originalText.isBlank()) {
            return UserMessage.userMessage(
                    TextContent.from(originalText + "\n\n" + placeholder)
            );
        } else {
            return UserMessage.userMessage(TextContent.from(placeholder));
        }
    }

    /**
     * 检查消息是否包含 ImageContent
     */
    private boolean hasImageContent(ChatMessage message) {
        if (message instanceof UserMessage userMsg) {
            return userMsg.contents().stream()
                    .anyMatch(content -> content instanceof ImageContent);
        }
        return false;
    }

    /**
     * 更新索引映射（移除最旧消息后）
     */
    private void updateIndicesAfterRemoval() {
        // 更新 messageTurnMap
        Map<Integer, String> newMap = new HashMap<>();
        for (Map.Entry<Integer, String> entry : messageTurnMap.entrySet()) {
            int newIndex = entry.getKey() - 1;
            if (newIndex >= 0) {
                newMap.put(newIndex, entry.getValue());
            }
        }
        messageTurnMap.clear();
        messageTurnMap.putAll(newMap);

        // 更新 turnImagePositions
        for (List<Integer> positions : turnImagePositions.values()) {
            positions.replaceAll(pos -> pos - 1);
            positions.removeIf(pos -> pos < 0);
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

    /**
     * 获取 Turn 统计信息
     */
    public TurnStats getTurnStats() {
        lock.readLock().lock();
        try {
            int totalTurns = turnImagePositions.size();
            int totalImages = turnImagePositions.values().stream()
                    .mapToInt(List::size)
                    .sum();
            return new TurnStats(totalTurns, totalImages, messages.size());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Turn 统计信息
     */
    public record TurnStats(
            int totalTurns,
            int totalImages,
            int totalMessages
    ) {}
}

