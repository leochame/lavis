Project J-Agent: macOS 系统级自主智能体开发文档 (Java版)

1. 项目概述

J-Agent 是一个驻留在 macOS 菜单栏的 AI 智能体。不同于传统的 Chatbot，它具备“手”和“眼”，通过 macOS 底层辅助功能接口（Accessibility API）理解屏幕内容，并直接控制鼠标键盘执行任务。

2. 核心架构设计

系统采用 感知-决策-执行 (Perception-Brain-Action) 闭环架构。

模块一：感知层 (The Hybrid Eye)

负责将屏幕像素转化为结构化数据。

截图服务 (Snapshooter):

使用 java.awt.Robot.createScreenCapture。

关键处理： 必须检测 DPI缩放比例，确保截图与坐标系统一致。

UI 结构提取器 (AX-Dumper):

Level 1 (快速): 使用 AppleScript 获取当前前台窗口位置、尺寸。

Level 2 (深度): 递归扫描窗口内的 AXButton, AXTextField, AXTextArea, AXLink 等可交互元素。

输出： 生成一份扁平化的 UI 元素列表 (JSON)，包含元素的 (x, y, w, h) 和 description。

模块二：决策层 (The Brain)

集成 LLM (Gemini 2.0 Flash 或 GPT-4o)。

Prompt 策略 (Grounding):

输入： Screenshot (当前屏幕截图) + UI_Elements_JSON (文本化的 UI 树)。

System Prompt: > "你是一个 macOS 操作助手。我将提供当前屏幕截图和一份可交互元素的坐标列表。用户会给出指令。请判断用户意图，并从列表中找到最匹配的元素 ID。如果列表中没有匹配项，请返回 'USE_VISION_GRID'。"

坐标映射 (The Mapper):

如果 LLM 返回 ID，直接查表获得精确中心坐标。

如果 LLM 返回 'USE_VISION_GRID'，则启动传统的 10x10 网格视觉定位（作为兜底）。

模块三：执行层 (The Hand)

动作驱动 (ActionDriver):

封装 java.awt.Robot。

实现平滑鼠标移动 (Bezier 曲线轨迹)，模拟人类操作，避免触发某些软件的反脚本检测。

实现键盘输入（支持 Clipboard 粘贴，比逐字输入更快更准）。

3. 详细技术实现方案

3.1 核心数据结构

// 代表屏幕上的一个可操作元素
public class UIElement {
    public String id;       // 唯一标识符 (生成的索引)
    public String role;     // AXButton, AXTextField 等
    public String name;     // 按钮文字或标签
    public int x, y;        // 屏幕绝对坐标 (Points)
    public int w, h;        // 尺寸
    
    public Point getCenter() {
        return new Point(x + w / 2, y + h / 2);
    }
}
