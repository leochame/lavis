package com.lavis.service.tts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本清洗服务
 *
 * 用于清洗 LLM 回复文本，移除不适合 TTS 朗读的内容
 * 包括：Markdown 标记、代码块、URL、特殊符号等
 *
 * 设计原则：
 * 1. 清洗后的文本应该是自然语言，适合语音朗读
 * 2. 如果清洗后为空，表示该回复不适合语音播放
 */
@Slf4j
@Service
public class TextCleanerService {

    // 代码块模式 (```...```)
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
        "```[\\s\\S]*?```",
        Pattern.MULTILINE
    );

    // 行内代码模式 (`...`)
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`[^`]+`");

    // URL 模式
    private static final Pattern URL_PATTERN = Pattern.compile(
        "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+"
    );

    // Markdown 标题 (# ## ###)
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s*", Pattern.MULTILINE);

    // Markdown 粗体/斜体 (**text**, *text*, __text__, _text_)
    private static final Pattern BOLD_ITALIC_PATTERN = Pattern.compile(
        "(\\*\\*|__|\\*|_)(.+?)\\1"
    );

    // Markdown 链接 [text](url)
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\([^)]+\\)");

    // Markdown 列表标记 (- * 1.)
    private static final Pattern LIST_MARKER_PATTERN = Pattern.compile("^\\s*[-*]\\s+|^\\s*\\d+\\.\\s+", Pattern.MULTILINE);

    // 多余空白
    private static final Pattern EXTRA_WHITESPACE_PATTERN = Pattern.compile("\\s{2,}");

    // 句子分隔符（用于分句）
    private static final Pattern SENTENCE_SPLIT_PATTERN = Pattern.compile(
        "(?<=[。！？.!?])\\s*|(?<=\\n)\\s*"
    );

    /**
     * 清洗文本，移除不适合 TTS 的内容
     *
     * @param text 原始 LLM 回复文本
     * @return 清洗后的文本，如果不适合朗读则返回空字符串
     */
    public String clean(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String cleaned = text;

        // 1. 移除代码块
        cleaned = CODE_BLOCK_PATTERN.matcher(cleaned).replaceAll("");

        // 2. 移除行内代码
        cleaned = INLINE_CODE_PATTERN.matcher(cleaned).replaceAll("");

        // 3. 移除 URL
        cleaned = URL_PATTERN.matcher(cleaned).replaceAll("");

        // 4. 移除 Markdown 标题标记，保留文本
        cleaned = HEADING_PATTERN.matcher(cleaned).replaceAll("");

        // 5. 处理 Markdown 链接，保留链接文本
        cleaned = LINK_PATTERN.matcher(cleaned).replaceAll("$1");

        // 6. 移除粗体/斜体标记，保留文本
        Matcher boldItalicMatcher = BOLD_ITALIC_PATTERN.matcher(cleaned);
        cleaned = boldItalicMatcher.replaceAll("$2");

        // 7. 移除列表标记
        cleaned = LIST_MARKER_PATTERN.matcher(cleaned).replaceAll("");

        // 8. 清理多余空白
        cleaned = EXTRA_WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ");

        // 9. 去除首尾空白
        cleaned = cleaned.trim();

        // 10. 如果清洗后内容过短或只剩符号，返回空
        if (cleaned.length() < 2 || !containsReadableContent(cleaned)) {
            log.debug("Text cleaned to empty or unreadable: original length={}, cleaned length={}",
                text.length(), cleaned.length());
            return "";
        }

        log.debug("Text cleaned: {} chars -> {} chars", text.length(), cleaned.length());
        return cleaned;
    }

    /**
     * 将长文本分割为句子列表（用于流式 TTS）
     *
     * @param text 清洗后的文本
     * @return 句子列表
     */
    public List<String> splitToSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> sentences = new ArrayList<>();
        String[] parts = SENTENCE_SPLIT_PATTERN.split(text);

        StringBuilder buffer = new StringBuilder();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // 如果句子太短，合并到下一句
            if (trimmed.length() < 10 && buffer.length() > 0) {
                buffer.append(trimmed);
            } else if (buffer.length() > 0) {
                buffer.append(trimmed);
                sentences.add(buffer.toString());
                buffer = new StringBuilder();
            } else {
                // 如果句子足够长，直接添加
                if (trimmed.length() >= 10) {
                    sentences.add(trimmed);
                } else {
                    buffer.append(trimmed);
                }
            }
        }

        // 处理剩余内容
        if (buffer.length() > 0) {
            sentences.add(buffer.toString());
        }

        return sentences;
    }

    /**
     * 检查文本是否包含可朗读的内容（不只是符号和数字）
     */
    private boolean containsReadableContent(String text) {
        // 至少包含一个中文字符或英文字母
        return text.matches(".*[\\u4e00-\\u9fa5a-zA-Z].*");
    }

    /**
     * 快速检查文本是否主要是代码
     * 用于在清洗前快速判断是否需要跳过 TTS
     *
     * @param text 原始文本
     * @return true 表示主要是代码，不适合 TTS
     */
    public boolean isPrimarilyCode(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        // 计算代码块占比
        Matcher codeBlockMatcher = CODE_BLOCK_PATTERN.matcher(text);
        int codeLength = 0;
        while (codeBlockMatcher.find()) {
            codeLength += codeBlockMatcher.group().length();
        }

        // 如果代码块占比超过 50%，认为主要是代码
        double codeRatio = (double) codeLength / text.length();
        return codeRatio > 0.5;
    }
}
