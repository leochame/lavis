package com.lavis.memory;

import com.lavis.entity.SessionMessageEntity;
import com.lavis.repository.SessionMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视觉内容压缩器
 *
 * 实现基于 Turn 的视觉内容压缩策略：
 * - 首张图片 (Anchor): 保留完整 Base64，作为环境基准
 * - 中间图片 (Process): 替换为占位符，极致压缩 Token
 * - 末张图片 (Result): 保留完整 Base64，作为执行结果证明
 * - 异常帧 (Error): 保留完整 Base64，用于调试
 *
 * 压缩比预估：
 * - 768px 宽度截图: ~1,500 tokens
 * - 占位符文本: ~10 tokens
 * - 压缩比: 99.3%
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisualCompactor {

    private static final String PLACEHOLDER_FORMAT = "[Visual_Placeholder: %s]";
    private static final Pattern BASE64_IMAGE_PATTERN = Pattern.compile(
            "data:image/[^;]+;base64,([A-Za-z0-9+/=]+)",
            Pattern.CASE_INSENSITIVE);

    // 错误关键词，用于识别异常帧
    private static final List<String> ERROR_KEYWORDS = List.of(
            "error", "错误", "失败", "exception", "failed", "❌", "警告", "warning"
    );

    private final SessionMessageRepository messageRepository;
    private final ColdStorage coldStorage;

    /**
     * 压缩指定 Turn 的视觉内容
     *
     * @param turnId 要压缩的 Turn ID
     * @return 压缩结果
     */
    @Transactional
    public CompactionResult compactTurn(String turnId) {
        if (turnId == null) {
            return CompactionResult.empty();
        }

        // 获取该 Turn 中所有包含图片的消息
        List<SessionMessageEntity> imageMessages = messageRepository
                .findByTurnIdAndHasImageOrderByTurnPositionAsc(turnId, true);

        if (imageMessages.isEmpty()) {
            log.debug("Turn {} has no images to compact", turnId);
            return CompactionResult.empty();
        }

        if (imageMessages.size() <= 2) {
            // 只有 1-2 张图片，全部保留
            log.debug("Turn {} has only {} images, skipping compaction", turnId, imageMessages.size());
            return new CompactionResult(0, 0, imageMessages.size());
        }

        int compactedCount = 0;
        int archivedCount = 0;

        // 识别首尾锚点
        SessionMessageEntity firstImage = imageMessages.get(0);
        SessionMessageEntity lastImage = imageMessages.get(imageMessages.size() - 1);

        for (int i = 1; i < imageMessages.size() - 1; i++) {
            SessionMessageEntity msg = imageMessages.get(i);

            // 检查是否为异常帧
            if (isErrorFrame(msg)) {
                log.debug("Preserving error frame: {}", msg.getImageId());
                continue;
            }

            // 归档到冷存储
            String base64Data = extractBase64FromContent(msg.getContent());
            if (base64Data != null && msg.getImageId() != null) {
                coldStorage.archive(msg.getImageId(), base64Data);
                archivedCount++;
            }

            // 替换为占位符
            String compactedContent = replaceImageWithPlaceholder(msg.getContent(), msg.getImageId());
            msg.setContent(compactedContent);
            msg.setIsCompressed(true);
            messageRepository.save(msg);

            compactedCount++;
        }

        log.info("Compacted Turn {}: {} images compressed, {} archived, {} total",
                turnId, compactedCount, archivedCount, imageMessages.size());

        return new CompactionResult(compactedCount, archivedCount, imageMessages.size());
    }

    /**
     * 压缩指定会话中所有已完成的 Turn（排除当前 Turn）
     *
     * @param sessionId 会话 ID
     * @param currentTurnId 当前活跃的 Turn ID（不压缩）
     * @return 总压缩结果
     */
    @Transactional
    public CompactionResult compactPreviousTurns(String sessionId, String currentTurnId) {
        // 获取所有历史 Turn ID
        List<String> turnIds = messageRepository.findDistinctTurnIdsBySessionId(sessionId);

        int totalCompacted = 0;
        int totalArchived = 0;
        int totalImages = 0;

        for (String turnId : turnIds) {
            // 跳过当前 Turn
            if (turnId.equals(currentTurnId)) {
                continue;
            }

            CompactionResult result = compactTurn(turnId);
            totalCompacted += result.compactedCount();
            totalArchived += result.archivedCount();
            totalImages += result.totalImages();
        }

        return new CompactionResult(totalCompacted, totalArchived, totalImages);
    }

    /**
     * 从冷存储恢复图片
     *
     * @param imageId 图片 ID
     * @return Base64 编码的图片数据，未找到返回 null
     */
    public String restoreImage(String imageId) {
        return coldStorage.retrieve(imageId).orElse(null);
    }

    /**
     * 生成占位符
     */
    public String createPlaceholder(String imageId) {
        return String.format(PLACEHOLDER_FORMAT, imageId != null ? imageId : "unknown");
    }

    /**
     * 判断消息是否为异常帧
     */
    private boolean isErrorFrame(SessionMessageEntity message) {
        if (message == null || message.getContent() == null) {
            return false;
        }

        String content = message.getContent().toLowerCase();
        return ERROR_KEYWORDS.stream().anyMatch(content::contains);
    }

    /**
     * 从消息内容中提取 Base64 图片数据
     */
    private String extractBase64FromContent(String content) {
        if (content == null) {
            return null;
        }

        Matcher matcher = BASE64_IMAGE_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // 尝试直接匹配纯 Base64（无 data URI 前缀）
        if (content.length() > 1000 && isValidBase64(content)) {
            return content;
        }

        return null;
    }

    /**
     * 将消息内容中的图片替换为占位符
     */
    private String replaceImageWithPlaceholder(String content, String imageId) {
        if (content == null) {
            return createPlaceholder(imageId);
        }

        // 替换 data URI 格式的图片
        String result = BASE64_IMAGE_PATTERN.matcher(content)
                .replaceAll(createPlaceholder(imageId));

        // 如果内容本身就是纯 Base64，直接替换
        if (result.equals(content) && content.length() > 1000 && isValidBase64(content)) {
            return createPlaceholder(imageId);
        }

        return result;
    }

    /**
     * 简单验证是否为有效的 Base64 字符串
     */
    private boolean isValidBase64(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return str.matches("^[A-Za-z0-9+/=]+$");
    }

    /**
     * 压缩结果记录
     */
    public record CompactionResult(
            int compactedCount,
            int archivedCount,
            int totalImages
    ) {
        public static CompactionResult empty() {
            return new CompactionResult(0, 0, 0);
        }

        public long estimatedTokensSaved() {
            // 每张图片约 1500 tokens，占位符约 10 tokens
            return (long) compactedCount * (1500 - 10);
        }
    }
}
