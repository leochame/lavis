package com.lavis.memory;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turn 上下文管理器
 *
 * Turn 定义：从用户发起请求到收到最终回复的完整交互周期。
 * 一个 Turn 可能包含多轮工具调用和多张截图。
 *
 * 使用方式：
 * <pre>
 * TurnContext turn = TurnContext.begin(sessionId);
 * try {
 *     // 执行任务...
 *     turn.recordImage(imageId);
 * } finally {
 *     TurnContext.end();
 * }
 * </pre>
 */
@Slf4j
@Getter
public class TurnContext {

    private static final ThreadLocal<TurnContext> CURRENT = new ThreadLocal<>();

    private final String turnId;
    private final String sessionId;
    private final LocalDateTime startTime;
    private final List<String> imageIds;
    private final AtomicInteger messagePosition;

    private TurnContext(String sessionId) {
        this.turnId = UUID.randomUUID().toString();
        this.sessionId = sessionId;
        this.startTime = LocalDateTime.now();
        this.imageIds = Collections.synchronizedList(new ArrayList<>());
        this.messagePosition = new AtomicInteger(0);
    }

    /**
     * 开始新的 Turn
     *
     * @param sessionId 会话 ID
     * @return 新创建的 TurnContext
     * @throws IllegalStateException 如果当前线程已有活跃的 Turn
     */
    public static TurnContext begin(String sessionId) {
        if (CURRENT.get() != null) {
            log.warn("Turn already active for session {}, ending previous turn", sessionId);
            end();
        }

        TurnContext context = new TurnContext(sessionId);
        CURRENT.set(context);
        log.debug("Turn started: {} for session {}", context.turnId, sessionId);
        return context;
    }

    /**
     * 获取当前线程的 Turn 上下文
     *
     * @return 当前 TurnContext，如果没有活跃的 Turn 则返回 null
     */
    public static TurnContext current() {
        return CURRENT.get();
    }

    /**
     * 获取当前 Turn ID，如果没有活跃的 Turn 则返回 null
     */
    public static String currentTurnId() {
        TurnContext ctx = CURRENT.get();
        return ctx != null ? ctx.turnId : null;
    }

    /**
     * 结束当前 Turn
     *
     * @return 结束的 TurnContext，如果没有活跃的 Turn 则返回 null
     */
    public static TurnContext end() {
        TurnContext context = CURRENT.get();
        if (context != null) {
            CURRENT.remove();
            log.debug("Turn ended: {} with {} images", context.turnId, context.imageIds.size());
        }
        return context;
    }

    /**
     * 记录本轮产生的图片 ID
     */
    public void recordImage(String imageId) {
        if (imageId != null) {
            imageIds.add(imageId);
            log.trace("Image recorded in turn {}: {}", turnId, imageId);
        }
    }

    /**
     * 获取下一个消息位置编号
     */
    public int nextPosition() {
        return messagePosition.incrementAndGet();
    }

    /**
     * 获取当前消息位置（不递增）
     */
    public int currentPosition() {
        return messagePosition.get();
    }

    /**
     * 获取本轮第一张图片 ID
     */
    public String getFirstImageId() {
        return imageIds.isEmpty() ? null : imageIds.get(0);
    }

    /**
     * 获取本轮最后一张图片 ID
     */
    public String getLastImageId() {
        return imageIds.isEmpty() ? null : imageIds.get(imageIds.size() - 1);
    }

    /**
     * 判断指定图片是否为锚点图片（首张或末张）
     */
    public boolean isAnchorImage(String imageId) {
        if (imageId == null || imageIds.isEmpty()) {
            return false;
        }
        return imageId.equals(getFirstImageId()) || imageId.equals(getLastImageId());
    }

    /**
     * 获取本轮图片数量
     */
    public int getImageCount() {
        return imageIds.size();
    }

    @Override
    public String toString() {
        return String.format("TurnContext[turnId=%s, session=%s, images=%d, position=%d]",
                turnId, sessionId, imageIds.size(), messagePosition.get());
    }
}
