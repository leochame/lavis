package com.lavis.perception;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.awt.*;

/**
 * 代表屏幕上的一个可操作 UI 元素
 * 核心数据结构，用于存储 Accessibility API 提取的元素信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UIElement {
    
    /**
     * 唯一标识符 (生成的索引，如 "btn_0", "text_1")
     */
    private String id;
    
    /**
     * 元素角色类型
     * 如: AXButton, AXTextField, AXTextArea, AXLink, AXStaticText, AXCheckBox 等
     */
    private String role;
    
    /**
     * 元素名称/标签/文本内容
     * 如按钮文字、输入框标签等
     */
    private String name;
    
    /**
     * 元素描述 (可选)
     */
    private String description;
    
    /**
     * 元素值 (对于输入框等有值的元素)
     */
    private String value;
    
    /**
     * 屏幕绝对坐标 X (Points - macOS 逻辑坐标)
     */
    private int x;
    
    /**
     * 屏幕绝对坐标 Y (Points - macOS 逻辑坐标)
     */
    private int y;
    
    /**
     * 元素宽度
     */
    private int width;
    
    /**
     * 元素高度
     */
    private int height;
    
    /**
     * 是否可点击
     */
    private boolean clickable;
    
    /**
     * 是否可聚焦
     */
    private boolean focusable;
    
    /**
     * 是否启用
     */
    private boolean enabled;
    
    /**
     * 所属窗口标题
     */
    private String windowTitle;
    
    /**
     * 所属应用名称
     */
    private String appName;
    
    /**
     * 元素层级深度
     */
    private int depth;
    
    /**
     * 获取元素中心点坐标
     */
    public Point getCenter() {
        return new Point(x + width / 2, y + height / 2);
    }
    
    /**
     * 获取元素边界矩形
     */
    public Rectangle getBounds() {
        return new Rectangle(x, y, width, height);
    }
    
    /**
     * 检查点是否在元素范围内
     */
    public boolean contains(int px, int py) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }
    
    /**
     * 检查是否为交互式元素
     */
    public boolean isInteractive() {
        return clickable || focusable || isInputField();
    }
    
    /**
     * 检查是否为输入字段
     */
    public boolean isInputField() {
        return "AXTextField".equals(role) || 
               "AXTextArea".equals(role) || 
               "AXSearchField".equals(role) ||
               "AXSecureTextField".equals(role);
    }
    
    /**
     * 检查是否为按钮类型
     */
    public boolean isButton() {
        return "AXButton".equals(role) || 
               "AXPopUpButton".equals(role) ||
               "AXMenuButton".equals(role);
    }
    
    /**
     * 检查是否为链接
     */
    public boolean isLink() {
        return "AXLink".equals(role);
    }
    
    /**
     * 检查是否为复选框
     */
    public boolean isCheckbox() {
        return "AXCheckBox".equals(role);
    }
    
    /**
     * 转换为简洁的 JSON 描述 (用于发送给 LLM)
     */
    public String toCompactJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":\"").append(id).append("\"");
        sb.append(",\"role\":\"").append(role).append("\"");
        if (name != null && !name.isEmpty()) {
            sb.append(",\"name\":\"").append(escapeJson(name)).append("\"");
        }
        if (value != null && !value.isEmpty()) {
            sb.append(",\"value\":\"").append(escapeJson(truncate(value, 50))).append("\"");
        }
        sb.append(",\"x\":").append(x);
        sb.append(",\"y\":").append(y);
        sb.append(",\"w\":").append(width);
        sb.append(",\"h\":").append(height);
        if (clickable) sb.append(",\"clickable\":true");
        sb.append("}");
        return sb.toString();
    }
    
    /**
     * 转换为人类可读的描述
     */
    public String toHumanReadable() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(id).append("] ");
        sb.append(getRoleSimpleName());
        if (name != null && !name.isEmpty()) {
            sb.append(" \"").append(truncate(name, 30)).append("\"");
        }
        sb.append(" at (").append(x).append(",").append(y).append(")");
        return sb.toString();
    }
    
    /**
     * 获取简化的角色名称
     */
    private String getRoleSimpleName() {
        if (role == null) return "Unknown";
        return role.replace("AX", "");
    }
    
    /**
     * 截断字符串
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
    
    /**
     * 转义 JSON 特殊字符
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}

