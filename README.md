## Lavis - macOS 系统级多模态 AI 智能体 / macOS System-level Multimodal AI Agent

Lavis 是一个运行在 macOS 上的桌面 AI 智能体，能够通过**视觉感知屏幕**、**模拟鼠标键盘**执行自动化操作，并支持**语音交互**。  
Lavis is a macOS desktop AI agent that **perceives your screen**, **controls mouse & keyboard**, and supports **voice interaction**.

---

## 核心特性 / Key Features

- **视觉感知 / Visual Perception**: 实时截图分析（支持 Retina 缩放） / real-time screenshot analysis with Retina support
- **自主操作 / Autonomous Actions**: 鼠标移动/点击/拖拽、键盘输入、系统快捷键 / mouse, keyboard, and system shortcut control
- **反思机制 / Reflection Loop**: Action–Observation–Correction 闭环 / closed loop for self-correction
- **系统集成 / System Integration**: AppleScript、应用控制、Shell 命令 / AppleScript, app control, shell commands
- **语音交互 / Voice Interaction**: 唤醒词、ASR、TTS / wake word, ASR, TTS
- **透明 UI / Transparent UI**: HUD 式前端，展示 Agent 思考过程 / HUD-style UI showing internal reasoning
- **内存安全 / Memory Safety**: 历史截图与音频自动清理，支持长时间运行 / automatic cleanup for long-running sessions

---

## 技术栈 / Tech Stack

| 层级 / Layer | 技术 / Tech | 版本 / Version |
|-------------|-------------|----------------|
| **后端 / Backend** | Spring Boot | 3.5.9 |
| **语言 / Language** | Java | 21 |
| **AI 框架 / AI Framework** | LangChain4j | 0.35.0 |
| **前端 / Frontend** | React | 19.x |
| **桌面 / Desktop** | Electron | 40.x |
| **构建 / Build** | Vite | 7.x |
| **状态管理 / State** | Zustand | 5.x |

---

## 快速开始 / Quick Start

### 前置要求 / Prerequisites

- macOS (Intel / Apple Silicon)
- JDK 21+
- Node.js 18+
- 至少一个 LLM API Key / at least one LLM API key

### 1. 配置后端 API Key / Configure Backend API Keys

**方式一（推荐）/ Option 1 (recommended): 环境变量 / Environment variables**

```bash
export MODELA_API_KEY=your_modela_api_key    # 主要 LLM (e.g. GPT-4)
export MODELB_API_KEY=your_modelb_api_key    # 视觉 LLM (e.g. Gemini)
export MODELC_API_KEY=your_modelc_api_key    # 快速 LLM (e.g. GPT-3.5)
export WHISPER_API_KEY=your_whisper_api_key  # 语音识别 / ASR
export TTS_API_KEY=your_tts_api_key          # 语音合成 / TTS
```

**方式二 / Option 2: 配置文件 / Configuration file**

```bash
cp src/main/resources/application.properties.example src/main/resources/application.properties
# 编辑 application.properties 填写 API Key / edit and fill your API keys
```

### 2. 启动后端 / Start Backend

```bash
./mvnw spring-boot:run
```

> 想要使用 GraalVM Native Image 进行 AOT 编译和更强的防逆向能力，请参考 `docs/Developer-Build-and-Packaging.md`。  
> For AOT compilation and stronger reverse-engineering resistance with GraalVM Native Image, see `docs/Developer-Build-and-Packaging.md`.

### 3. 启动前端 / Start Frontend

```bash
cd frontend
npm install
npm run electron:dev
```

### 4. 授予 macOS 权限 / Grant macOS Permissions

**中文：**
1. 屏幕录制：系统设置 → 隐私与安全性 → 屏幕录制 → 勾选 Lavis
2. 辅助功能：系统设置 → 隐私与安全性 → 辅助功能 → 勾选 Lavis

**English:**
1. Screen Recording: System Settings → Privacy & Security → Screen Recording → enable Lavis
2. Accessibility: System Settings → Privacy & Security → Accessibility → enable Lavis

---

## 项目结构 / Project Structure

```text
lavis/
├── src/main/java/com/lavis/        # Java 后端 / backend
│   ├── cognitive/                  # 认知层 / cognitive logic
│   ├── perception/                 # 感知层（截图）/ perception (screen)
│   ├── action/                     # 动作层（鼠标键盘）/ actions
│   ├── controller/                 # REST API
│   ├── websocket/                  # WebSocket 通信 / WebSocket
│   └── service/                    # TTS/ASR 等服务 / services
├── frontend/                       # Electron + React 前端 / frontend
│   ├── electron/                   # Electron 主进程 / main process
│   └── src/                        # React UI & hooks
├── docs/                           # 文档 / documentation
│   ├── User-Guide.md               # 用户使用说明 / User guide
│   ├── Developer-Build-and-Packaging.md  # 构建与打包指南 / Build & packaging
│   └── ARCHITECTURE.md (planned)   # 可以迁移现有架构文档 / planned ref
├── ARCHITECTURE.md                 # 详细架构文档 / architecture (legacy location)
├── Dev_Plan.md                     # 开发计划 / dev plan
└── IMPLEMENTATION_STATUS.md        # 实现状态 / implementation status
```

---

## REST API 概览 / REST API Overview

| 方法 / Method | 端点 / Endpoint | 说明 / Description |
|--------------|-----------------|--------------------|
| GET | `/api/agent/status` | 获取系统状态 / get system status |
| POST | `/api/agent/chat` | 聊天 / chat with screenshot context |
| POST | `/api/agent/task` | 执行自动化任务 / execute automation task |
| POST | `/api/agent/stop` | 紧急停止 / emergency stop |
| POST | `/api/agent/reset` | 重置对话 / reset state |
| GET | `/api/agent/screenshot` | 获取屏幕截图 / get screenshot |
| POST | `/api/agent/tts` | 文本转语音 / text-to-speech |
| GET | `/api/agent/history` | 获取任务历史 / get task history |

**示例 / Examples**

```bash
# 检查状态 / check status
curl http://localhost:8080/api/agent/status

# 发送消息 / send a chat message
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "当前屏幕上显示了什么?"}'

# 执行任务 / execute a task
curl -X POST http://localhost:8080/api/agent/task \
  -H "Content-Type: application/json" \
  -d '{"goal": "打开 Safari 并搜索天气"}'
```

---

## 文档导航 / Documentation

- `docs/User-Guide.md`  
  - **中文/English** 用户说明：安装、运行、权限、基础使用。
- `docs/Developer-Build-and-Packaging.md`  
  - **中文/English** 开发者指南：构建、GraalVM Native Image 打包、Electron 打包。
- `ARCHITECTURE.md`  
  - 系统架构与数据流的详细说明。
- `Dev_Plan.md` & `IMPLEMENTATION_STATUS.md`  
  - 开发计划与当前实现进度，适合作为贡献者参考。
- `frontend/README.md`  
  - 前端（Electron + React）开发说明。

---

## 安全与隐私 / Security & Privacy

- 所有自动化操作在本地执行；截图仅用于视觉推理，不做长期持久化。  
- API Key 存储于本地环境变量或配置文件，不会被前端或第三方服务泄露。  
- 使用 GraalVM Native Image 打包后端，可移除 `.class` 字节码，增加反编译与逆向的难度。

All automation runs locally; screenshots are transient and used only for visual reasoning.  
API keys live in local env/config only and are never exposed to the frontend or third parties.  
GraalVM Native Image packaging removes `.class` files, making reverse engineering significantly harder.

---

## License

MIT License
