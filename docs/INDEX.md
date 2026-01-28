# Lavis Development Documentation Index

> Complete documentation index for Lavis project.

**Last Updated**: 2026-01-28

---

## Documentation Navigation

### Quick Start

| Document | Description | Audience |
|----------|-------------|----------|
| [User Guide (EN)](User-Guide-en.md) | Installation, running, basic usage | End users |
| [User Guide (ZH)](User-Guide-zh.md) | Installation, running, basic usage | End users |
| [Developer Guide (EN)](Developer-Build-and-Packaging-en.md) | Build, packaging, GraalVM Native Image | Developers |
| [Developer Guide (ZH)](Developer-Build-and-Packaging-zh.md) | Build, packaging, GraalVM Native Image | Developers |

### Architecture & Design

| Document | Description |
|----------|-------------|
| [System Architecture](ARCHITECTURE.md) | System architecture and data flow |
| [Database Implementation](Database-Implementation.md) | SQLite database details |

---

## Implemented Features

### Phase 1: Database Integration

**Status**: Completed

- SQLite database with Spring Data JPA
- 6 core tables: scheduled_tasks, task_run_logs, user_sessions, session_messages, user_preferences, agent_skills
- Flyway migrations for schema management
- Vector search support (embedding field in agent_skills)

### Phase 2: Memory Management System

**Status**: Completed

**Features**:
- Session persistence to SQLite
- Auto cleanup of old screenshots (keeps last 10)
- Smart context compression (AI-driven summarization when >100K tokens)
- Scheduled cleanup tasks (hourly)
- JVM memory monitoring

**Components**:
- `SessionStore.java` - Session persistence
- `ImageCleanupService.java` - Screenshot cleanup
- `ContextCompactor.java` - Context compression
- `MemoryManager.java` - Memory coordination

**Configuration**:
```properties
memory.keep.images=10
memory.token.threshold=100000
memory.keep.recent.messages=10
memory.session.retention.days=30
memory.cleanup.interval.ms=3600000
```

### Phase 3: Scheduler System

**Status**: Completed

**Features**:
- Dynamic Cron scheduling with Spring TaskScheduler
- Task persistence (auto-restore on restart)
- Execution history logging
- Dual task types: Agent tasks and Shell commands
- Complete REST API

**Command Format**:
```
agent:open Safari and visit google.com    # Agent task
shell:ls -la /tmp                         # Shell command
```

**Cron Format** (6 fields):
```
second minute hour day month weekday
0 0 * * * *     # Every hour
0 */15 * * * *  # Every 15 minutes
0 0 9 * * 1-5   # Weekdays at 9 AM
```

**REST API**:

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/scheduler/tasks` | Create task |
| GET | `/api/scheduler/tasks` | List all tasks |
| GET | `/api/scheduler/tasks/{id}` | Get task |
| PUT | `/api/scheduler/tasks/{id}` | Update task |
| POST | `/api/scheduler/tasks/{id}/start` | Start task |
| POST | `/api/scheduler/tasks/{id}/stop` | Stop task |
| DELETE | `/api/scheduler/tasks/{id}` | Delete task |
| GET | `/api/scheduler/tasks/{id}/history` | Get run history |

### Phase 4: Skills Plugin System

**Status**: Completed

**Features**:
- SKILL.md file format with YAML frontmatter
- Dynamic loading from `~/.lavis/skills/`
- Hot reload support (file watcher)
- Parameter substitution (`{{param}}` syntax)
- Agent Tools integration (`executeSkill`, `listSkills`)
- Complete REST API

**Skill File Format** (`~/.lavis/skills/my-skill/SKILL.md`):
```markdown
---
name: screenshot
description: Capture screen and save to desktop
category: utility
version: 1.0.0
author: lavis
command: shell:screencapture -x ~/Desktop/screenshot.png
---

# Screenshot Skill

Captures the current screen.
```

**Command Prefixes**:
- `shell:` - Execute shell command
- `agent:` - Execute AI agent task
- (no prefix) - Default to shell

**REST API**:

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/skills` | List all skills |
| GET | `/api/skills/{id}` | Get skill details |
| POST | `/api/skills` | Create skill |
| PUT | `/api/skills/{id}` | Update skill |
| DELETE | `/api/skills/{id}` | Delete skill |
| POST | `/api/skills/{id}/execute` | Execute skill |
| POST | `/api/skills/reload` | Reload from filesystem |
| GET | `/api/skills/categories` | List categories |

**Configuration**:
```properties
skills.directory=${user.home}/.lavis/skills
skills.hot-reload.enabled=true
skills.hot-reload.interval.ms=5000
```

**Agent Tools**:
```java
@Tool("Execute a skill by name")
String executeSkill(String skillName, String params)

@Tool("List all available skills")
String listSkills()
```

---

## Frontend Management Panel

The Electron frontend includes a management panel (MGMT button) with:

- **Skills Tab**: Create, edit, delete, enable/disable, execute skills
- **Scheduler Tab**: Create, edit, delete, pause/resume, run tasks, view logs

---

## Project Structure

### Backend (Java + Spring Boot)

```
src/main/java/com/lavis/
├── cognitive/           # Cognitive layer (AgentService, AgentTools)
├── perception/          # Perception layer (screenshots)
├── action/              # Action layer (mouse, keyboard)
├── controller/          # REST API controllers
├── websocket/           # WebSocket communication
├── memory/              # Memory management
├── scheduler/           # Scheduler system
├── skills/              # Skills plugin system
│   ├── SkillStore.java
│   ├── SkillLoader.java
│   ├── SkillExecutor.java
│   ├── SkillService.java
│   ├── dto/
│   └── model/
├── entity/              # JPA entities
├── repository/          # JPA repositories
└── config/              # Configuration classes
```

### Frontend (Electron + React)

```
frontend/src/
├── api/
│   ├── agentApi.ts
│   └── managementApi.ts    # Skills & Scheduler API
├── components/
│   ├── ChatPanel.tsx
│   ├── BrainPanel.tsx
│   ├── ManagementPanel.tsx # Tab container
│   ├── SkillsPanel.tsx     # Skills management
│   └── SchedulerPanel.tsx  # Scheduler management
└── hooks/
```

### Data Directory

```
~/.lavis/
├── data/lavis.db        # SQLite database
├── skills/              # User skills
│   ├── screenshot/SKILL.md
│   └── open-browser/SKILL.md
└── logs/
```

---

## Troubleshooting

### Database Connection Failed
```bash
ls -la ~/.lavis/data/lavis.db
sqlite3 ~/.lavis/data/lavis.db ".tables"
```

### Scheduled Task Not Running
```bash
curl http://localhost:8080/api/scheduler/tasks
curl http://localhost:8080/api/scheduler/status
```

### Skills Not Loading
```bash
curl http://localhost:8080/api/skills
curl -X POST http://localhost:8080/api/skills/reload
```

---

## Archived Documents

Historical documents are in `archive/` directory:
- Phase implementation summaries
- Development history
- Technical comparisons

---

## License

MIT License
