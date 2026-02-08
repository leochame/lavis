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
- **上下文工程**: 智能压缩与感知去重，历史视觉 Token 降低 95%+
- **网络搜索**: 深度优先搜索子代理，最多 5 轮迭代

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

Lavis 使用 Google Gemini API 提供所有 AI 服务（聊天、语音识别、语音合成）。你只需要**一个 API Key** 即可开始使用。

**方式一（推荐）：前端设置面板（最简单）**

1. 启动 `Lavis AI.app`
2. 打开设置面板（通过菜单栏图标或 `Cmd + K`）
3. 在设置表单中输入你的 Gemini API Key
4. 点击「保存」- Key 会立即保存并生效

> **注意**: 前端设置面板是最简单的配置方式，无需编辑文件或环境变量。

**方式二：环境变量**

在终端中设置环境变量，然后从终端启动应用：

```bash
export GEMINI_API_KEY=your_gemini_api_key_here

# 启动应用
open /Applications/Lavis\ AI.app
```

**持久化配置**：将环境变量添加到你的 shell 配置文件中（如 `~/.zshrc` 或 `~/.bash_profile`）：

```bash
echo 'export GEMINI_API_KEY=your_gemini_api_key_here' >> ~/.zshrc
source ~/.zshrc
```

**方式三：配置文件（高级用户）**

如果你手动运行后端（开发者模式）：

1. 复制示例配置文件：
   ```bash
   cp src/main/resources/application.properties.example src/main/resources/application.properties
   ```

2. 编辑 `src/main/resources/application.properties`，设置：
   ```properties
   app.llm.models.gemini.api-key=your_gemini_api_key_here
   app.llm.models.whisper.api-key=your_gemini_api_key_here
   app.llm.models.tts.api-key=your_gemini_api_key_here
   ```

   或者直接设置环境变量 `GEMINI_API_KEY`（配置文件会自动使用它）。

#### 获取 Gemini API Key

1. 访问 [Google AI Studio](https://aistudio.google.com/app/apikey)
2. 使用你的 Google 账号登录
3. 点击「Create API Key」
4. 复制生成的 Key

> **安全提示**: API Key 仅存储在本地（环境变量或本地配置文件中），不会被上传到第三方服务或暴露给前端。

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

> **获取打包版本**: 如果你需要打包应用，请参考 `docs/Build-and-Packaging-zh.md` 中的打包指南。打包脚本会自动下载 JRE、编译后端、构建前端并生成 DMG 安装包。

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

Lavis 使用 Google Gemini API 提供所有 AI 服务。你只需要**一个 API Key**。

**方式一（推荐）：环境变量**

```bash
export GEMINI_API_KEY=your_gemini_api_key_here
```

**方式二：配置文件**

```bash
cp src/main/resources/application.properties.example src/main/resources/application.properties
# 编辑 application.properties，设置 GEMINI_API_KEY 或直接在文件中填写 API Key
```

**方式三：前端设置面板**

启动前端后，在设置面板中配置 API Key（最简单的方式）。

#### 获取 Gemini API Key

1. 访问 [Google AI Studio](https://aistudio.google.com/app/apikey)
2. 使用你的 Google 账号登录
3. 点击「Create API Key」
4. 复制生成的 Key

#### 2. 启动后端

```bash
./mvnw spring-boot:run
```

> 想要使用 GraalVM Native Image 进行 AOT 编译和更强的防逆向能力，请参考 `docs/Build-and-Packaging-zh.md`（中文）或 `docs/Build-and-Packaging-en.md`（English）。

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
│   ├── service/                    # TTS/ASR 等服务
│   ├── scheduler/                  # 定时任务调度（Cron + 历史记录）
│   ├── skills/                     # Skills 插件系统（SKILL.md 动态加载）
│   ├── memory/                     # 会话与截图内存管理（Context Engineering）
│   ├── entity/                     # JPA 实体（任务、日志、会话、技能等）
│   └── repository/                 # JPA 仓库（基于 SQLite）
├── frontend/                       # Electron + React 前端
│   ├── electron/                   # Electron 主进程（托盘、窗口、快捷键）
│   └── src/                        # React UI & hooks（包含 Skills/Scheduler 管理面板）
├── docs/                           # 文档
│   ├── User-Guide-en.md            # 用户使用说明
│   ├── Build-and-Packaging-zh.md  # 构建与打包指南（中文）
│   ├── Build-and-Packaging-en.md  # 构建与打包指南（英文）
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
curl http://localhost:18765/api/agent/status

# 发送消息
curl -X POST http://localhost:18765/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "当前屏幕上显示了什么?"}'

# 执行任务
curl -X POST http://localhost:18765/api/agent/task \
  -H "Content-Type: application/json" \
  -d '{"goal": "打开 Safari 并搜索天气"}'
```

---

## 文档导航

- `docs/User-Guide-en.md`  
  用户说明：安装、运行、权限、基础使用。
- `docs/Build-and-Packaging-zh.md` / `docs/Build-and-Packaging-en.md`  
  完整的构建与打包指南：开发模式、一键打包（JAR 方式）、GraalVM Native Image（高级选项）、调试、故障排除。
- `docs/ARCHITECTURE.md`  
  系统架构、数据流详细说明与开发历史。
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

