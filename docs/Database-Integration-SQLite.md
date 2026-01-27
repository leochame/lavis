# Lavis 数据库集成方案：SQLite（推荐）

## 一、为什么选择 SQLite 而不是 H2？

### 1.1 核心优势对比

| 维度 | H2 | SQLite | 为什么 SQLite 胜出 |
|------|----|----|------------------|
| **Electron 互操作性** | ❌ Node.js 无法直接访问 | ✅ better-sqlite3 原生支持 | **前端可以直接读取数据库** |
| **AI 向量支持** | ❌ 不支持 | ✅ sqlite-vec 扩展 | **支持语义检索** |
| **文件通用性** | ⚠️ Java 专有格式 | ✅ 通用格式 | **任何工具都能打开** |
| **启动速度** | ⚠️ 需要 JVM 预热 | ✅ 进程内 C 库 | **毫秒级启动** |
| **行业标准** | ⚠️ Java 生态 | ✅ Local-First 标准 | **VS Code, Obsidian 都用** |

### 1.2 Lavis 的特殊需求

1. **Electron 前端需要快速加载历史记录**
   - 用户打开应用时，不应该等待 Java 后端启动
   - SQLite 允许 Electron 直接读取数据库，毫秒级加载

2. **Agent Skills 需要语义检索**
   - 未来可能需要"找到与当前任务最相关的 Skill"
   - SQLite + sqlite-vec 可以在数据库层完成向量搜索

3. **调试和运维友好**
   - macOS 自带 `sqlite3` 命令
   - 任何数据库工具都能打开 `.db` 文件

---

## 二、Spring Boot + SQLite 集成

### 2.1 添加依赖

**pom.xml**

```xml
<dependencies>
    <!-- SQLite JDBC Driver -->
    <dependency>
        <groupId>org.xerial</groupId>
        <artifactId>sqlite-jdbc</artifactId>
        <version>3.45.0.0</version>
    </dependency>

    <!-- Hibernate SQLite Dialect -->
    <dependency>
        <groupId>org.hibernate.orm</groupId>
        <artifactId>hibernate-community-dialects</artifactId>
        <version>6.4.4.Final</version>
    </dependency>

    <!-- Spring Data JPA -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <!-- Flyway (数据库迁移) -->
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
</dependencies>
```

### 2.2 配置文件

**application.properties**

```properties
# SQLite Database 配置
spring.datasource.url=jdbc:sqlite:${user.home}/.lavis/data/lavis.db
spring.datasource.driver-class-name=org.sqlite.JDBC
spring.datasource.username=
spring.datasource.password=

# JPA 配置
spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect
spring.jpa.hibernate.ddl-auto=none
spring.jpa.show-sql=false

# Flyway 配置
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=true

# SQLite 特定配置
spring.jpa.properties.hibernate.jdbc.batch_size=20
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
```

### 2.3 自定义 SQLite 方言（可选，解决一些兼容性问题）

**SQLiteDialectCustom.java**

```java
package com.lavis.config;

import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.dialect.identity.IdentityColumnSupport;
import org.hibernate.dialect.identity.IdentityColumnSupportImpl;

public class SQLiteDialectCustom extends SQLiteDialect {

    @Override
    public IdentityColumnSupport getIdentityColumnSupport() {
        return new IdentityColumnSupportImpl() {
            @Override
            public boolean supportsIdentityColumns() {
                return true;
            }

            @Override
            public String getIdentityColumnString(int type) {
                return "INTEGER";
            }

            @Override
            public String getIdentitySelectString(String table, String column, int type) {
                return "SELECT last_insert_rowid()";
            }
        };
    }
}
```

---

## 三、数据库设计（与 H2 版本相同）

### 3.1 表结构

#### **scheduled_tasks** - 定时任务表

```sql
CREATE TABLE scheduled_tasks (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    cron_expression TEXT NOT NULL,
    command TEXT NOT NULL,
    enabled INTEGER DEFAULT 1,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    last_run_at TEXT,
    last_run_status TEXT,
    last_run_result TEXT,
    run_count INTEGER DEFAULT 0
);

CREATE INDEX idx_tasks_enabled ON scheduled_tasks(enabled);
```

#### **task_run_logs** - 任务执行日志表

```sql
CREATE TABLE task_run_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id TEXT NOT NULL,
    start_time TEXT NOT NULL,
    end_time TEXT,
    status TEXT NOT NULL,
    result TEXT,
    error TEXT,
    duration_ms INTEGER,
    FOREIGN KEY (task_id) REFERENCES scheduled_tasks(id) ON DELETE CASCADE
);

CREATE INDEX idx_logs_task_id ON task_run_logs(task_id);
CREATE INDEX idx_logs_start_time ON task_run_logs(start_time);
```

#### **user_sessions** - 用户会话表

```sql
CREATE TABLE user_sessions (
    id TEXT PRIMARY KEY,
    session_key TEXT UNIQUE NOT NULL,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    last_active_at TEXT DEFAULT (datetime('now')),
    message_count INTEGER DEFAULT 0,
    total_tokens INTEGER DEFAULT 0,
    metadata TEXT
);

CREATE INDEX idx_sessions_last_active ON user_sessions(last_active_at);
```

#### **session_messages** - 会话消息表

```sql
CREATE TABLE session_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    message_type TEXT NOT NULL,
    content TEXT NOT NULL,
    has_image INTEGER DEFAULT 0,
    token_count INTEGER,
    created_at TEXT DEFAULT (datetime('now')),
    FOREIGN KEY (session_id) REFERENCES user_sessions(id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_session_id ON session_messages(session_id);
CREATE INDEX idx_messages_created_at ON session_messages(created_at);
```

#### **user_preferences** - 用户偏好表

```sql
CREATE TABLE user_preferences (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    preference_key TEXT UNIQUE NOT NULL,
    preference_value TEXT NOT NULL,
    value_type TEXT NOT NULL,
    description TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
);

CREATE INDEX idx_preferences_key ON user_preferences(preference_key);
```

#### **agent_skills** - Agent 技能表

```sql
CREATE TABLE agent_skills (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    category TEXT,
    version TEXT,
    author TEXT,
    content TEXT NOT NULL,
    command TEXT NOT NULL,
    enabled INTEGER DEFAULT 1,
    install_source TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    last_used_at TEXT,
    use_count INTEGER DEFAULT 0,
    embedding BLOB  -- 用于存储向量（未来扩展）
);

CREATE INDEX idx_skills_enabled ON agent_skills(enabled);
CREATE INDEX idx_skills_category ON agent_skills(category);
```

---

## 四、Flyway 迁移脚本

**src/main/resources/db/migration/V1__Initial_Schema.sql**

```sql
-- 创建定时任务表
CREATE TABLE scheduled_tasks (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    cron_expression TEXT NOT NULL,
    command TEXT NOT NULL,
    enabled INTEGER DEFAULT 1,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    last_run_at TEXT,
    last_run_status TEXT,
    last_run_result TEXT,
    run_count INTEGER DEFAULT 0
);

CREATE INDEX idx_tasks_enabled ON scheduled_tasks(enabled);

-- 创建任务执行日志表
CREATE TABLE task_run_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id TEXT NOT NULL,
    start_time TEXT NOT NULL,
    end_time TEXT,
    status TEXT NOT NULL,
    result TEXT,
    error TEXT,
    duration_ms INTEGER,
    FOREIGN KEY (task_id) REFERENCES scheduled_tasks(id) ON DELETE CASCADE
);

CREATE INDEX idx_logs_task_id ON task_run_logs(task_id);

-- 创建用户会话表
CREATE TABLE user_sessions (
    id TEXT PRIMARY KEY,
    session_key TEXT UNIQUE NOT NULL,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    last_active_at TEXT DEFAULT (datetime('now')),
    message_count INTEGER DEFAULT 0,
    total_tokens INTEGER DEFAULT 0,
    metadata TEXT
);

CREATE INDEX idx_sessions_last_active ON user_sessions(last_active_at);

-- 创建会话消息表
CREATE TABLE session_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    message_type TEXT NOT NULL,
    content TEXT NOT NULL,
    has_image INTEGER DEFAULT 0,
    token_count INTEGER,
    created_at TEXT DEFAULT (datetime('now')),
    FOREIGN KEY (session_id) REFERENCES user_sessions(id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_session_id ON session_messages(session_id);

-- 创建用户偏好表
CREATE TABLE user_preferences (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    preference_key TEXT UNIQUE NOT NULL,
    preference_value TEXT NOT NULL,
    value_type TEXT NOT NULL,
    description TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
);

CREATE INDEX idx_preferences_key ON user_preferences(preference_key);

-- 创建 Agent 技能表
CREATE TABLE agent_skills (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    category TEXT,
    version TEXT,
    author TEXT,
    content TEXT NOT NULL,
    command TEXT NOT NULL,
    enabled INTEGER DEFAULT 1,
    install_source TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    last_used_at TEXT,
    use_count INTEGER DEFAULT 0,
    embedding BLOB
);

CREATE INDEX idx_skills_enabled ON agent_skills(enabled);
CREATE INDEX idx_skills_category ON agent_skills(category);
```

---

## 五、JPA 实体类（注意 SQLite 的差异）

### 5.1 ScheduledTask Entity

```java
package com.lavis.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "scheduled_tasks")
public class ScheduledTaskEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String command;

    // SQLite 使用 INTEGER 存储 BOOLEAN
    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_run_status")
    private String lastRunStatus;

    @Column(name = "last_run_result", columnDefinition = "TEXT")
    private String lastRunResult;

    @Column(name = "run_count")
    private Integer runCount = 0;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

---

## 六、Electron 前端直接访问 SQLite

### 6.1 安装 better-sqlite3

```bash
cd frontend
npm install better-sqlite3
```

### 6.2 Electron 主进程访问数据库

**frontend/electron/database.ts**

```typescript
import Database from 'better-sqlite3';
import path from 'path';
import { app } from 'electron';

let db: Database.Database | null = null;

export function initDatabase() {
  const userDataPath = app.getPath('userData');
  const dbPath = path.join(userDataPath, 'data', 'lavis.db');

  db = new Database(dbPath, { readonly: true }); // 只读模式，避免与 Java 冲突

  return db;
}

export function getDatabase() {
  if (!db) {
    throw new Error('Database not initialized');
  }
  return db;
}

// 获取最近的会话消息
export function getRecentMessages(limit: number = 50) {
  const db = getDatabase();
  const stmt = db.prepare(`
    SELECT * FROM session_messages
    ORDER BY created_at DESC
    LIMIT ?
  `);

  return stmt.all(limit);
}

// 获取所有技能
export function getAllSkills() {
  const db = getDatabase();
  const stmt = db.prepare(`
    SELECT * FROM agent_skills
    WHERE enabled = 1
    ORDER BY use_count DESC
  `);

  return stmt.all();
}

// 获取用户偏好
export function getUserPreference(key: string) {
  const db = getDatabase();
  const stmt = db.prepare(`
    SELECT preference_value FROM user_preferences
    WHERE preference_key = ?
  `);

  return stmt.get(key);
}
```

### 6.3 在 Electron 主进程中使用

**frontend/electron/main.ts**

```typescript
import { app, BrowserWindow, ipcMain } from 'electron';
import { initDatabase, getRecentMessages, getAllSkills } from './database';

app.whenReady().then(() => {
  // 初始化数据库
  initDatabase();

  // 创建窗口
  const mainWindow = new BrowserWindow({
    width: 800,
    height: 600,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
    },
  });

  // IPC 处理：获取历史消息
  ipcMain.handle('get-recent-messages', async (event, limit) => {
    return getRecentMessages(limit);
  });

  // IPC 处理：获取所有技能
  ipcMain.handle('get-all-skills', async () => {
    return getAllSkills();
  });

  mainWindow.loadURL('http://localhost:5173');
});
```

### 6.4 在 React 前端中使用

**frontend/src/hooks/useDatabase.ts**

```typescript
import { useEffect, useState } from 'react';

export function useRecentMessages(limit: number = 50) {
  const [messages, setMessages] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // 通过 Electron IPC 获取数据
    window.electron.getRecentMessages(limit).then((data) => {
      setMessages(data);
      setLoading(false);
    });
  }, [limit]);

  return { messages, loading };
}

export function useAllSkills() {
  const [skills, setSkills] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    window.electron.getAllSkills().then((data) => {
      setSkills(data);
      setLoading(false);
    });
  }, []);

  return { skills, loading };
}
```

**frontend/src/components/ChatPanel.tsx**

```typescript
import { useRecentMessages } from '../hooks/useDatabase';

export function ChatPanel() {
  // 直接从 SQLite 加载历史消息，不需要等待 Java 后端
  const { messages, loading } = useRecentMessages(50);

  if (loading) {
    return <div>Loading...</div>;
  }

  return (
    <div>
      {messages.map((msg) => (
        <div key={msg.id}>{msg.content}</div>
      ))}
    </div>
  );
}
```

---

## 七、向量搜索扩展（未来）

### 7.1 安装 sqlite-vec

```bash
# macOS
brew install sqlite-vec

# 或者下载预编译的扩展
# https://github.com/asg017/sqlite-vec
```

### 7.2 在 Java 中启用扩展

```java
@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource() {
        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + System.getProperty("user.home") + "/.lavis/data/lavis.db");

        // 加载 sqlite-vec 扩展
        try (Connection conn = dataSource.getConnection()) {
            Statement stmt = conn.createStatement();
            stmt.execute("SELECT load_extension('/path/to/vec0.dylib')");
        } catch (SQLException e) {
            // Handle error
        }

        return dataSource;
    }
}
```

### 7.3 向量搜索示例

```sql
-- 创建向量索引
CREATE VIRTUAL TABLE skill_embeddings USING vec0(
    skill_id TEXT PRIMARY KEY,
    embedding FLOAT[1536]
);

-- 插入向量
INSERT INTO skill_embeddings (skill_id, embedding)
VALUES ('skill-1', vec_f32('[0.1, 0.2, ...]'));

-- 向量搜索
SELECT
    s.id,
    s.name,
    vec_distance(e.embedding, vec_f32('[0.1, 0.2, ...]')) AS distance
FROM agent_skills s
JOIN skill_embeddings e ON s.id = e.skill_id
ORDER BY distance
LIMIT 10;
```

---

## 八、数据库备份与恢复

### 8.1 自动备份

```java
@Service
public class DatabaseBackupService {

    private static final String DB_FILE = System.getProperty("user.home") + "/.lavis/data/lavis.db";
    private static final String BACKUP_DIR = System.getProperty("user.home") + "/.lavis/backups";

    @Scheduled(cron = "0 0 3 * * *")
    public void backupDatabase() {
        try {
            Files.createDirectories(Paths.get(BACKUP_DIR));

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String backupFile = BACKUP_DIR + "/lavis_" + timestamp + ".db";

            // SQLite 支持在线备份
            try (Connection conn = dataSource.getConnection()) {
                Statement stmt = conn.createStatement();
                stmt.execute("VACUUM INTO '" + backupFile + "'");
            }

            // 清理 30 天前的备份
            cleanOldBackups(30);

        } catch (Exception e) {
            // Log error
        }
    }
}
```

---

## 九、性能优化

### 9.1 启用 WAL 模式（Write-Ahead Logging）

```java
@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource() {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setCacheSize(10000);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + System.getProperty("user.home") + "/.lavis/data/lavis.db");

        return dataSource;
    }
}
```

### 9.2 批量插入优化

```java
@Service
public class MessageService {

    @Transactional
    public void saveMessages(List<SessionMessage> messages) {
        // SQLite 在事务中批量插入性能更好
        messageRepository.saveAll(messages);
    }
}
```

---

## 十、调试与运维

### 10.1 使用 sqlite3 命令行

```bash
# 打开数据库
sqlite3 ~/.lavis/data/lavis.db

# 查看所有表
.tables

# 查看表结构
.schema scheduled_tasks

# 查询数据
SELECT * FROM scheduled_tasks;

# 导出数据
.output backup.sql
.dump

# 退出
.quit
```

### 10.2 使用 GUI 工具

- **DB Browser for SQLite**（免费）
- **DataGrip**（JetBrains）
- **DBeaver**（免费）

---

## 十一、总结

### 11.1 SQLite vs H2 最终对比

| 场景 | H2 | SQLite | 推荐 |
|------|----|----|------|
| **纯 Java 后端** | ✅ 零配置 | ⚠️ 需要配置 | H2 |
| **Electron + Java** | ❌ 前端无法访问 | ✅ 前端直接访问 | **SQLite** |
| **AI 向量搜索** | ❌ 不支持 | ✅ sqlite-vec | **SQLite** |
| **调试友好** | ⚠️ 需要 JDBC 工具 | ✅ 通用工具 | **SQLite** |
| **行业标准** | ⚠️ Java 生态 | ✅ Local-First 标准 | **SQLite** |

### 11.2 Lavis 的最佳选择

**推荐使用 SQLite**，原因：

1. ✅ **Electron 前端可以直接访问数据库**（毫秒级加载历史记录）
2. ✅ **支持向量搜索扩展**（未来可以实现 Skills 语义检索）
3. ✅ **通用格式**（任何工具都能打开，调试友好）
4. ✅ **行业标准**（VS Code, Obsidian, LangChain 都用）
5. ✅ **轻量级**（~1MB，比 H2 更小）

### 11.3 实施步骤

1. **添加 SQLite 依赖**到 `pom.xml`
2. **配置 application.properties**
3. **创建 Flyway 迁移脚本**
4. **实现 JPA 实体类和 Repository**
5. **在 Electron 中集成 better-sqlite3**
6. **测试前端直接访问数据库**
7. **配置自动备份**

---

**SQLite 是 Lavis 的最佳选择！** 🚀
