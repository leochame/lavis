# Lavis

<p align="center">
  <img src="docs/images/icon.png" alt="Lavis Logo" width="128" />
</p>

Lavis 是一个运行在 macOS 上的桌面多模态 AI 智能体，能够感知屏幕、执行鼠标键盘操作，并支持语音交互。

## 项目定位

Lavis 面向本地桌面自动化场景，采用智能体闭环：
- 感知当前屏幕状态
- 决策下一步动作
- 执行并再次观察，直到完成目标

适合个人工作流自动化、重复 GUI 操作、语音辅助桌面控制等场景。

## 核心特性

- 基于截图感知的桌面推理
- 系统级鼠标/键盘动作执行
- 语音链路（唤醒词、STT、TTS）
- Electron 桌面界面（聊天、任务进度、管理面板）
- 内置任务调度与 Skills 扩展机制
- 本地优先运行与本地配置管理

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 21, Spring Boot |
| 前端 | React, TypeScript, Vite |
| 桌面 | Electron |
| 状态管理 | Zustand |
| 网络请求 | Axios |

## 用户文档

- 中文文档: [docs/User-Guide-zh.md](docs/User-Guide-zh.md)
- English Guide: [docs/User-Guide-en.md](docs/User-Guide-en.md)

面向用户的安装、权限、环境配置、Task/Skills 使用、FAQ 均维护在上述用户文档中。

## 开发者快速启动

### 前置要求

- macOS
- JDK 21+
- Node.js 18+

### 运行配置

```bash
cp .env.example .env
# 在 .env 中填写 app.llm.models.* 配置
```

### 启动后端

```bash
./mvnw spring-boot:run
```

默认后端端口：`18765`。

### 启动前端（Electron）

```bash
cd frontend
npm install
npm run electron:dev
```

## 开发工作流

1. 启动后端（`./mvnw spring-boot:run`）
2. 启动前端（`npm run electron:dev`）
3. 修改后端/前端代码并联调
4. 在 Electron 应用中验证行为

## 打包（macOS）

```bash
cd frontend
npm install
npm run package
```

## 架构概览

```text
前端（Electron + React）
  -> HTTP/WebSocket
后端（Spring Boot Agent Services）
  -> LLM/STT/TTS Providers
  -> System Actions（截图、鼠标、键盘）
```

## 仓库结构

```text
lavis/
├── src/main/java/com/lavis/   # Java 后端
├── src/main/resources/        # 后端配置与资源
├── frontend/                  # Electron + React 前端
└── docs/                      # 双语用户文档
```

## 贡献方式

欢迎提交 PR，建议流程：
1. 新建功能分支
2. 保持改动聚焦、附带必要文档说明
3. 在 PR 描述中附上验证步骤

## Roadmap

- 提升任务执行稳定性与失败恢复能力
- 丰富 Skills 生态与管理体验
- 进一步提升跨平台打包一致性

## 许可证

MIT
