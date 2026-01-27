-- Lavis Database Initial Schema
-- SQLite Database Schema for Lavis Agent System

-- ====================================
-- 1. Scheduled Tasks Table
-- ====================================
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

-- ====================================
-- 2. Task Run Logs Table
-- ====================================
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

-- ====================================
-- 3. User Sessions Table
-- ====================================
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

-- ====================================
-- 4. Session Messages Table
-- ====================================
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

-- ====================================
-- 5. User Preferences Table
-- ====================================
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

-- ====================================
-- 6. Agent Skills Table
-- ====================================
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
