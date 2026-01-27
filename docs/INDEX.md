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

## 💾 数据库实现

| 文档 | 描述 | 状态 |
|------|------|------|
| [数据库实现说明](Database-Implementation.md) | SQLite 数据库完整实现文档 | ✅ 已完成 |

**已实现功能**：
- ✅ **6 张核心数据表**：定时任务、执行日志、会话、消息、偏好、技能
- ✅ **JPA 实体与仓储**：完整的 Spring Data JPA 集成
- ✅ **Flyway 自动迁移**：版本化 SQL 脚本管理
- ✅ **向量搜索支持**：agent_skills 表预留 embedding 字段
- ✅ **后端数据访问**：通过 JPA Repository 访问数据库

**数据持久化内容**：
- 定时任务（Cron Jobs）
- 用户会话（Sessions）
- 会话消息（Messages）
- 用户偏好（Preferences）
- Agent 技能（Skills with Vector Embeddings）

---

## 🔧 已实现功能

### 1. 记忆管理系统 ✅

**状态**：已完成（Phase 2）
**实现日期**：2026-01-27

**核心功能**：
- ✅ **会话持久化**：自动保存对话到 SQLite 数据库（user_sessions, session_messages 表）
- ✅ **自动清理截图**：保留最近 10 张截图，自动删除旧截图
- ✅ **智能压缩对话**：超过 100K tokens 自动使用 AI 总结压缩
- ✅ **定时清理任务**：每小时自动清理旧会话（>30 天）和旧截图
- ✅ **内存监控**：实时监控 JVM 堆内存使用情况
- ✅ **会话统计**：跟踪消息数量、token 使用量、会话时间

**实现组件**：
- `SessionStore.java` - 会话持久化服务
- `ImageCleanupService.java` - 截图清理服务（@Scheduled 每小时）
- `ContextCompactor.java` - 上下文压缩服务（AI 驱动）
- `MemoryManager.java` - 记忆管理协调器

**配置参数**（默认值）：
```properties
memory.keep.images=10                    # 保留截图数量
memory.token.threshold=100000            # 压缩触发阈值
memory.keep.recent.messages=10           # 压缩时保留最近消息数
memory.session.retention.days=30         # 会话保留天数
memory.cleanup.interval.ms=3600000       # 清理间隔（1小时）
```

**数据库表**：
- `user_sessions` - 会话元数据（session_key, message_count, total_tokens）
- `session_messages` - 消息历史（message_type, content, has_image, token_count）

**API 接口**：
- `AgentService.getMemoryStats()` - 获取内存统计
- `AgentService.getSessionStats()` - 获取会话统计
- `AgentService.resetConversation()` - 重置会话

**详细文档**：[Phase 2 实现总结](Phase2-Memory-Management-Implementation.md)

---

## 🚧 计划中功能

### 2. 定时任务系统（Phase 3）

**目标**：实现 7×24 小时自动化任务调度

**核心功能**：
- Cron 表达式调度
- 任务持久化（使用 scheduled_tasks 表）
- 执行历史记录（使用 task_run_logs 表）
- 支持 Agent 任务和 Shell 命令
- 任务管理 UI

**实现文件**：
- `ScheduledTaskService.java`
- `TaskExecutor.java`
- REST API：`/api/scheduler/tasks`

### 3. Skills 插件系统（Phase 4）

**目标**：提升系统扩展性，允许用户自定义工具和技能

**核心功能**：
- Markdown 格式定义技能
- 动态加载和热重载
- 参数化执行
- 与 Agent Tools 集成
- 向量搜索支持（使用 agent_skills 表的 embedding 字段）

**实现文件**：
- `SkillManager.java`
- `SkillLoader.java`
- `SkillExecutor.java`
- 技能目录：`~/.lavis/skills/`

---

## 📋 实施计划

### 第一阶段：数据库集成 ✅

**状态**：已完成
**完成日期**：2026-01-27

**核心功能**：
- SQLite 数据库配置与集成
- 数据表结构设计与迁移
- JPA 实体类和 Repository 实现
- 后端数据库访问（通过 JPA）

**任务清单**：
- [x] 添加 SQLite 依赖到 `pom.xml`
- [x] 配置 `application.properties`
- [x] 创建 Flyway 迁移脚本（V1__Initial_Schema.sql）
- [x] 实现 JPA 实体类（ScheduledTaskEntity, UserSessionEntity, AgentSkillEntity）
- [x] 实现 JPA Repository 接口
- [x] 验证后端数据库访问
- [ ] 在 Electron 中集成 `better-sqlite3`
- [ ] 实现前端数据库访问模块（database.ts）
- [ ] 测试前端直接读取数据库

**参考文档**：[数据库实现说明](Database-Implementation.md)

---

### 第二阶段：记忆管理系统 ✅

**状态**：已完成
**完成日期**：2026-01-27

**目标**：支持 7×24 小时长期运行，自动管理内存和历史数据

**核心功能**：
- 自动清理历史截图（保留最近 10 张）
- 智能压缩对话历史（超过 100K tokens 自动总结）
- 会话持久化（SQLite 数据库）
- 定时清理任务（每小时执行）
- 内存占用监控

**任务清单**：
- [x] 实现 `MemoryManager.java`（记忆管理器）
- [x] 实现 `ImageCleanupService.java`（图片清理服务）
- [x] 实现 `ContextCompactor.java`（上下文压缩器）
- [x] 实现 `SessionStore.java`（会话持久化）
- [x] 集成到 `AgentService.java`
- [x] 配置定时清理任务（@Scheduled）
- [x] 实现内存占用监控
- [ ] 测试长时间运行（24 小时以上）
- [ ] 验证内存占用稳定性

**实现文档**：[Phase 2 实现总结](Phase2-Memory-Management-Implementation.md)

**已实现的功能**：
1. **会话持久化**：每条消息自动保存到数据库，包含类型、内容、token 数、是否含图片等元数据
2. **自动截图清理**：
   - 内存中：ImageContentCleanableChatMemory 自动清理旧截图
   - 数据库中：定期删除旧的图片消息，保留最近 10 条
3. **智能上下文压缩**：
   - 监控 token 使用量（估算：1 token ≈ 4 字符）
   - 超过 100K tokens 时自动触发压缩
   - 使用 AI 总结旧消息，保留最近 10 条完整消息
4. **定时维护任务**：
   - 每小时自动执行清理
   - 删除 30 天前的旧会话
   - 清理当前会话的旧截图
5. **内存监控**：
   - 实时监控 JVM 堆内存使用
   - 提供内存统计 API（已用/最大/使用率）
6. **会话统计**：
   - 消息数量统计
   - Token 使用量统计
   - 会话活跃时间跟踪

**数据库集成**：
- 使用 `user_sessions` 表存储会话元数据
- 使用 `session_messages` 表存储完整对话历史
- 支持按会话查询、按时间过滤、按类型筛选

**API 接口**：
- `getMemoryStats()` - 获取 JVM 内存统计
- `getSessionStats()` - 获取当前会话统计
- `resetConversation()` - 重置会话（清空内存并创建新会话）

---

### 第三阶段：定时任务系统（待实现）

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
