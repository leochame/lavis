package com.lavis.agent.message;

/**
 * ToolCallResultMessage 使用示例
 * 展示如何在 GUI Agent 中返回包含图片的工具执lines结果
 */
public class ToolCallResultMessageExample {

    /**
     * 示例1：传统文本结果（兼容原有功能）
     */
    public static void textOnlyExample() {
        // 模拟工具执lines请求
        // ToolExecutionRequest request = ...;
        // String textResult = "点击操作has been completed";

        // 创建仅包含文本的结果消息
        // ToolCallResultMessage message = ToolCallResultMessage.from(request, textResult);
    }

    /**
     * 示例2：包含截图的工具结果（新功能）
     */
    public static void textWithImageExample() {
        // 模拟工具执lines请求和结果
        // ToolExecutionRequest request = ...;
        // String textResult = "截图has been 保存";
        // String base64Screenshot = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQ...";

        // 创建包含文本和图片的结果消息
        // ToolCallResultMessage message = ToolCallResultMessage.from(request, textResult, base64Screenshot);

        // 检查是否包含图片
        // boolean hasImage = message.hasImage();
        // ImageContent imageContent = message.getImageContent();
    }

    /**
     * 示例3：自定义内容组合
     */
    public static void customContentExample() {
        // 模拟工具执lines请求
        // ToolExecutionRequest request = ...;

        // 创建自定义内容columns表
        // List<Content> contents = Arrays.asList(
        //     TextContent.from("操作completed，以下是结果截图："),
        //     ImageContent.from(base64Screenshot, "image/jpeg"),
        //     TextContent.from("操作耗时：1.2seconds")
        // );

        // 创建自定义内容的结果消息
        // ToolCallResultMessage message = ToolCallResultMessage.from(request, contents);
    }

    /**
     * 在 AgentService 中的实际使用场景
     */
    public static void agentServiceUsageExample() {
        /*
        // 在工具执lines后，ifneed返回截图结果：

        String toolResult = toolExecutionService.executeUnified(toolName, toolArgs);

        // if是视觉相关的工具（如截图、UI操作etc），can 附加截图
        if (toolExecutionService.isVisualImpactTool(toolName)) {
            // 获取操作后的截图
            ScreenCapturer.ImageCapture capture = screenCapturer.captureWithDedup();
            String base64Image = capture.base64();

            // 创建包含文本结果和截图的消息
            ToolCallResultMessage toolResultMessage = ToolCallResultMessage.from(
                request,
                toolResult,
                base64Image
            );

            messages.add(toolResultMessage);
            chatMemory.add(toolResultMessage);
        } else {
            // 普通工具，只返回文本结果
            ToolCallResultMessage toolResultMessage = ToolCallResultMessage.from(request, toolResult);
            messages.add(toolResultMessage);
            chatMemory.add(toolResultMessage);
        }
        */
    }
}