# Gemini Hackathon 改进建议（15天开发周期）

本文档针对 Gemini Hackathon 提出**务实、聚焦**的改进建议，帮助项目在15天开发周期内更好地展示 Gemini 3.0-flash 的核心能力。

## 🎯 核心原则

1. **聚焦自动化任务执行**：本项目是 macOS 自动化助手，不是聊天系统
2. **展示 Gemini 核心能力**：多模态理解、工具调用、快速响应
3. **提升用户体验**：让用户清楚知道任务执行状态和进度
4. **务实开发**：避免过度工程化，优先高价值功能

## 📋 目录

1. [增强执行状态推送](#1-增强执行状态推送) ⭐⭐⭐⭐⭐
2. [多模态能力增强](#2-多模态能力增强) ⭐⭐⭐⭐
3. [演示脚本和 Demo](#3-演示脚本和-demo) ⭐⭐⭐⭐⭐
4. [文档和展示](#4-文档和展示) ⭐⭐⭐⭐⭐
5. [工具调用优化](#5-工具调用优化) ⭐⭐⭐
6. [性能优化](#6-性能优化) ⭐⭐⭐

---

## 1. 增强执行状态推送 ⭐⭐⭐⭐⭐

### 当前状态
- ✅ 已有 WebSocket 推送工作流状态（步骤开始/完成/失败）
- ✅ 前端已有 WorkflowPanel、TaskPanel 显示进度
- ⚠️ **缺少**：工具调用的详细状态、AI 思考过程摘要、视觉分析结果

### 为什么重要
- **用户需求**：用户想知道"AI 现在在做什么"、"为什么这样做"
- **展示 Gemini 能力**：展示 AI 的思考过程、视觉理解能力
- **提升信任度**：让用户看到 AI 的决策过程，增强信任

### 改进建议

#### 1.1 推送工具调用详情
```java
// 在 AgentService.processWithTools() 中
// 当执行工具时，推送详细信息

// 执行工具前
workflowEventService.onToolCalling(toolName, toolArgs, "准备点击登录按钮 (坐标: 500, 300)");

// 执行工具后
workflowEventService.onToolExecuted(toolName, result, success, "已点击登录按钮，等待页面响应");
```

#### 1.2 推送 AI 思考摘要
```java
// 在 AgentService.processWithTools() 中
// 当 AI 生成响应时，提取关键信息并推送

// 解析 AI 响应，提取：
// - 视觉分析结果："识别到登录按钮，位于屏幕中央"
// - 决策原因："检测到登录表单已加载，可以开始输入"
// - 下一步计划："将点击用户名输入框"

workflowEventService.onThinking("正在分析屏幕...", "识别到 3 个可点击按钮：登录、注册、忘记密码");
```

#### 1.3 推送视觉分析结果
```java
// 在重新截图后，推送视觉分析摘要
workflowEventService.onVisualAnalysis(Map.of(
    "elements_detected", 5,
    "buttons_found", 2,
    "text_fields", 1,
    "summary", "检测到登录表单，包含用户名、密码输入框和登录按钮"
));
```

#### 1.4 前端显示增强
- 在 WorkflowPanel 中显示当前工具调用状态
- 在 BrainPanel 中显示 AI 思考摘要
- 添加"思考中"状态，显示 AI 正在分析的内容

#### 1.5 实现位置
- `src/main/java/com/lavis/cognitive/AgentService.java` (添加推送调用)
- `src/main/java/com/lavis/websocket/WorkflowEventService.java` (添加新事件类型)
- `frontend/src/components/WorkflowPanel.tsx` (显示工具调用状态)
- `frontend/src/components/BrainPanel.tsx` (显示思考摘要)

**预计工作量**: 1-2 天  
**价值**: 显著提升用户体验，展示 Gemini 的思考过程

---

## 2. 多模态能力增强 ⭐⭐⭐⭐

### 当前状态
- ✅ 已支持图像（截图）输入
- ✅ 已支持音频（ASR）输入
- ⚠️ Prompt 可以更明确地指导 Gemini 进行视觉分析
- ⚠️ 未充分利用 Gemini 3.0-flash 的视觉理解能力

### 为什么重要
- **展示 Gemini 核心能力**：多模态理解是 Gemini 的强项
- **提升执行成功率**：更好的视觉理解 = 更准确的坐标定位
- **Hackathon 展示**：视觉分析能力是很好的演示点

### 改进建议

#### 2.1 增强图像理解 Prompt
```java
// 在 AgentService.SYSTEM_PROMPT 中增强视觉分析指导
private static final String SYSTEM_PROMPT = """
    You are Lavis, a professional macOS automation assistant powered by Gemini 3.0-flash.
    
    ## Visual Analysis Capabilities (Gemini 3.0-flash):
    You have advanced visual understanding capabilities:
    - **Precise UI element detection**: Identify buttons, text fields, menus, icons, checkboxes, radio buttons
    - **Text recognition (OCR)**: Extract all visible text from screenshots, including button labels, menu items, window titles
    - **Layout understanding**: Understand spatial relationships between elements (above, below, left, right)
    - **State detection**: Identify active windows, focused elements, dialog states, loading indicators
    - **Color and style recognition**: Identify disabled buttons (grayed out), highlighted elements, error states
    
    ## Coordinate System (Critical):
    - **Red cross marker**: Current mouse position with coordinates (e.g., "Mouse at (500, 300)")
    - **Green circle**: Last click position with label
    - **Always use coordinates shown in screenshot** for operations - do not guess coordinates
    
    ## Visual Analysis Workflow:
    1. **Observe**: Carefully analyze the screenshot, identify all interactive elements
    2. **Describe**: Verbally describe what you see (for user understanding)
    3. **Locate**: Find the target element and note its coordinates from the screenshot
    4. **Verify**: Before clicking, verify the element is visible and clickable
    5. **Confirm**: After clicking, verify the action succeeded by analyzing the new screenshot
    
    ...
    """;
```

#### 2.2 添加视觉分析结果提取
```java
// 在 AgentService 中，解析 AI 响应，提取视觉分析信息
// 用于推送给前端展示

private String extractVisualAnalysis(String aiResponse) {
    // 使用简单的正则或关键词提取
    // 例如："识别到登录按钮，位于 (500, 300)"
    // 推送给前端显示
}
```

#### 2.3 实现位置
- `src/main/java/com/lavis/cognitive/AgentService.java` (优化 SYSTEM_PROMPT)
- `src/main/java/com/lavis/cognitive/AgentService.java` (添加视觉分析提取)

**预计工作量**: 0.5-1 天  
**价值**: 展示 Gemini 多模态能力，提升执行成功率

---

## 3. 演示脚本和 Demo ⭐⭐⭐⭐⭐

### 为什么最重要
- **Hackathon 展示**：演示脚本是评委了解项目的第一印象
- **展示 Gemini 能力**：通过精心设计的场景展示 Gemini 3.0-flash 的核心能力
- **时间投入回报高**：1-2 天投入，对 Hackathon 展示至关重要

### 改进建议

#### 3.1 创建 Gemini Hackathon 专用演示脚本
```bash
# scripts/demo-gemini-hackathon.sh
#!/bin/bash
# 展示 Gemini 3.0-flash 的核心能力：
# 1. 快速响应（flash 模型）
# 2. 多模态理解（视觉+语音）
# 3. 工具调用和自我纠正
# 4. 复杂任务规划

echo "🚀 Lavis - Powered by Gemini 3.0-flash"
echo "======================================"
echo ""
echo "演示场景 1: 视觉理解 + 精确点击"
echo "场景: 打开 Finder，找到并点击'下载'文件夹"
# ... 执行演示
```

#### 3.2 演示场景设计（3-5 个）

**场景 1: 视觉理解 + 精确操作** (2-3 分钟)
- 目标：展示 Gemini 的视觉理解能力
- 任务："打开 Finder，找到并点击'下载'文件夹，然后打开第一个文件"
- 亮点：展示 AI 如何识别 UI 元素、定位坐标、执行操作

**场景 2: 复杂任务规划** (3-5 分钟)
- 目标：展示 Gemini 的任务规划能力
- 任务："帮我创建一个新的文本文件，写入'Hello Gemini'，然后保存到桌面"
- 亮点：展示 Planner -> Executor -> Reflector 的完整流程

**场景 3: 自我纠正能力** (2-3 分钟)
- 目标：展示 Gemini 的错误恢复能力
- 任务："点击一个不存在的按钮，观察 AI 如何自我纠正"
- 亮点：展示 AI 如何检测失败、分析原因、尝试替代方案

**场景 4: 多模态交互** (1-2 分钟)
- 目标：展示语音输入 + 视觉理解
- 任务：使用语音输入"打开计算器"，然后通过截图理解并执行
- 亮点：展示端到端的语音+视觉交互

**场景 5: 快速响应** (1 分钟)
- 目标：展示 Gemini 3.0-flash 的快速响应
- 任务：执行多个简单任务，展示响应速度
- 亮点：对比 flash 模型的快速响应

#### 3.3 实现位置
- `scripts/demo-gemini-hackathon.sh`
- `docs/demo-scenarios.md` (详细场景说明)
- `docs/demo-video-script.md` (视频录制脚本)

**预计工作量**: 1-2 天  
**价值**: 对 Hackathon 展示至关重要

---

## 4. 文档和展示 ⭐⭐⭐⭐⭐

### 改进建议

#### 4.1 创建 Gemini Hackathon 专用 README
```markdown
# Lavis - Powered by Gemini 3.0-flash

## 🎯 Gemini 能力展示
- ✅ **多模态理解**：视觉（截图）+ 语音（ASR）
- ✅ **快速响应**：Gemini 3.0-flash 模型
- ✅ **工具调用**：完整的工具调用循环
- ✅ **任务规划**：Planner -> Executor -> Reflector 架构
- ✅ **自我纠正**：失败检测和自动恢复

## 🚀 快速开始
...
```

#### 4.2 添加演示视频/截图
- 录制 3-5 分钟演示视频
- 添加关键场景的 GIF 动图
- 截图展示工作流状态、思考过程

#### 4.3 实现位置
- `README-GEMINI-HACKATHON.md`
- `docs/gemini-capabilities.md`
- `docs/demo-screenshots/` (截图目录)

**预计工作量**: 1 天  
**价值**: 帮助评委快速理解项目

---

## 5. 工具调用优化 ⭐⭐⭐

### 当前状态
- ✅ 已实现工具调用循环
- ✅ 支持工具执行后重新截图
- ⚠️ 可以优化工具调用的 prompt 和错误处理

### 改进建议

#### 5.1 优化工具调用 Prompt
```java
// 在 SYSTEM_PROMPT 中更明确地指导 Gemini 如何使用工具
"""
## Tool Calling Guidelines:
- Always call tools when user requests actions
- Use precise coordinates from screenshot markers (red cross shows current position)
- After tool execution, analyze the new screenshot to verify success
- If action fails, try alternative strategies:
  * Different coordinates (adjust 5-30 pixels)
  * Different approach (right-click menu, keyboard shortcut)
  * Wait longer for UI to respond
- Maximum 3 retries for the same action before reporting failure
"""
```

#### 5.2 工具调用结果优化
- 更清晰的工具执行结果格式
- 更好的错误处理和重试逻辑

#### 5.3 实现位置
- `src/main/java/com/lavis/cognitive/AgentService.java`
- `src/main/java/com/lavis/cognitive/AgentTools.java`

**预计工作量**: 0.5-1 天  
**价值**: 提升任务执行成功率

---

## 6. 性能优化 ⭐⭐⭐

### 改进建议

#### 6.1 利用 Gemini 3.0-flash 的快速响应
```properties
# application.properties
# 优化超时设置，利用 flash 的快速响应
app.llm.models.model.timeout-seconds=60
app.llm.models.model.temperature=0.3  # 降低温度，提升响应速度
```

#### 6.2 截图优化
- 截图压缩优化（保持质量的同时减小尺寸）
- 减少不必要的截图（例如：纯文本操作不需要截图）

#### 6.3 实现位置
- `src/main/resources/application.properties.example`
- `src/main/java/com/lavis/perception/ScreenCapturer.java`

**预计工作量**: 0.5 天  
**价值**: 提升响应速度，展示 flash 模型的优势

---

## 🎯 实施优先级总结（15天开发周期）

| 优先级 | 功能 | 预计工作量 | 价值 | 是否必须 |
|--------|------|-----------|------|---------|
| ⭐⭐⭐⭐⭐ | **演示脚本和 Demo** | 1-2 天 | Hackathon 展示关键 | ✅ 必须 |
| ⭐⭐⭐⭐⭐ | **文档和展示** | 1 天 | 帮助评委理解项目 | ✅ 必须 |
| ⭐⭐⭐⭐⭐ | **增强执行状态推送** | 1-2 天 | 显著提升用户体验 | ✅ 必须 |
| ⭐⭐⭐⭐ | **多模态能力增强** | 0.5-1 天 | 展示 Gemini 核心能力 | ⚠️ 推荐 |
| ⭐⭐⭐ | **工具调用优化** | 0.5-1 天 | 提升执行成功率 | ⚠️ 可选 |
| ⭐⭐⭐ | **性能优化** | 0.5 天 | 提升响应速度 | ⚠️ 可选 |

**总计**: 5-8 天（剩余时间可用于测试、优化、bug 修复）

---

## 🚀 15天开发计划

### 第 1-2 天：演示脚本（最高优先级）
1. 创建 `scripts/demo-gemini-hackathon.sh`
2. 设计 3-5 个典型演示场景
3. 测试演示脚本，确保稳定运行
4. 录制演示视频（可选，但强烈推荐）

### 第 3 天：文档完善
1. 创建 `README-GEMINI-HACKATHON.md`
2. 添加 Gemini 能力展示章节
3. 添加演示截图/视频链接
4. 完善项目说明

### 第 4-5 天：增强执行状态推送
1. 在 `AgentService` 中添加工具调用状态推送
2. 在 `WorkflowEventService` 中添加新事件类型
3. 前端显示工具调用状态和思考摘要
4. 测试和优化

### 第 6 天：多模态能力增强
1. 优化 `SYSTEM_PROMPT`，增强视觉分析指导
2. 添加视觉分析结果提取和推送
3. 测试视觉理解能力提升

### 第 7-8 天：工具调用优化（可选）
1. 优化工具调用 prompt
2. 改进错误处理和重试逻辑
3. 测试执行成功率提升

### 第 9-10 天：性能优化（可选）
1. 优化截图压缩
2. 调整超时和温度参数
3. 测试响应速度提升

### 第 11-15 天：测试、优化、Bug 修复
1. 全面测试所有功能
2. 修复发现的 bug
3. 优化用户体验
4. 准备 Hackathon 演示

---

## 💡 关于流式响应的说明

### 为什么不推荐流式响应？

1. **项目定位不匹配**：
   - 本项目是**自动化任务执行系统**，不是聊天系统
   - 用户关心的是"任务执行进度"、"工具调用状态"，而不是"AI 的思考过程逐字显示"

2. **已有更好的方案**：
   - 项目已有 WebSocket 推送工作流状态
   - 通过推送"思考摘要"、"工具调用状态"更能满足用户需求

3. **开发成本高**：
   - 流式响应需要修改核心架构
   - 需要处理工具调用中断、错误恢复等复杂场景
   - 15 天开发周期内，投入产出比不高

### 替代方案：增强状态推送

**更符合项目需求**：
- 推送"AI 正在分析屏幕..."（思考摘要）
- 推送"准备点击登录按钮 (坐标: 500, 300)"（工具调用详情）
- 推送"识别到 3 个可点击按钮"（视觉分析结果）

这样既能满足用户需求，又能展示 Gemini 的能力，开发成本更低。

---

## 📝 注意事项

1. **API Key 安全**: 确保演示脚本中不包含真实的 API Key
2. **错误处理**: 演示脚本需要包含错误处理，避免演示时出错
3. **稳定性**: 优先保证核心功能稳定，再考虑优化
4. **兼容性**: 确保改进不影响现有功能
5. **测试**: 每个功能完成后都要充分测试

---

## 🔗 相关资源

- [Gemini API 文档](https://ai.google.dev/gemini-api/docs)
- [Gemini 3.0-flash 特性](https://ai.google.dev/gemini-api/docs/models/gemini-3.0-flash)
- [LangChain4j Gemini 集成](https://github.com/langchain4j/langchain4j)

---

**最后更新**: 2024-12-19
**维护者**: Lavis Team

