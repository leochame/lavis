## Lavis - macOS 系统级多模态 AI 智能体

Lavis 是一个运行在 macOS 上的桌面 AI 智能体，能够通过**视觉感知屏幕**、**模拟鼠标键盘**执行自动化操作，并支持**语音交互**。

---

## 核心特性

- **视觉感知**: 实时截图分析（支持 Retina 缩放）
- **自主操作**: 鼠标移动/点击/拖拽、键盘输入、系统快捷键
- **反思机制**: Action–Observation–Correction 闭环
- **系统集成**: AppleScript、应用控制、Shell 命令
- **语音交互**: 唤醒词、ASR、TTS
- **透明 UI**: HUD 式前端，展示 Agent 思考过程
- **内存安全**: 历史截图与音频自动清理，支持长时间运行

---

## 技术栈

| 层级 | 技术 | 版本 |
|-------------|-------------|----------------|
| **后端** | Spring Boot | 3.5.9 |
| **语言** | Java | 21 |
| **AI 框架** | LangChain4j | 0.35.0 |
| **前端** | React | 19.x |
| **桌面** | Electron | 40.x |
| **构建** | Vite | 7.x |
| **状态管理** | Zustand | 5.x |

---

## 快速开始

### 方式一：一键启动（推荐用户使用）

Lavis 已将所有组件（Java 后端、JRE、前端）打包成一个独立的 macOS 应用，**无需安装 Java 或 Node.js**，双击即可运行。

#### 1. 安装应用

1. 下载 `Lavis AI-1.0.0-arm64.dmg`（或对应架构版本）
2. 双击 DMG 文件，将 `Lavis AI.app` 拖入「应用程序」文件夹
3. 首次运行时，如出现安全提示，请前往「系统设置 → 隐私与安全性」允许运行

#### 2. 配置 API Key

应用首次启动前，需要配置 LLM 和语音服务的 API Key。有两种方式：

**方式一（推荐）：环境变量**

在终端中设置环境变量，然后从终端启动应用：

```bash
export MODEL_API_KEY=your_model_api_key      # 主要 LLM (如 GPT-4, Gemini)
export WHISPER_API_KEY=your_whisper_api_key # 语音识别（可为 Gemini Hackathon 使用 Gemini 3.0-flash）
export TTS_API_KEY=your_tts_api_key           # 语音合成

# 启动应用
open /Applications/Lavis\ AI.app
```

**方式二：配置文件**

编辑应用包内的配置文件（需要右键「显示包内容」）：

```bash
# 找到应用包内的配置文件
open /Applications/Lavis\ AI.app/Contents/Resources/backend/application.properties
# 或使用文本编辑器编辑
```

> **注意**: 打包后的应用配置文件位置可能不同，建议使用环境变量方式。

#### 3. 授予 macOS 权限

首次运行时，系统会提示授予以下权限：

1. **屏幕录制**：系统设置 → 隐私与安全性 → 屏幕录制 → 勾选 `Lavis AI`
2. **辅助功能**：系统设置 → 隐私与安全性 → 辅助功能 → 勾选 `Lavis AI`
3. **麦克风**（可选）：用于语音唤醒和语音交互

#### 4. 启动应用

双击 `Lavis AI.app` 即可启动。应用会自动：
- 启动内嵌的 Java 后端服务（无需手动操作）
- 显示前端界面
- 在菜单栏显示图标

> **后端自动启动**: Java 后端已内嵌在应用中，会在应用启动时自动运行。如果后端启动失败，请检查日志或重新安装应用。

> **获取打包版本**: 如果你需要打包应用，请参考 `frontend/PACKAGING.md` 中的打包指南。打包脚本会自动下载 JRE、编译后端、构建前端并生成 DMG 安装包。

---

### 方式二：开发者模式

如果你需要开发或修改代码，请使用开发者模式：

#### 前置要求

- macOS (Intel / Apple Silicon)
- JDK 21+
- Node.js 18+
- Maven（或使用项目自带的 `mvnw`）
- 至少一个 LLM API Key

#### 1. 配置 API Key

**方式一（推荐）：环境变量**

```bash
export MODEL_API_KEY=your_model_api_key      # 主要 LLM (如 GPT-4, Gemini)
export WHISPER_API_KEY=your_whisper_api_key # 语音识别（可为 Gemini Hackathon 使用 Gemini 3.0-flash）
export TTS_API_KEY=your_tts_api_key         # 语音合成
```

**方式二：配置文件**

```bash
cp src/main/resources/application.properties.example src/main/resources/application.properties
# 编辑 application.properties 填写 API Key
```

#### 2. 启动后端

```bash
./mvnw spring-boot:run
```

> 想要使用 GraalVM Native Image 进行 AOT 编译和更强的防逆向能力，请参考 `docs/Developer-Build-and-Packaging-zh.md`（中文）或 `docs/Developer-Build-and-Packaging-en.md`（English）。

#### 3. 启动前端

在另一个终端窗口中：

```bash
cd frontend
npm install
npm run electron:dev
```

> **注意**: 开发者模式下，前端会自动检测并连接到运行中的后端。如果后端未启动，前端仍会尝试启动内嵌的 JAR（如果已构建）。

#### 4. 授予 macOS 权限

同「方式一」中的步骤 3。

---

## 项目结构

```text
lavis/
├── src/main/java/com/lavis/        # Java 后端
│   ├── cognitive/                  # 认知层
│   ├── perception/                 # 感知层（截图）
│   ├── action/                     # 动作层（鼠标键盘）
│   ├── controller/                 # REST API
│   ├── websocket/                  # WebSocket 通信
│   └── service/                    # TTS/ASR 等服务
├── frontend/                       # Electron + React 前端
│   ├── electron/                   # Electron 主进程
│   └── src/                        # React UI & hooks
├── docs/                           # 文档
│   ├── User-Guide-zh.md            # 用户使用说明（中文）
│   ├── User-Guide-en.md            # 用户使用说明（英文）
│   ├── Developer-Build-and-Packaging-zh.md  # 构建与打包指南（中文）
│   ├── Developer-Build-and-Packaging-en.md  # 构建与打包指南（英文）
│   └── ARCHITECTURE.md             # 详细架构文档
```

---

## REST API 概览

| 方法 | 端点 | 说明 |
|--------------|-----------------|--------------------|
| GET | `/api/agent/status` | 获取系统状态 |
| POST | `/api/agent/chat` | 聊天（带截图上下文） |
| POST | `/api/agent/task` | 执行自动化任务 |
| POST | `/api/agent/stop` | 紧急停止 |
| POST | `/api/agent/reset` | 重置对话 |
| GET | `/api/agent/screenshot` | 获取屏幕截图 |
| POST | `/api/agent/tts` | 文本转语音 |
| GET | `/api/agent/history` | 获取任务历史 |

**示例**

```bash
# 检查状态
curl http://localhost:8080/api/agent/status

# 发送消息
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "当前屏幕上显示了什么?"}'

# 执行任务
curl -X POST http://localhost:8080/api/agent/task \
  -H "Content-Type: application/json" \
  -d '{"goal": "打开 Safari 并搜索天气"}'
```

---

## 文档导航

- `docs/User-Guide-zh.md`  
  用户说明（中文）：安装、运行、权限、基础使用。
- `docs/User-Guide-en.md`  
  用户说明（英文）：安装、运行、权限、基础使用。
- `docs/Developer-Build-and-Packaging-zh.md` / `docs/Developer-Build-and-Packaging-en.md`  
  开发者指南：构建、GraalVM Native Image 打包、Electron 打包。
- `docs/ARCHITECTURE.md`  
  系统架构与数据流的详细说明。
- `docs/Development-History.md`  
  开发计划与实现状态（历史参考）。
- `docs/Gemini-Hackathon-Improvements.md`  
  Gemini Hackathon 改进建议（15天开发周期）。
- `frontend/README.md`  
  前端（Electron + React）开发说明。

---

## 安全与隐私

- 所有自动化操作在本地执行；截图仅用于视觉推理，不做长期持久化。
- API Key 存储于本地环境变量或配置文件，不会被前端或第三方服务泄露。
- 使用 GraalVM Native Image 打包后端，可移除 `.class` 字节码，增加反编译与逆向的难度。

---

## License

MIT License

