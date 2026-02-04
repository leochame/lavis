# Lavis Development Documentation Index

> Complete documentation index for Lavis project.

**Last Updated**: 2026-02-04

---

## Documentation Navigation

### Quick Start

| Document | Description | Audience |
|----------|-------------|----------|
| [User Guide](User-Guide-en.md) | Installation, running, basic usage | End users |
| [Build & Packaging Guide (EN)](Build-and-Packaging-en.md) | Development mode, one-click packaging (JAR method), GraalVM Native Image, debugging | Developers & Packagers |
| [Build & Packaging Guide (ZH)](Build-and-Packaging-zh.md) | 开发模式、一键打包（JAR 方式）、GraalVM Native Image、调试 | 开发者 & 打包者 |

### Architecture & Design

| Document | Description |
|----------|-------------|
| [System Architecture](ARCHITECTURE.md) | System architecture, data flow, and development history |
| [Database Implementation](Database-Implementation.md) | SQLite database details |
| [Unified ReAct Loop Design](Unified-ReAct-Loop-Design.md) | One-layer architecture design (Completed) |

---

## Implemented Features

### Phase 5: Unified ReAct Decision Loop

**Status**: ✅ Completed (Core Implementation + Testing + JSON Schema)

**Goal**: Reduce LLM calls by 50-70% by merging Planner and Executor into a unified decision loop.

**Completed**:
- ✅ Core data structures (`DecisionBundle`, `ExecuteNow`, `Action`, `ReactTaskContext`)
- ✅ `LocalExecutor` service for batch action execution
- ✅ `executeGoal()` method in TaskOrchestrator (unified loop)
- ✅ `DecisionBundleSchema.createResponseFormat()` for API-level JSON enforcement
- ✅ Unit tests (181 tests) and integration tests (10 tests)
- ✅ Removed deprecated `PlannerService`, `MicroExecutorService`, `TaskPlan`, `PlanStep`

**New Files**:
```
src/main/java/com/lavis/cognitive/react/
├── DecisionBundle.java       # LLM output structure
├── ExecuteNow.java           # Actions for current round
├── Action.java               # Single action definition
├── ReactTaskContext.java     # Simplified task context
├── LocalExecutor.java        # Batch executor (no LLM)
└── DecisionBundleSchema.java # JSON parsing/validation + ResponseFormat
```

**Configuration**:
```properties
lavis.orchestrator.max-iterations=50
lavis.orchestrator.max-consecutive-failures=5
lavis.executor.action-delay-ms=100
lavis.executor.boundary-wait-ms=500
```

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
├── cognitive/           # Cognitive layer
│   ├── orchestrator/    # TaskOrchestrator (unified ReAct loop)
│   ├── react/           # Unified ReAct loop components
│   │   ├── DecisionBundle.java
│   │   ├── ExecuteNow.java
│   │   ├── Action.java
│   │   ├── ReactTaskContext.java
│   │   ├── LocalExecutor.java
│   │   └── DecisionBundleSchema.java
│   ├── executor/        # ToolExecutionService
│   └── AgentService.java, AgentTools.java
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
curl http://localhost:18765/api/scheduler/tasks
curl http://localhost:18765/api/scheduler/status
```

### Skills Not Loading
```bash
curl http://localhost:18765/api/skills
curl -X POST http://localhost:18765/api/skills/reload
```

---

## License

MIT License
