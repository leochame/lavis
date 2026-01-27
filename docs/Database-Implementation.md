# Lavis 数据库实现说明

## 概述

Lavis 使用 **SQLite** 作为本地数据库，通过 Spring Data JPA 和 Flyway 进行管理。数据库文件位于 `~/.lavis/data/lavis.db`。

## 已实现功能

### 1. 数据持久化

✅ **6 张核心数据表**：
- `scheduled_tasks` - 定时任务定义
- `task_run_logs` - 任务执行历史
- `user_sessions` - 用户会话
- `session_messages` - 会话消息
- `user_preferences` - 用户偏好设置
- `agent_skills` - Agent 技能插件

### 2. JPA 实体与仓储

✅ **完整的 JPA 实现**：
- 6 个实体类（Entity）with 生命周期回调
- 6 个仓储接口（Repository）with 自定义查询方法
- 自动时间戳管理（created_at, updated_at）
- 外键约束和级联删除

### 3. 数据库迁移

✅ **Flyway 自动迁移**：
- 版本化 SQL 脚本（V1__Initial_Schema.sql）
- 应用启动时自动执行迁移
- 支持增量更新和回滚

### 4. 向量搜索支持

✅ **预留向量搜索能力**：
- `agent_skills` 表包含 `embedding BLOB` 字段
- 可存储技能的向量表示（如 OpenAI embeddings）
- 未来可集成 sqlite-vec 扩展实现语义检索

**使用场景**：
- 根据用户意图查找最相关的 Agent 技能
- 语义化技能推荐
- 智能技能匹配

### 5. 后端数据访问

✅ **Spring Data JPA 集成**：
```java
// 示例：保存用户偏好
UserPreferenceEntity pref = new UserPreferenceEntity();
pref.setPreferenceKey("theme");
pref.setPreferenceValue("dark");
pref.setValueType("string");
userPreferenceRepository.save(pref);

// 示例：查询会话消息
List<SessionMessageEntity> messages =
    sessionMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

// 示例：查询启用的技能
List<AgentSkillEntity> skills =
    agentSkillRepository.findByEnabledOrderByUseCountDesc(true);
```

### 6. 前端数据访问（规划中）

⏳ **Electron + better-sqlite3**：
- Electron 主进程可直接读取 SQLite 数据库
- 无需等待 Java 后端启动即可加载历史数据
- 毫秒级查询性能

## 数据表详细说明

### scheduled_tasks - 定时任务表

**用途**：存储 Cron 定时任务配置

**字段**：
- `id` (TEXT) - 任务 ID
- `name` (TEXT) - 任务名称
- `description` (TEXT) - 任务描述
- `cron_expression` (TEXT) - Cron 表达式
- `command` (TEXT) - 执行命令
- `enabled` (INTEGER) - 是否启用
- `last_run_at` (TEXT) - 最后执行时间
- `last_run_status` (TEXT) - 最后执行状态
- `run_count` (INTEGER) - 执行次数

**索引**：
- `idx_tasks_enabled` - 快速查询启用的任务

### task_run_logs - 任务执行日志表

**用途**：记录定时任务的执行历史

**字段**：
- `id` (INTEGER) - 自增 ID
- `task_id` (TEXT) - 关联任务 ID
- `start_time` (TEXT) - 开始时间
- `end_time` (TEXT) - 结束时间
- `status` (TEXT) - 执行状态
- `result` (TEXT) - 执行结果
- `error` (TEXT) - 错误信息
- `duration_ms` (INTEGER) - 执行时长（毫秒）

**索引**：
- `idx_logs_task_id` - 按任务查询日志
- `idx_logs_start_time` - 按时间查询日志

### user_sessions - 用户会话表

**用途**：存储用户对话会话

**字段**：
- `id` (TEXT) - 会话 ID
- `session_key` (TEXT) - 会话密钥（唯一）
- `created_at` (TEXT) - 创建时间
- `updated_at` (TEXT) - 更新时间
- `last_active_at` (TEXT) - 最后活跃时间
- `message_count` (INTEGER) - 消息数量
- `total_tokens` (INTEGER) - 总 Token 数
- `metadata` (TEXT) - 元数据（JSON）

**索引**：
- `idx_sessions_last_active` - 按活跃时间查询

### session_messages - 会话消息表

**用途**：存储会话中的所有消息

**字段**：
- `id` (INTEGER) - 自增 ID
- `session_id` (TEXT) - 关联会话 ID
- `message_type` (TEXT) - 消息类型（user/assistant/system）
- `content` (TEXT) - 消息内容
- `has_image` (INTEGER) - 是否包含图片
- `token_count` (INTEGER) - Token 数量
- `created_at` (TEXT) - 创建时间

**索引**：
- `idx_messages_session_id` - 按会话查询消息
- `idx_messages_created_at` - 按时间查询消息

### user_preferences - 用户偏好表

**用途**：存储用户配置和偏好设置

**字段**：
- `id` (INTEGER) - 自增 ID
- `preference_key` (TEXT) - 配置键（唯一）
- `preference_value` (TEXT) - 配置值
- `value_type` (TEXT) - 值类型（string/number/boolean/json）
- `description` (TEXT) - 描述
- `created_at` (TEXT) - 创建时间
- `updated_at` (TEXT) - 更新时间

**索引**：
- `idx_preferences_key` - 快速查询配置

### agent_skills - Agent 技能表

**用途**：存储 Agent 自定义技能和插件

**字段**：
- `id` (TEXT) - 技能 ID
- `name` (TEXT) - 技能名称
- `description` (TEXT) - 技能描述
- `category` (TEXT) - 技能分类
- `version` (TEXT) - 版本号
- `author` (TEXT) - 作者
- `content` (TEXT) - 技能内容（Markdown）
- `command` (TEXT) - 执行命令
- `enabled` (INTEGER) - 是否启用
- `install_source` (TEXT) - 安装来源
- `last_used_at` (TEXT) - 最后使用时间
- `use_count` (INTEGER) - 使用次数
- `embedding` (BLOB) - 向量表示（用于语义检索）

**索引**：
- `idx_skills_enabled` - 快速查询启用的技能
- `idx_skills_category` - 按分类查询技能

## 向量搜索实现指南

### 1. 存储向量

```java
// 使用 OpenAI 或其他模型生成 embedding
float[] embedding = generateEmbedding(skill.getDescription());

// 转换为字节数组存储
ByteBuffer buffer = ByteBuffer.allocate(embedding.length * 4);
for (float f : embedding) {
    buffer.putFloat(f);
}
skill.setEmbedding(buffer.array());

agentSkillRepository.save(skill);
```

### 2. 向量检索（未来集成 sqlite-vec）

```sql
-- 使用 sqlite-vec 扩展进行向量搜索
SELECT
    id, name, description,
    vec_distance(embedding, ?) AS distance
FROM agent_skills
WHERE enabled = 1
ORDER BY distance
LIMIT 10;
```

### 3. 语义检索流程

1. 用户输入查询："帮我截图"
2. 生成查询的向量表示
3. 在 agent_skills 表中进行向量相似度搜索
4. 返回最相关的技能
5. 执行匹配的技能

## 数据库配置

### application.properties

```properties
# SQLite 数据库配置
spring.datasource.url=jdbc:sqlite:${user.home}/.lavis/data/lavis.db
spring.datasource.driver-class-name=org.sqlite.JDBC

# JPA 配置
spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect
spring.jpa.hibernate.ddl-auto=none
spring.jpa.show-sql=false

# Flyway 配置
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=true
```

## 数据库管理

### 查看数据库

```bash
# 使用 sqlite3 命令行
sqlite3 ~/.lavis/data/lavis.db

# 查看所有表
.tables

# 查看表结构
.schema agent_skills

# 查询数据
SELECT * FROM user_preferences;

# 退出
.quit
```

### 数据备份

SQLite 是文件数据库，直接复制文件即可备份：

```bash
# 手动备份
cp ~/.lavis/data/lavis.db ~/.lavis/data/lavis_backup_$(date +%Y%m%d).db

# 使用系统备份工具
# - macOS: Time Machine
# - Linux: rsync, tar
# - Windows: File History
```

## 性能优化

### 1. 索引优化

所有表都已创建必要的索引：
- 主键索引（自动）
- 外键索引
- 查询频繁的字段索引

### 2. 批量操作

```java
// 使用 @Transactional 批量插入
@Transactional
public void saveMessages(List<SessionMessageEntity> messages) {
    sessionMessageRepository.saveAll(messages);
}
```

### 3. 连接池配置

Spring Boot 默认使用 HikariCP 连接池，已在 application.properties 中配置：

```properties
spring.jpa.properties.hibernate.jdbc.batch_size=20
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
```

## 未来扩展

### 1. sqlite-vec 集成

- 安装 sqlite-vec 扩展
- 创建向量索引表
- 实现高性能向量检索

### 2. 全文搜索

- 使用 SQLite FTS5 扩展
- 对技能内容和消息进行全文索引
- 支持中文分词

### 3. 数据分析

- 统计任务执行成功率
- 分析用户使用习惯
- 技能使用热度排行

## 总结

Lavis 数据库实现提供了：
- ✅ 完整的数据持久化能力
- ✅ 灵活的 JPA 访问接口
- ✅ 自动化的数据库迁移
- ✅ 向量搜索预留支持
- ✅ 高性能的查询优化
- ✅ 简单的备份方案

数据库层已为 Phase 2（记忆管理）、Phase 3（定时任务）、Phase 4（技能系统）提供了坚实的基础。
