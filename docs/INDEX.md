# Lavis 开发文档索引

> 本文档是 Lavis 项目的开发文档总索引，方便 Claude Code 和开发者快速定位所需文档。

**最后更新**：2026-01-27

---

## 📚 文档导航

### 🚀 快速开始

| 文档 | 描述 | 适用人群 |
|------|------|---------|
| [用户指南（中文）](User-Guide-zh.md) | 安装、运行、基础使用 | 终端用户 |
| [用户指南（英文）](User-Guide-en.md) | Installation, running, basic usage | End users |
| [开发者构建指南（中文）](Developer-Build-and-Packaging-zh.md) | 构建、打包、GraalVM Native Image | 开发者 |
| [开发者构建指南（英文）](Developer-Build-and-Packaging-en.md) | Build, packaging, GraalVM Native Image | Developers |

---

## 🏗️ 架构与设计

| 文档 | 描述 | 关键内容 |
|------|------|---------|
| [系统架构](ARCHITECTURE.md) | 系统架构与数据流详细说明 | 认知层、感知层、动作层、WebSocket 通信 |

---

## 💾 数据库集成

| 文档 | 描述 | 推荐度 |
|------|------|--------|
| [SQLite 集成方案](Database-Integration-SQLite.md) | SQLite + Spring Boot + Electron | ⭐⭐⭐⭐⭐ |

**为什么选择 SQLite**：
- ✅ **Electron 前端可以直接访问**：通过 better-sqlite3，毫秒级加载历史记录
- ✅ **支持 AI 向量搜索**：sqlite-vec 扩展，实现 Skills 语义检索
- ✅ **通用格式**：任何工具都能打开，调试友好
- ✅ **行业标准**：VS Code、Obsidian、LangChain 等都使用 SQLite

**数据持久化内容**：
- 定时任务（Cron Jobs）
- 用户会话（Sessions）
- 会话消息（Messages）
- 用户偏好（Preferences）
- Agent 技能（Skills）

---

## 🔧 功能增强计划

### 核心增强功能

| 文档 | 描述 | 实施优先级 |
|------|------|-----------|
| [记忆管理系统](Enhancement-Plan-Memory-Cron-Skills.md) | 长期运行、自动清理、智能压缩 | 🔴 高 |
| [定时任务系统](Enhancement-Plan-Part2-Scheduler-Skills.md) | Cron 调度、任务持久化、执行历史 | 🔴 高 |
| [Skills 插件系统](Enhancement-Plan-Part2-Scheduler-Skills.md) | Markdown 格式、动态加载、参数化执行 | 🟡 中 |

### 功能特性

#### 1. 记忆管理系统
- **目标**：支持 7×24 小时长期运行
- **核心功能**：
  - 自动清理历史截图（保留最近 10 张）
  - 智能压缩对话历史（超过 100K tokens 自动总结）
  - 会话持久化（JSONL 格式）
  - 定时清理任务（每小时执行）
- **实现文件**：
  - `MemoryManager.java`
  - `ImageCleanupService.java`
  - `ContextCompactor.java`
  - `SessionStore.java`

#### 2. 定时任务系统
- **目标**：实现 7×24 小时自动化任务
- **核心功能**：
  - Cron 表达式调度
  - 任务持久化（重启后恢复）
  - 执行历史记录
  - 支持 Agent 任务和 Shell 命令
- **实现文件**：
  - `ScheduledTaskService.java`
  - `TaskExecutor.java`
  - `TaskStore.java`
  - REST API：`/api/scheduler/tasks`

#### 3. Skills 插件系统
- **目标**：允许用户自定义工具和扩展功能
- **核心功能**：
  - Markdown 格式定义（参考 Clawdbot）
  - 动态加载和热重载
  - 参数化执行
  - 与 Agent Tools 集成
- **实现文件**：
  - `SkillManager.java`
  - `SkillLoader.java`
  - `SkillExecutor.java`
  - 技能目录：`~/.lavis/skills/`

---

## 📋 实施计划

### 第一阶段：数据库集成

**目标**：完成 SQLite 数据库集成，为后续功能提供持久化基础

**核心功能**：
- SQLite 数据库配置与集成
- 数据表结构设计与迁移
- JPA 实体类和 Repository 实现
- Electron 前端数据库访问
- 自动备份机制

**任务清单**：
- [ ] 添加 SQLite 依赖到 `pom.xml`
- [ ] 配置 `application.properties`
- [ ] 创建 Flyway 迁移脚本（V1__Initial_Schema.sql）
- [ ] 实现 JPA 实体类（ScheduledTaskEntity, UserSessionEntity, AgentSkillEntity）
- [ ] 实现 JPA Repository 接口
- [ ] 在 Electron 中集成 `better-sqlite3`
- [ ] 实现前端数据库访问模块（database.ts）
- [ ] 测试前端直接读取数据库

**参考文档**：[SQLite 集成方案](Database-Integration-SQLite.md)

---

### 第二阶段：记忆管理系统

**目标**：支持 7×24 小时长期运行，自动管理内存和历史数据

**核心功能**：
- 自动清理历史截图（保留最近 10 张）
- 智能压缩对话历史（超过 100K tokens 自动总结）
- 会话持久化（JSONL 格式）
- 定时清理任务（每小时执行）
- 内存占用监控

**任务清单**：
- [ ] 实现 `MemoryManager.java`（记忆管理器）
- [ ] 实现 `ImageCleanupService.java`（图片清理服务）
- [ ] 实现 `ContextCompactor.java`（上下文压缩器）
- [ ] 实现 `SessionStore.java`（会话持久化）
- [ ] 集成到 `AgentService.java`
- [ ] 配置定时清理任务（@Scheduled）
- [ ] 实现内存占用监控
- [ ] 测试长时间运行（24 小时以上）
- [ ] 验证内存占用稳定性

**参考文档**：[记忆管理系统](Enhancement-Plan-Memory-Cron-Skills.md)

---

### 第三阶段：定时任务系统

**目标**：实现自动化任务调度，支持 Cron 表达式和任务管理

**核心功能**：
- Cron 表达式调度
- 任务持久化（重启后恢复）
- 执行历史记录
- 支持 Agent 任务和 Shell 命令
- 任务管理 UI

**任务清单**：
- [ ] 实现 `ScheduledTaskService.java`（任务调度服务）
- [ ] 实现 `TaskExecutor.java`（任务执行器）
- [ ] 实现 `TaskStore.java`（任务持久化，使用 SQLite）
- [ ] 实现 REST API（SchedulerController）
  - [ ] POST `/api/scheduler/tasks` - 创建任务
  - [ ] GET `/api/scheduler/tasks` - 获取所有任务
  - [ ] POST `/api/scheduler/tasks/{id}/stop` - 停止任务
  - [ ] DELETE `/api/scheduler/tasks/{id}` - 删除任务
  - [ ] GET `/api/scheduler/tasks/{id}/history` - 获取执行历史
- [ ] 创建前端 UI（SchedulerPanel.tsx）
  - [ ] 任务列表展示
  - [ ] 创建任务表单
  - [ ] 任务执行历史查看
- [ ] 测试定时任务（如每日签到）
- [ ] 测试任务重启后恢复

**参考文档**：[定时任务系统](Enhancement-Plan-Part2-Scheduler-Skills.md)

---

### 第四阶段：Skills 插件系统

**目标**：提升系统扩展性，允许用户自定义工具和技能

**核心功能**：
- Markdown 格式定义（参考 Clawdbot）
- 动态加载和热重载
- 参数化执行
- 与 Agent Tools 集成
- 技能市场 UI

**任务清单**：
- [ ] 实现 `SkillManager.java`（技能管理器）
- [ ] 实现 `SkillLoader.java`（技能加载器）
  - [ ] 解析 SKILL.md 文件
  - [ ] 提取 frontmatter 元数据
  - [ ] 提取命令和参数
- [ ] 实现 `SkillExecutor.java`（技能执行器）
- [ ] 实现 REST API（SkillsController）
  - [ ] GET `/api/skills` - 获取所有技能
  - [ ] GET `/api/skills/{id}` - 获取技能详情
  - [ ] POST `/api/skills/{id}/execute` - 执行技能
  - [ ] POST `/api/skills/reload` - 重新加载技能
- [ ] 创建示例技能
  - [ ] screenshot（截图工具）
  - [ ] genshin-signin（原神签到）
- [ ] 集成到 Agent Tools（AgentTools.java）
  - [ ] `executeSkill` 工具
  - [ ] `listSkills` 工具
- [ ] 创建前端 UI（SkillsPanel.tsx）
  - [ ] 技能列表展示
  - [ ] 技能详情查看
  - [ ] 技能执行界面
- [ ] 测试技能加载和执行
- [ ] 测试热重载功能

**参考文档**：[Skills 插件系统](Enhancement-Plan-Part2-Scheduler-Skills.md)

---

## 🗂️ 项目结构

### 后端（Java + Spring Boot）

```
src/main/java/com/lavis/
├── cognitive/              # 认知层
│   ├── AgentService.java
│   ├── AgentTools.java
│   └── TaskContext.java
├── perception/             # 感知层（截图）
├── action/                 # 动作层（鼠标键盘）
├── controller/             # REST API
├── websocket/              # WebSocket 通信
├── service/                # TTS/ASR 等服务
├── memory/                 # 记忆管理（新增）
│   ├── MemoryManager.java
│   ├── ImageCleanupService.java
│   ├── ContextCompactor.java
│   └── SessionStore.java
├── scheduler/              # 定时任务（新增）
│   ├── ScheduledTaskService.java
│   ├── TaskExecutor.java
│   └── TaskStore.java
├── skills/                 # Skills 系统（新增）
│   ├── SkillManager.java
│   ├── SkillLoader.java
│   └── SkillExecutor.java
├── entity/                 # JPA 实体类（新增）
│   ├── ScheduledTaskEntity.java
│   ├── UserSessionEntity.java
│   └── AgentSkillEntity.java
└── repository/             # JPA Repository（新增）
    ├── ScheduledTaskRepository.java
    ├── UserSessionRepository.java
    └── AgentSkillRepository.java
```

### 前端（Electron + React）

```
frontend/
├── electron/               # Electron 主进程
│   ├── main.ts
│   ├── backend-manager.ts
│   ├── database.ts         # SQLite 访问（新增）
│   └── preload.ts
├── src/                    # React UI
│   ├── components/
│   │   ├── Capsule.tsx
│   │   ├── ChatPanel.tsx
│   │   ├── TaskPanel.tsx
│   │   ├── VoicePanel.tsx
│   │   ├── SchedulerPanel.tsx  # 定时任务面板（新增）
│   │   └── SkillsPanel.tsx     # 技能市场（新增）
│   ├── hooks/
│   │   ├── useWebSocket.ts
│   │   ├── useDatabase.ts      # SQLite hooks（新增）
│   │   └── useVoiceRecorder.ts
│   └── store/              # Zustand 状态管理
└── scripts/                # 打包和开发工具
```

### 数据库（SQLite）

```
~/.lavis/
├── data/
│   └── lavis.db            # SQLite 数据库文件
├── skills/                 # 用户技能目录
│   ├── screenshot/
│   │   └── SKILL.md
│   └── genshin-signin/
│       └── SKILL.md
├── backups/                # 自动备份
│   └── lavis_20260127.db
└── logs/
    └── lavis.log
```

---

## 🔑 关键技术栈

### 后端
- **语言**：Java 21
- **框架**：Spring Boot 3.5.9
- **AI 框架**：LangChain4j 0.35.0
- **数据库**：SQLite 3.45.0
- **ORM**：Spring Data JPA + Hibernate
- **迁移**：Flyway

### 前端
- **桌面**：Electron 40.x
- **UI 框架**：React 19.x
- **语言**：TypeScript 5.9.x
- **构建工具**：Vite 7.x
- **状态管理**：Zustand 5.x
- **数据库访问**：better-sqlite3

---

## 📖 API 参考

### REST API

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/agent/status` | 获取系统状态 |
| POST | `/api/agent/chat` | 聊天（带截图上下文） |
| POST | `/api/agent/task` | 执行自动化任务 |
| POST | `/api/agent/reset` | 重置对话 |
| GET | `/api/agent/screenshot` | 获取屏幕截图 |
| GET | `/api/agent/history` | 获取任务历史 |

### 定时任务 API（新增）

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/scheduler/tasks` | 创建任务 |
| GET | `/api/scheduler/tasks` | 获取所有任务 |
| POST | `/api/scheduler/tasks/{id}/stop` | 停止任务 |
| DELETE | `/api/scheduler/tasks/{id}` | 删除任务 |
| GET | `/api/scheduler/tasks/{id}/history` | 获取任务执行历史 |

### Skills API（新增）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/skills` | 获取所有技能 |
| GET | `/api/skills/{id}` | 获取技能详情 |
| POST | `/api/skills/{id}/execute` | 执行技能 |
| POST | `/api/skills/reload` | 重新加载技能 |

---

## 🐛 调试与故障排除

### 常见问题

#### 1. 数据库连接失败
```bash
# 检查数据库文件是否存在
ls -la ~/.lavis/data/lavis.db

# 使用 sqlite3 命令行检查
sqlite3 ~/.lavis/data/lavis.db
.tables
```

#### 2. Electron 无法访问数据库
```bash
# 检查 better-sqlite3 是否正确安装
cd frontend
npm list better-sqlite3

# 重新安装
npm install better-sqlite3 --save
```

#### 3. 定时任务不执行
```bash
# 检查任务状态
curl http://localhost:8080/api/scheduler/tasks

# 查看日志
tail -f ~/.lavis/logs/lavis.log
```

---

## 📝 开发规范

### 代码风格
- **Java**：遵循 Google Java Style Guide
- **TypeScript**：遵循 Airbnb TypeScript Style Guide
- **命名**：使用有意义的变量名，避免缩写

### Git 提交规范
```
feat: 添加定时任务系统
fix: 修复数据库连接问题
docs: 更新 API 文档
refactor: 重构记忆管理模块
test: 添加单元测试
```

### 测试要求
- **单元测试**：覆盖率 > 70%
- **集成测试**：关键功能必须有集成测试
- **E2E 测试**：核心用户流程必须有 E2E 测试

---

## 🗄️ 归档文档

以下文档已归档到 `archive/` 目录，仅供历史参考：

- `Development-History.md` - 开发历史记录
- `Gemini-Hackathon-Improvements.md` - 黑客松改进建议
- `JSON-vs-ToolCall-Comparison.md` - 技术对比分析
- `Plan-ToolCall-Migration-Analysis.md` - 迁移分析

---

## 🤝 贡献指南

### 如何贡献

1. Fork 项目
2. 创建功能分支：`git checkout -b feature/amazing-feature`
3. 提交更改：`git commit -m 'feat: add amazing feature'`
4. 推送到分支：`git push origin feature/amazing-feature`
5. 提交 Pull Request

### 文档贡献

- 发现文档错误？请提交 Issue 或 PR
- 想要添加新文档？请先在 Issue 中讨论
- 更新文档后，记得更新本索引文件

---

## 📞 联系方式

- **项目地址**：https://github.com/yourusername/lavis
- **问题反馈**：https://github.com/yourusername/lavis/issues
- **讨论区**：https://github.com/yourusername/lavis/discussions

---

## 📄 许可证

MIT License

---

**最后更新**：2026-01-27
**维护者**：Lavis Team
