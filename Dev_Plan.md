Lavis Agent 交互优化与内存安全开发方案

本文档详细描述了针对 Lavis Agent 项目的三项核心改进方案：后端里程碑式语音播报、Electron 窗口交互形态控制以及全链路（前端/Electron/后端）的内存安全管理。

1. 后端：最终步骤语音播报 (Final Step Voice Announcement)

目标：在任务计划全部完成时（最终步骤），向前端推送简短的拟人化语音反馈，通知用户任务已完成，同时确保不阻塞自动化任务的执行速度。

**重要**：只在最终步骤（计划完成时）生成TTS通知，不在每个中间步骤生成，避免过度打扰用户。

1.1 架构变更：TaskOrchestrator

在任务编排器中引入异步处理机制，将"执行逻辑"与"播报逻辑"解耦。

异步线程池：在 TaskOrchestrator 中使用 CompletableFuture.runAsync() 启动独立线程来生成语音文本。

快速模型通道：使用 executor 模型（通常是 gemini-flash）来生成拟人化文本。

Prompt 策略：

Input: 用户目标 + 执行步数 + 耗时。

System Prompt: "你是一个拟人化助手。请把任务完成情况用口语简述，限制在 20 个字以内。例如：'任务完成了'、'微信消息已发送'。不要废话，直接说结果。"

流程控制：

TaskOrchestrator 检测到计划完成（currentPlan.isCompleted()）。

非阻塞地调用 LLM 生成语音文本（异步执行）。

1.2 TTS 配置统一方案

架构设计：后端提供 TTS 代理端点，前端调用并播放

**方案选择**：采用后端 TTS 代理模式，而非前端直接调用 TTS API

优势：
- ✅ **安全性**：API key 仅存在于后端，不会暴露给前端
- ✅ **配置统一**：所有 TTS 配置（API key、base URL、模型、音色等）统一在 `application.properties` 管理
- ✅ **架构清晰**：后端统一管理所有外部 API 调用，前端专注于 UI 和交互
- ✅ **易于维护**：切换 TTS 提供商或修改配置时，只需修改后端配置，前端无需改动

实现细节：

后端端点：`POST /api/agent/tts`
- 请求：`{ "text": "要转换的文本" }`
- 响应：`{ "success": true, "audio": "Base64编码的音频", "format": "mp3", "duration_ms": 123 }`
- 使用后端配置的 TTS 模型（`app.llm.models.tts.*`）生成音频

前端流程：
1. WebSocket 接收 `voice_announcement` 事件（仅包含文本）
2. 调用 `agentApi.tts(text)` 获取音频
3. 将 Base64 音频转换为 Blob 并播放

配置示例（`application.properties`）（已经存在，可以立刻复用）：
```properties
app.llm.models.tts.type=TTS
app.llm.models.tts.provider=OPENAI
app.llm.models.tts.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1
app.llm.models.tts.api-key=${TTS_API_KEY:your-api-key}
app.llm.models.tts.model-name=qwen3-tts-flash
app.llm.models.tts.voice=Cherry
app.llm.models.tts.format=mp3
```

主线程立即返回结果，不等待语音生成。

1.2 通信协议：WebSocket

在 WorkflowEventService 中新增专用事件类型，避免污染现有的聊天记录流。

**实现位置**：
- 后端：`src/main/java/com/lavis/websocket/WorkflowEventService.java` - `onVoiceAnnouncement(String text)` 方法
- 后端：`src/main/java/com/lavis/cognitive/orchestrator/TaskOrchestrator.java` - 在计划完成时调用

Event Type: voice_announcement

Payload:

```json
{
  "type": "voice_announcement",
  "data": {
    "text": "任务完成了",
  "timestamp": 1700000000000
}
}
```

**触发时机**：
- ✅ 仅在计划完成时（`currentPlan.isCompleted() == true`）触发
- ❌ 不在每个步骤完成时触发
- ❌ 不在计划失败时触发


2. 前端：Electron 窗口形态与交互 (Interaction & Window State)

目标：实现“听觉唤醒，视觉克制”。语音唤醒时不直接占据屏幕，仅通过胶囊组件的动效反馈，减少对用户的干扰。

2.1 状态机设计 (UI Store)

在 uiStore 中定义明确的窗口状态枚举：

| 状态 | 描述 | UI 表现 | 窗口尺寸 |
|------|------|---------|----------|
| Idle | 休眠/待机 | 胶囊隐藏或仅显示托盘图标 | (隐藏或极小) |
| Listening | 语音唤醒/监听中 | 显示胶囊，呼吸灯动效，不显示面板 | Mini (e.g., 200x60px) |
| Expanded | 交互展开 | 显示完整聊天与工作流面板 | Full (e.g., 800x600px) |

2.2 交互流程

唤醒阶段 (Wake Word Triggered)

触发源：useWakeWord 或 useVoskWakeWord 检测到关键词。

动作：setAppState('Listening')。

Electron IPC：发送 resize-window-mini 指令。

视觉：Capsule 组件添加呼吸 CSS 类，边框颜色变为激活态。

播报反馈 (TTS Playing)

触发源：收到 WebSocket voice_announcement 事件（包含文本）。

流程：
1. 前端接收 `voice_announcement` 事件（仅包含文本）
2. 前端调用后端 TTS 代理端点 `/api/agent/tts` 将文本转换为音频
3. 前端播放生成的音频

配置统一：
- **所有 TTS 配置统一在后端管理**（`application.properties`）
- 前端无需配置 API key，只需调用后端 TTS 端点
- 优点：安全性高（API key 不暴露）、配置集中、易于维护

视觉：Capsule 根据播放状态显示声波纹路，保持 Mini 尺寸。

主动展开 (User Activation)

触发源：用户双击 (Double Click) Capsule 组件。

动作：setAppState('Expanded')。

Electron IPC：发送 resize-window-full 指令。

视觉：渲染 ChatPanel 和 WorkflowPanel。

2.3 Electron 主进程适配

窗口属性：确保 BrowserWindow 初始化时 transparent: true, frame: false。

IPC 监听：实现 resize-window-mini 和 resize-window-full 的处理逻辑，精确调整 bounds。

3. 全链路内存安全策略 (Full-Stack Memory Safety)

目标：防止长时间运行导致内存泄漏或 OOM（OutOfMemory）。策略覆盖浏览器端、Electron 进程通信及 Java 后端堆内存。

3.1 浏览器/React 端：音频资源“消费即焚”

强制 URL Revoke：
在 audio.onended 或组件 useEffect 的 cleanup 函数中，必须调用 URL.revokeObjectURL(currentUrl)，防止 Blob 堆积。

单例 Audio 复用：
全局维护唯一的 new Audio() 实例（通过 useRef 或单例 Service），避免频繁创建 DOM 节点。

Store 瘦身：
voiceStore 中的 Base64 音频数据在播放开始后立即从队列移除 (shift)。禁止将音频二进制数据存入持久化的 chatHistory。

3.2 Electron 应用层：IPC 与 渲染优化

Electron 的主进程与渲染进程通过 IPC 通信，大数据量（如图片/音频 Base64）传输会造成严重的内存抖动和序列化开销。

IPC 传输限制 (Throttling)：

禁止通过 IPC 广播无意义的高清大图。

如果 Java 端传来了高清截图用于视觉识别，在转发给 Electron 前端展示时，应在 Java 端或 Node 层进行压缩（Thumbnail），仅传输缩略图。

DOM 虚拟化 (Virtualization)：

当窗口处于 Listening (Mini) 模式或后台时，React 应停止渲染 ChatPanel 中的复杂组件。

对于长聊天记录，必须引入虚拟滚动（如 react-window），仅渲染视口内的消息气泡，防止 DOM 节点数无限增长导致渲染进程崩溃。

3.3 Java 后端：堆内存生命周期管理

Java 后端处理大量的图像（Screen Capture）和上下文（Context），是内存消耗大户。

Image Content 淘汰策略 (Crucial)：

AgentService 中的 ChatMemory 会保存历史对话。默认情况下，它会保存所有历史截图的 Base64 字符串。

策略：实现一个自定义的 ChatMemory 管理逻辑。在添加新消息前，遍历历史消息，将超过 N 轮（如 2 轮）之前的 ImageContent 移除或替换为占位符。LLM 通常只需要最近的屏幕状态，不需要 10 分钟前的截图。这能节省 90% 以上的堆内存。

ScreenCapturer 对象释放：

确保 ScreenCapturer 生成的 BufferedImage 和 ByteArrayOutputStream 作用域最小化。

在 captureScreenWithCursorAsBase64 方法内部，使用 try-with-resources 确保流关闭。虽然 Java 有 GC，但在高频截图场景下，显式将大对象置为 null 有助于老年代 GC 效率。

GlobalContext 清理：

在 TaskOrchestrator 的 executeGoal 结束后（无论成功失败），必须调用清理方法，清空 GlobalContext 中可能缓存的临时跨步骤数据。

4. 总结

| 模块 | 关注点 | 关键措施 |
|------|--------|----------|
| 后端逻辑 | 响应速度 | 异步线程池 + 快速 LLM 模型 + WebSocket 专用事件 |
| 交互体验 | 干扰控制 | 状态机管理窗口尺寸 + 胶囊呼吸动效 + 双击展开机制 |
| 前端内存 | Blob/DOM | URL.revokeObjectURL + 虚拟滚动 + 隐藏模式停止渲染 |
| IPC/后端内存 | 大对象管理 | 历史截图淘汰机制 + IPC 缩略图传输 + 上下文及时清理 |