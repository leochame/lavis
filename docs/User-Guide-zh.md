# Lavis 用户使用文档（中文）

> 面向日常使用者，集中说明安装、启动、环境配置、任务配置与常见问题。  
> 目标：把“怎么配环境、怎么配各类配置、怎么配 task”放在一个入口里。

---

## 1. 适用范围

- 系统：macOS（Intel / Apple Silicon）
- 场景：Lavis 桌面应用使用与基础运维
- 不包含：源码开发细节与打包流水线（见构建文档）

---

## 2. 快速启动

### 2.1 安装应用

1. 打开 DMG 安装包（如 `Lavis-1.0.0-arm64.dmg`）。
2. 将 `Lavis AI.app` 拖入“应用程序”。
3. 首次运行若出现安全提示，在“系统设置 -> 隐私与安全性”中允许。

### 2.2 启动与权限

1. 启动 `Lavis AI.app`。
2. 首次授权：
   - 屏幕录制
   - 辅助功能
   - 麦克风（可选，语音功能需要）

---

## 3. 环境配置（.env / 环境变量 / properties）

## 3.1 推荐方式：项目根目录 `.env`

```bash
cp .env.example .env
```

最常用字段（直接 Spring Key）：

```properties
app.llm.models.fast-model.api-key=
app.llm.models.fast-model.base-url=
app.llm.models.fast-model.model-name=gemini-3-flash-preview

app.llm.models.whisper.api-key=
app.llm.models.whisper.base-url=
app.llm.models.whisper.model-name=gemini-3-flash-preview

app.llm.models.tts.api-key=
app.llm.models.tts.model-name=gemini-2.5-flash-preview-tts
app.llm.models.tts.voice=Kore
app.llm.models.tts.format=wav
```

也支持简写别名（后端会自动映射）：

- `LAVIS_CHAT_API_KEY` / `LAVIS_CHAT_BASE_URL` / `LAVIS_CHAT_MODEL_NAME`
- `LAVIS_STT_API_KEY` / `LAVIS_STT_BASE_URL` / `LAVIS_STT_MODEL_NAME`
- `LAVIS_TTS_API_KEY` / `LAVIS_TTS_MODEL_NAME` / `LAVIS_TTS_VOICE` / `LAVIS_TTS_FORMAT`

兼容旧字段：

- `GEMINI_API_KEY`（会作为三路模型共享 key）

## 3.2 环境变量方式

```bash
export GEMINI_API_KEY=your_key
open /Applications/Lavis\ AI.app
```

## 3.3 `application.properties` 方式（高级）

```bash
cp src/main/resources/application.properties.example src/main/resources/application.properties
```

在 `application.properties` 中可覆盖服务端口、调度器、技能目录等。

---

## 4. 常用配置项清单

| 配置项 | 默认值 | 说明 |
|---|---:|---|
| `server.port` | `18765` | 后端 HTTP 端口 |
| `tasks.directory` | `.task` | 任务文件目录（相对项目根目录） |
| `tasks.hot-reload.enabled` | `true` | 是否监听 `.task` 文件变更并自动刷新 |
| `skills.directory` | `${user.home}/.lavis/skills` | Skills 存储目录 |
| `skills.hot-reload.enabled` | `true` | Skills 热加载开关 |
| `scheduler.task.timeout.seconds` | `300` | 单次任务执行超时 |
| `scheduler.thread.pool.size` | `10` | 调度线程池大小 |
| `scheduler.history.retention.days` | `90` | 调度历史保留天数 |

---

## 5. Task 配置（重点）

Lavis 支持两类任务来源：

1. 面板中手动创建（`sourceType=MANUAL`）
2. `.task/*.task.md` 文件驱动（`sourceType=FILE`）

## 5.1 面板手动任务（Scheduler 面板）

### 调度模式

- `CRON`：按 Cron 表达式触发（支持 5 或 6 段）
- `LOOP`：按秒循环触发（`intervalSeconds > 0`）

### 执行模式

- `REQUEST`：模拟用户请求（走聊天链路）
- `COMMAND`：执行命令（支持前缀）

### `COMMAND` 常见前缀

- `shell:<command>`：执行 Shell
- `agent:<goal>`：以 agent 目标执行
- `request:<text>`：按请求执行（非 orchestrator）
- `request-task:<text>`：按请求执行（orchestrator）

## 5.2 文件任务（`.task/*.task.md`）

任务文件格式：

```markdown
---
id: daily-brief
name: Daily Brief
description: Workday summary
enabled: true
cron: "0 9 * * 1-5"      # 或 every_seconds: 300（二选一）
mode: request            # request 或 script
use_orchestrator: true   # 仅 request 模式有效
---
Summarize today's priorities in 3 bullets.
```

字段说明：

- `cron` 与 `every_seconds` 必须二选一，至少有一个
- `mode: request`：正文按“用户请求”执行
- `mode: script`：正文按 shell 脚本执行
- `enabled`：是否启用
- `id` 不写时默认取文件名（去掉 `.task.md`）

注意：

- 文件任务由 `.task` 目录管理，不能在面板直接编辑/删除。

---

## 6. Skills 配置

默认目录：

```text
~/.lavis/skills
```

可在 `application.properties` 覆盖：

```properties
skills.directory=/your/custom/path
skills.hot-reload.enabled=true
skills.hot-reload.interval.ms=5000
```

---

## 7. 面板与窗口

- 面板模式默认窗口已放大，信息密度更高。
- 仍支持拖拽移动与手动缩放（有最小尺寸保护）。
- 胶囊模式保留独立视觉和交互逻辑。

---

## 8. 常见问题

### Q1: 一直显示离线/红色状态？

- 先检查后端是否启动。
- 检查 `server.port` 与前端连接端口是否一致（默认 `18765`）。

### Q2: Task 没有按时触发？

- 检查任务是否 `enabled=true`。
- `CRON` 模式检查表达式是否合法。
- `LOOP` 模式检查 `intervalSeconds` 是否大于 0。
- 查看 Scheduler 历史日志定位失败原因。

### Q3: `.task` 文件改了不生效？

- 确认文件位于 `tasks.directory`。
- 确认文件名后缀为 `.task.md`。
- 确认 `tasks.hot-reload.enabled=true`。

---

## 9. 相关文档

- English User Guide: `docs/User-Guide-en.md`
