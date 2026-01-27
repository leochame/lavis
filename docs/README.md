# Lavis 开发文档

> Lavis - macOS 系统级多模态 AI 智能体

**欢迎来到 Lavis 开发文档！** 本目录包含所有开发相关的文档。

---

## 🚀 快速导航

### 新手入门
- 👤 **用户**：[用户指南（中文）](User-Guide-zh.md) | [User Guide (English)](User-Guide-en.md)
- 👨‍💻 **开发者**：[开发者构建指南（中文）](Developer-Build-and-Packaging-zh.md) | [Developer Guide (English)](Developer-Build-and-Packaging-en.md)

### 核心文档
- 📋 **完整索引**：[INDEX.md](INDEX.md) - 所有文档的详细索引
- 🏗️ **系统架构**：[ARCHITECTURE.md](ARCHITECTURE.md) - 系统架构与数据流

---

## 💾 数据库集成（重要）

**推荐方案**：[SQLite 集成](Database-Integration-SQLite.md) ⭐⭐⭐⭐⭐

**为什么选择 SQLite**：
- ✅ **Electron 前端可以直接访问**：通过 better-sqlite3，毫秒级加载
- ✅ **支持 AI 向量搜索**：sqlite-vec 扩展
- ✅ **通用格式**：任何工具都能打开
- ✅ **行业标准**：VS Code、Obsidian、LangChain 都在用

---

## 🔧 功能增强计划

### 核心功能（按优先级）

1. **记忆管理系统** - [查看文档](Enhancement-Plan-Memory-Cron-Skills.md)
   - 支持 7×24 小时长期运行
   - 自动清理历史截图
   - 智能压缩对话历史

2. **定时任务系统** - [查看文档](Enhancement-Plan-Part2-Scheduler-Skills.md)
   - Cron 表达式调度
   - 任务持久化
   - 执行历史记录

3. **Skills 插件系统** - [查看文档](Enhancement-Plan-Part2-Scheduler-Skills.md)
   - Markdown 格式定义
   - 动态加载
   - 参数化执行

---

## 📋 实施路线图

```
第一阶段：数据库集成
  ├─ SQLite + Spring Boot 配置
  ├─ 数据表结构设计与迁移
  ├─ JPA 实体类和 Repository
  └─ Electron 前端数据库访问

第二阶段：记忆管理系统
  ├─ 自动清理历史截图
  ├─ 智能压缩对话历史
  ├─ 会话持久化
  └─ 定时清理任务

第三阶段：定时任务系统
  ├─ Cron 表达式调度
  ├─ 任务持久化与恢复
  ├─ 执行历史记录
  └─ 任务管理 UI

第四阶段：Skills 插件系统
  ├─ Markdown 格式定义
  ├─ 动态加载与热重载
  ├─ 与 Agent Tools 集成
  └─ 技能市场 UI
```

---

## 📚 文档结构

```
docs/
├── README.md                           # 本文件（文档入口）
├── INDEX.md                            # 完整文档索引
├── ARCHITECTURE.md                     # 系统架构
├── User-Guide-zh.md                    # 用户指南（中文）
├── User-Guide-en.md                    # 用户指南（英文）
├── Developer-Build-and-Packaging-zh.md # 开发者指南（中文）
├── Developer-Build-and-Packaging-en.md # 开发者指南（英文）
├── Database-Integration-SQLite.md      # SQLite 集成（推荐）
├── Enhancement-Plan-Memory-Cron-Skills.md      # 记忆管理
├── Enhancement-Plan-Part2-Scheduler-Skills.md  # 定时任务 + Skills
└── archive/                            # 归档文档
    ├── Development-History.md
    ├── Gemini-Hackathon-Improvements.md
    ├── JSON-vs-ToolCall-Comparison.md
    └── Plan-ToolCall-Migration-Analysis.md
```

---

## 🔑 关键技术栈

### 后端
- Java 21 + Spring Boot 3.5.9
- LangChain4j 0.35.0
- SQLite 3.45.0 + Spring Data JPA

### 前端
- Electron 40.x + React 19.x
- TypeScript 5.9.x + Vite 7.x
- better-sqlite3（数据库访问）

---

## 🐛 遇到问题？

1. 查看 [INDEX.md](INDEX.md) 的"调试与故障排除"章节
2. 搜索 [Issues](https://github.com/yourusername/lavis/issues)
3. 提交新的 Issue

---

## 🤝 贡献

欢迎贡献代码和文档！请查看 [INDEX.md](INDEX.md) 的"贡献指南"章节。

---

## 📄 许可证

MIT License

---

**快速链接**：
- [完整文档索引](INDEX.md)
- [系统架构](ARCHITECTURE.md)
- [SQLite 集成](Database-Integration-SQLite.md)
- [增强计划](Enhancement-Plan-Memory-Cron-Skills.md)
