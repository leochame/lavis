## Lavis 用户使用说明 / Lavis User Guide

> 本文面向 **最终用户**，帮助你在 macOS 上安装、启动和日常使用 Lavis AI 智能体。  
> This document is for **end users**, explaining how to install, start, and use Lavis AI Agent on macOS.

---

## 1. 系统要求 / System Requirements

- **操作系统 / OS**: macOS (Intel 或 Apple Silicon)
- **网络 / Network**: 稳定的网络连接，用于访问云端 LLM/TTS 服务
- **后台服务 / Backend**:
  - 已安装并运行的 Lavis 后端（Spring Boot 应用，推荐使用 GraalVM Native Image 打包版本，见开发者文档）
  - 默认端口为 `8080`，如有修改，请在前端配置中更新

---

## 2. 安装与启动 / Installation & Launch

### 2.1 安装 Electron 桌面应用 / Install Electron Desktop App

**中文：**
- 双击安装包（如 `Lavis AI-1.0.0-arm64.dmg`），将 `Lavis AI.app` 拖入「应用程序」文件夹。
- 首次打开时，如果出现「无法验证开发者」提示，请在「系统设置 -> 隐私与安全性」中允许运行该应用。

**English:**
- Open the installer (e.g. `Lavis AI-1.0.0-arm64.dmg`) and drag `Lavis AI.app` into the `Applications` folder.
- On first launch, if macOS warns about an unidentified developer, go to `System Settings -> Privacy & Security` and allow the app to run.

### 2.2 启动后端服务 / Start Backend Service

**中文：**
- 后端可以以普通 JAR 形式或 GraalVM Native Image 二进制形式运行（具体见开发者打包指南）。
- 启动成功后，在浏览器中访问 `http://localhost:8080/api/agent/status` 应该返回系统状态 JSON。

**English:**
- The backend can run either as a regular Spring Boot JAR or as a GraalVM Native Image binary (see Developer Build Guide).
- Once started, visiting `http://localhost:8080/api/agent/status` in a browser should return a JSON status.

### 2.3 授予 macOS 权限 / Grant macOS Permissions

**中文：首次运行 Lavis 时，请按提示授予以下权限：**
- **屏幕录制**：用于视觉感知当前屏幕内容。
- **辅助功能**：用于模拟鼠标键盘操作。

路径：`系统设置 -> 隐私与安全性 -> 屏幕录制 / 辅助功能`，在列表中勾选 `Lavis AI`。

**English: On first run, please grant the following permissions:**
- **Screen Recording**: allows Lavis to see the screen for visual reasoning.
- **Accessibility**: allows Lavis to control mouse and keyboard.

Path: `System Settings -> Privacy & Security -> Screen Recording / Accessibility`, then enable `Lavis AI`.

---

## 3. 首次配置 / First-Time Configuration

### 3.1 配置 LLM 与语音服务 / Configure LLM & Voice Services

**中文：**
- Lavis 需要若干 API Key（如通用 LLM、视觉 LLM、语音识别、语音合成等）。
- 推荐将这些 Key 配置在后端的环境变量或 `application.properties` 文件中，前端无需直接接触密钥。

**English:**
- Lavis requires several API keys (general LLM, vision LLM, ASR, TTS, etc.).
- It is recommended to configure these keys in the backend via environment variables or `application.properties`; the frontend never sees raw keys.

### 3.2 前端与后端连通性 / Connectivity Check

**中文：**
- 启动 `Lavis AI.app` 后，胶囊组件应显示为「就绪」状态（蓝色渐变静止）。
- 如果后端未运行或连接失败，胶囊会显示为「错误」状态（红色脉冲）。

**English:**
- After launching `Lavis AI.app`, the capsule should appear in **Ready** state (blue gradient, static).
- If the backend is down or unreachable, the capsule will enter **Error** state (red pulsing).

---

## 4. 基本使用方式 / Basic Usage

### 4.1 窗口形态 / Window States

**中文：**
- **Idle**：休眠状态，仅在菜单栏或托盘中驻留。
- **Listening**：语音唤醒/监听中，小尺寸胶囊窗口，仅显示轻量反馈。
- **Expanded**：展开完整界面，展示聊天区与任务工作流。

**English:**
- **Idle**: dormant, agent stays in tray/menu bar.
- **Listening**: voice wake / listening mode with a small capsule window.
- **Expanded**: full UI with chat panel and workflow panel.

### 4.2 唤醒与快捷键 / Wake & Shortcuts

**中文：**
- 通过唤醒词（如「Hi Lavis」）或快捷键唤醒：
  - `Alt + Space`：显示/隐藏胶囊或聊天窗口
  - `Cmd + K`：快速打开聊天
  - `Escape`：隐藏窗口
- 双击胶囊可在 Listening 与 Expanded 之间切换。

**English:**
- Use a wake word (e.g. “Hi Lavis”) or shortcuts to activate:
  - `Alt + Space`: toggle capsule / chat window
  - `Cmd + K`: open quick chat
  - `Escape`: hide window
- Double-click the capsule to toggle between Listening and Expanded modes.

### 4.3 典型操作示例 / Typical Use Cases

**中文示例：**
- 「帮我打开 Safari 并搜索今天的天气」
- 「把当前屏幕上显示的错误信息读给我听」
- 「打开微信并给张三发消息：今晚 8 点见」

**English examples:**
- “Open Safari and search for today’s weather.”
- “Read aloud the error message currently on screen.”
- “Open WeChat and send a message to Alice: see you at 8 PM tonight.”

---

## 5. 安全与隐私 / Security & Privacy

**中文：**
- 所有屏幕截图和自动化操作在本地完成，默认不上传到第三方。
- 截图仅用于实时视觉分析，旧的截图会自动清理，以避免长时间运行导致内存泄漏。
- API Key 仅保存在本地配置文件或环境变量中，不会被应用上传。

**English:**
- All screenshots and automation run locally; by default, nothing is uploaded to third-party services.
- Screenshots are used for real-time visual analysis only and are automatically cleaned up to prevent memory leaks.
- API keys are stored only in local configuration or environment variables and are never uploaded by the app.

---

## 6. 常见问题 / FAQ

### Q1. 胶囊一直是红色，表示什么？  
**中文：** 通常表示后端不可用或连接失败，请确认后端服务已启动，并检查网络与端口。  
**English:** This usually means the backend is unavailable. Please ensure the backend service is running and check network/port configuration.

### Q2. 鼠标键盘没有反应？  
**中文：** 请检查是否在「隐私与安全性 -> 辅助功能」中勾选了 `Lavis AI`，并重启应用。  
**English:** Make sure `Lavis AI` is allowed under `Privacy & Security -> Accessibility`, then restart the app.

### Q3. 语音不能正常工作？  
**中文：** 检查麦克风权限、网络连接以及后端语音服务配置（ASR/TTS 的 API Key 是否有效）。  
**English:** Check microphone permission, network connectivity, and backend voice service configuration (ASR/TTS API keys).

---

## 7. 获取帮助 / Getting Support

**中文：**
- 如果你在使用过程中遇到问题，可以：
  - 查看根目录下的 `README.md` 和 `ARCHITECTURE.md`
  - 向项目维护者提交 Issue（若已开源）

**English:**
- If you encounter any issues:
  - Refer to `README.md` and `ARCHITECTURE.md` in the project root
  - Open an Issue for the maintainers (if the project is public)


