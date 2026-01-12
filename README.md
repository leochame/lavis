# Lavis - macOS 系统级多模态智能体

Lavis 是一个运行在 macOS 上的 AI 智能体 (类似 "Jarvis")，能够通过视觉感知屏幕、通过鼠标键盘操作系统。

## ✨ 核心特性

- **视觉感知**: 实时截图分析，支持 Retina 屏幕缩放
- **自主操作**: 鼠标移动/点击/拖拽、键盘输入、系统快捷键
- **反思机制**: Action-Observation-Correction (行动-观察-修正) 闭环
- **系统集成**: AppleScript 执行、应用控制、Shell 命令
- **透明 UI**: 现代化 HUD 抬头显示器，展示 Agent 思考过程
- **智能重试**: 自动处理 API 限流和错误重试

## 🏗️ 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                     Lavis Architecture                       │
├─────────────────────────────────────────────────────────────┤
│  M4: Overlay UI (JavaFX)                                    │
│  └── 透明 HUD 窗口，展示思考过程                              │
├─────────────────────────────────────────────────────────────┤
│  M2: Cognitive Layer (LangChain4j + Gemini)                 │
│  ├── AgentService: AI 对话与工具调用                         │
│  ├── AgentTools: 可调用的工具集                              │
│  └── ReflectionLoop: 反思循环机制                            │
├─────────────────────────────────────────────────────────────┤
│  M1: Perception │  M3: Action Layer                         │
│  └── ScreenCapturer    ├── RobotDriver (鼠标/键盘)          │
│                        └── AppleScriptExecutor (系统脚本)    │
└─────────────────────────────────────────────────────────────┘
```

## 🚀 快速开始

### 前置要求

- macOS 系统
- JDK 21+
- Gemini API Key (免费获取: https://makersuite.google.com/app/apikey)

### 配置 API Key

```bash
export GEMINI_API_KEY=your_api_key_here
```

或在 `application.properties` 中配置:

```properties
gemini.api.key=your_api_key_here
```

### 运行

```bash
./mvnw spring-boot:run
```

### 授予权限

首次运行时，macOS 会要求授权:
1. **屏幕录制权限**: 系统偏好设置 → 安全性与隐私 → 隐私 → 屏幕录制
2. **辅助功能权限**: 系统偏好设置 → 安全性与隐私 → 隐私 → 辅助功能

## ⌨️ 快捷键

| 快捷键 | 功能 |
|--------|------|
| `⌘+Enter` | 发送消息 |
| `⌘+K` | 清空日志 |
| `↑/↓` | 浏览输入历史 |
| `Escape` | 隐藏窗口 |

## 📡 REST API

### 检查状态
```bash
curl http://localhost:8080/api/agent/status
```

### 发送消息
```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "你好"}'
```

### 带截图的对话 (视觉分析)
```bash
curl -X POST http://localhost:8080/api/agent/chat-with-screenshot \
  -H "Content-Type: application/json" \
  -d '{"message": "当前屏幕上显示了什么?"}'
```

### 执行自动化任务
```bash
curl -X POST http://localhost:8080/api/agent/execute \
  -H "Content-Type: application/json" \
  -d '{"task": "打开 Safari 并搜索天气"}'
```

### 获取屏幕截图
```bash
curl http://localhost:8080/api/agent/screenshot
```

### 重置对话
```bash
curl -X POST http://localhost:8080/api/agent/reset
```

### 查看任务历史
```bash
curl http://localhost:8080/api/agent/history?limit=10
```

### 清空历史
```bash
curl -X DELETE http://localhost:8080/api/agent/history
```

## 🛠️ 可用工具

### 鼠标操作
- `click(x, y)` - 单击
- `doubleClick(x, y)` - 双击
- `rightClick(x, y)` - 右键点击
- `drag(fromX, fromY, toX, toY)` - 拖拽
- `scroll(amount)` - 滚动

### 键盘操作
- `typeText(text)` - 输入文本 (支持中英文)
- `pressEnter()` - 回车
- `pressEscape()` - ESC
- `pressTab()` - Tab
- `copy()` / `paste()` - 复制/粘贴
- `selectAll()` - 全选
- `save()` - 保存
- `undo()` - 撤销

### 系统操作
- `openApplication(name)` - 打开应用
- `quitApplication(name)` - 关闭应用
- `openURL(url)` - 打开网址
- `openFile(path)` - 打开文件
- `executeAppleScript(script)` - 执行 AppleScript
- `executeShell(command)` - 执行 Shell 命令
- `showNotification(title, message)` - 显示通知

## 🔄 反思机制

Lavis 采用类似 Flowith OS 的反思机制:

1. **行动 (Action)**: 执行用户指定的操作
2. **观察 (Observation)**: 截图分析执行结果
3. **修正 (Correction)**: 如果未完成，调整策略重试

```
┌──────┐    ┌──────────┐    ┌──────────┐
│ 行动 │ -> │ 截图观察 │ -> │ 完成判断 │
└──────┘    └──────────┘    └────┬─────┘
     ^                          │
     │       ┌──────────┐       │ NO
     └───────│ 修正策略 │<──────┘
             └──────────┘
```

## 📁 项目结构

```
src/main/java/com/lavis/
├── LavisApplication.java       # 应用入口
├── perception/
│   └── ScreenCapturer.java     # 屏幕截图 (M1)
├── cognitive/
│   ├── AgentService.java       # AI 服务 (M2)
│   ├── AgentTools.java         # AI 工具集 (M2)
│   └── ReflectionLoop.java     # 反思循环 (M2)
├── action/
│   ├── RobotDriver.java        # 鼠标键盘控制 (M3)
│   └── AppleScriptExecutor.java# 系统脚本执行 (M3)
├── ui/
│   ├── OverlayWindow.java      # 透明 HUD 窗口 (M4)
│   └── JavaFXInitializer.java  # JavaFX 初始化
├── controller/
│   └── AgentController.java    # REST API
└── config/
    └── AppConfig.java          # 应用配置
```

## ⚙️ 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `gemini.api.key` | - | Gemini API Key (必填) |
| `gemini.model` | gemini-2.5-flash | 使用的 AI 模型 |
| `agent.retry.max` | 3 | 最大重试次数 |
| `agent.retry.delay.ms` | 2000 | 重试延迟 (毫秒) |
| `reflection.max.iterations` | 5 | 反思循环最大次数 |
| `reflection.delay.ms` | 1000 | 每次操作后等待时间 |
| `screenshot.target.width` | 768 | 截图压缩宽度 |

## 🎯 示例任务

```bash
# 打开应用
curl -X POST http://localhost:8080/api/agent/execute \
  -H "Content-Type: application/json" \
  -d '{"task": "打开 Notes 应用并创建一个新笔记"}'

# 网页操作
curl -X POST http://localhost:8080/api/agent/execute \
  -H "Content-Type: application/json" \
  -d '{"task": "打开 Safari，访问 github.com"}'

# 文件操作
curl -X POST http://localhost:8080/api/agent/execute \
  -H "Content-Type: application/json" \
  -d '{"task": "打开 Finder，进入 Documents 文件夹"}'
```

## 🔒 隐私说明

- 所有操作在本地执行
- 截图按需获取，不会持久存储
- API Key 存储在本地配置中

## 📜 License

MIT License
