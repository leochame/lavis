package com.lavis.agent.message;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 工具调用结果消息，支持文本和图片内容
 * 替代 ToolExecutionResultMessage，新增图片支持用于 GUI Agent 场景
 */
public class ToolCallResultMessage implements ChatMessage {

    private final String id;
    private final String toolName;
    private final List<Content> contents;

    private ToolCallResultMessage(String id, String toolName, List<Content> contents) {
        this.id = id;
        this.toolName = toolName;
        this.contents = new ArrayList<>(contents);
    }

    /**
     * 创建仅包含文本的工具结果消息（兼容原有功能）
     */
    public static ToolCallResultMessage from(ToolExecutionRequest request, String text) {
        return new ToolCallResultMessage(
                request.id(),
                request.name(),
                List.of(TextContent.from(text))
        );
    }

    /**
     * 创建包含文本和图片的工具结果消息
     */
    public static ToolCallResultMessage from(ToolExecutionRequest request, String text, String base64Image) {
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(text));
        contents.add(ImageContent.from(base64Image, "image/jpeg"));

        return new ToolCallResultMessage(request.id(), request.name(), contents);
    }

    /**
     * 创建自定义内容的工具结果消息
     */
    public static ToolCallResultMessage from(ToolExecutionRequest request, List<Content> contents) {
        return new ToolCallResultMessage(request.id(), request.name(), contents);
    }

    @Override
    public ChatMessageType type() {
        return ChatMessageType.TOOL_EXECUTION_RESULT;
    }

    public String id() {
        return id;
    }

    public String toolName() {
        return toolName;
    }

    public List<Content> contents() {
        return new ArrayList<>(contents);
    }

    /**
     * 获取文本内容（兼容原有 text() 方法）
     */
    public String text() {
        return contents.stream()
                .filter(content -> content instanceof TextContent)
                .map(content -> ((TextContent) content).text())
                .findFirst()
                .orElse("");
    }

    /**
     * 检查是否包含图片
     */
    public boolean hasImage() {
        return contents.stream().anyMatch(content -> content instanceof ImageContent);
    }

    /**
     * 获取图片内容
     */
    public ImageContent getImageContent() {
        return contents.stream()
                .filter(content -> content instanceof ImageContent)
                .map(content -> (ImageContent) content)
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ToolCallResultMessage that = (ToolCallResultMessage) o;
        return Objects.equals(id, that.id) &&
               Objects.equals(toolName, that.toolName) &&
               Objects.equals(contents, that.contents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, toolName, contents);
    }

    @Override
    public String toString() {
        return "ToolCallResultMessage{" +
               "id='" + id + '\'' +
               ", toolName='" + toolName + '\'' +
               ", contents=" + contents +
               '}';
    }
}