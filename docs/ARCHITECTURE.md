# Lavis Agent Architecture v2.0

## Overview

Lavis is a headless desktop AI agent system that perceives the screen, reasons about it, and executes actions autonomously. The architecture follows a modern frontend-backend separation pattern.

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Desktop Environment                      │
│                                                              │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │              Electron Frontend (UI Layer)          │    │
│  │  ┌──────────┐  ┌──────────┐  ┌───────────┐│    │
│  │  │ Capsule  │  │ ChatPanel│  │TaskPanel   ││    │
│  │  │ (Status)  │  │(MD/Code)│  │(Progress)  ││    │
│  │  └──────────┘  └──────────┘  └───────────┘│    │
│  │         React + TypeScript + Vite                │    │
│  └──────────────────────────────────────────────────────────┘    │
│                    ↓ REST/HTTP                                 │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │         Spring Boot Backend (The Brain)            │    │
│  │  ┌──────────┐  ┌──────────┐  ┌───────────┐│    │
│  │  │Perception│  │ Cognitive │  │    Action   ││    │
│  │  │  Screen   │  │  Agent    │  │   Robot    ││    │
│  │  │Capturer  │  │Service   │  │   Driver   ││    │
│  │  └──────────┘  └──────────┘  └───────────┘│    │
│  │         Java 21 + Spring Boot 3.5.9              │    │
│  └──────────────────────────────────────────────────────────┘    │
│                    ↓ AWT Robot                              │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │                 macOS Desktop                      │    │
│  │  Mouse Click | Keyboard Input | Screen Read            │    │
│  └──────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

## Technology Stack

### Backend (Java)
| Component | Technology | Purpose |
|-----------|-------------|---------|
| **Framework** | Spring Boot 3.5.9 | REST API, dependency injection |
| **Language** | Java 21 | Core runtime |
| **Build** | Maven | Package management |
| **LLM** | LangChain4j 0.35.0 | Multi-model LLM integration |
| **Supported Models** | OpenAI, Gemini, Custom | Vision + Text capabilities |
| **Action** | AWT Robot | Mouse/keyboard control |
| **Perception** | AWT Robot + OCR | Screen capture |

### Frontend (TypeScript)
| Component | Technology | Purpose |
|-----------|-------------|---------|
| **Framework** | React 19 | Component library |
| **Runtime** | Electron 40 | Desktop app shell |
| **Build** | Vite 7 | Fast dev server & bundler |
| **Language** | TypeScript 5.9 | Type safety |
| **Styling** | CSS Modules | Scoped styles |
| **HTTP** | Axios | API client |
| **Markdown** | react-markdown + syntax-highlighter | Rich text rendering |

## Architecture Layers

### 1. Perception Layer (Backend)

**Purpose:** Convert physical screen state into digital representations

```
ScreenCapturer (Java)
├── captureScreenAsBase64() → PNG (base64)
├── getScreenSize() → Dimension
└── Screenshot compression to 768px width
```

**Key Files:**
- `src/main/java/com/lavis/perception/ScreenCapturer.java`

### 2. Cognitive Layer (Backend)

**Purpose:** Process perception, reason about goals, generate action plans

```
AgentService
├── chatWithScreenshot(message) → Fast System
├── resetConversation()
├── isAvailable()
└── getTaskOrchestrator()

TaskOrchestrator (Unified ReAct Loop)
├── executeGoal(goal) → OrchestratorResult
│   └── Perceive → Decide → Execute loop
├── getState() → State enum
├── interrupt()
├── reset()
└── generateSystemPrompt()

LocalExecutor
├── executeBatch(ExecuteNow) → BatchExecutionResult
├── executeAction(Action) → ActionResult
└── Semantic boundary detection

DecisionBundle (LLM Output)
├── thought: String
├── last_action_result: String
├── execute_now: ExecuteNow
├── is_goal_complete: boolean
└── completion_summary: String

LlmFactory
├── createOpenAI(model, key)
├── createGemini(model, key)
└── createCustom(config)
```

**Key Files:**
- `src/main/java/com/lavis/cognitive/AgentService.java`
- `src/main/java/com/lavis/cognitive/orchestrator/TaskOrchestrator.java`
- `src/main/java/com/lavis/cognitive/react/` (DecisionBundle, ExecuteNow, Action, LocalExecutor, etc.)
- `src/main/java/com/lavis/service/llm/LlmFactory.java`

### 3. Action Layer (Backend)

**Purpose:** Execute physical actions on the desktop

```
RobotDriver
├── click(x, y, button) → ClickResult
├── type(text) → TypeResult
├── press(key) → PressResult
├── moveSmooth(x, y, duration) → MoveResult
└── getLastResult() → ExecutionResult

BezierMouseUtils
├── bezierCurve() → Point[]
├── easingFunctions → EasingType[]
└── humanLikeDuration(distance) → ms
```

**Key Files:**
- `src/main/java/com/lavis/action/RobotDriver.java`
- `src/main/java/com/lavis/action/BezierMouseUtils.java`

### 4. UI Layer (Frontend)

**Purpose:** Display agent state, capture user input, show progress, and manage skills/scheduled tasks.

```
React Components
├── App (Main container, heartbeat)
├── Capsule (Floating status indicator)
├── ChatPanel (Chat interface, MD rendering)
├── TaskPanel (Progress, steps, stop button)
└── ManagementPanel (Tabs: SkillsPanel, SchedulerPanel, BrainPanel)

API Layer
├── AgentApi (Axios client for /api/agent/**)
│   ├── chat(message)
│   ├── executeTask(goal)
│   ├── stop()
│   ├── reset()
│   ├── getStatus()
│   ├── getScreenshot(thumbnail?)
│   └── getHistory()
└── ManagementApi (Axios client for /api/skills/** and /api/scheduler/**)
    ├── Skills CRUD + execute + reload
    └── Scheduler CRUD + start/stop + history

Electron Process
├── main.ts (Window, tray, shortcuts)
├── preload.ts (IPC bridge)
└── electron-builder.json (Packaging)
```

**Key Files:**
- `frontend/src/App.tsx`
- `frontend/src/components/*.tsx`
- `frontend/src/api/agentApi.ts`
- `frontend/src/api/managementApi.ts`
- `frontend/electron/main.ts`

### 5. Scheduler Layer (Backend)

**Purpose:** Run automation on schedule, persist definitions, and keep execution history.

```
ScheduledTaskService
├── createTask()/updateTask()/deleteTask()
├── startTask()/stopTask()
├── getAllTasks()/getTaskHistory()
└── executeTask() → TaskExecutor

TaskExecutor
├── execute(ScheduledTaskEntity)
├── executeAgentTask("agent:...")
└── executeShellTask("shell:..." or raw shell)
```

**Key Files:**
- `src/main/java/com/lavis/scheduler/ScheduledTaskService.java`
- `src/main/java/com/lavis/scheduler/TaskExecutor.java`
- `src/main/java/com/lavis/scheduler/TaskStore.java`
- `src/main/java/com/lavis/controller/SchedulerController.java`

### 6. Skills & Plugin Layer (Backend)

**Purpose:** Provide reusable, user-defined tools that the Agent and scheduler can call.

```
SkillService
├── loadSkillsFromDisk()  (~/.lavis/skills/**/SKILL.md)
├── getToolSpecifications()  → LangChain4j ToolSpecification list
├── executeSkill(id, params) → SkillExecutionContext
└── addToolUpdateListener()/setContextInjectionCallback()

AgentTools
├── executeSkill(name, params)
└── listSkills()
```

**Key Files:**
- `src/main/java/com/lavis/skills/*.java`
- `src/main/java/com/lavis/skills/model/*.java`
- `src/main/java/com/lavis/skills/dto/*.java`
- `src/main/java/com/lavis/controller/SkillController.java`

### 7. Cross-Cutting: Memory, Database & Context Engineering

**Purpose:** Persist sessions, compress context, and keep long-running agents stable.

```
MemoryManager
├── getCurrentSessionKey()
├── onTurnEnd(TurnContext)
└── coordinate cleanup & summaries

TurnContext
├── begin()/end()
├── recordImage(imageId)
└── track per-turn resources

SessionStore (SQLite)
├── persist messages, images, skills, tasks
└── restore on startup
```

**Key Files:**
- `src/main/java/com/lavis/memory/*.java`
- `src/main/java/com/lavis/entity/*.java`
- `src/main/java/com/lavis/repository/*.java`
- `src/main/java/com/lavis/cognitive/memory/ImageContentCleanableChatMemory.java`

## REST API Endpoints

| Method | Endpoint | Request | Response | Purpose |
|--------|----------|----------|----------|---------|
| POST | `/api/agent/chat` | `{ message: string }` | `{ success, response, duration_ms }` | Fast system Q&A |
| POST | `/api/agent/task` | `{ goal: string }` | `{ success, message, plan_summary, steps_total, execution_summary }` | Slow system task |
| GET | `/api/agent/status` | - | `{ available, model, orchestrator_state }` | System state |
| POST | `/api/agent/stop` | - | `{ status: string }` | Emergency stop |
| POST | `/api/agent/reset` | - | `{ status: string }` | Reset state |
| GET | `/api/agent/screenshot` | - | `{ success, image: base64, size }` | Screen capture |
| GET | `/api/agent/history` | - | `TaskRecord[]` | Task history |
| DELETE | `/api/agent/history` | - | - | Clear history |

## State Machine

### Agent States

```
IDLE → THINKING → EXECUTING → SUCCESS/ERROR
  ↑        ↓           ↓
  └──────────────────────┘

IDLE:     Agent ready, waiting for input
THINKING:  Analyzing screen, generating plan
EXECUTING:  Performing actions, updating progress
SUCCESS:    Task completed successfully
ERROR:      Task failed, needs intervention
```

### Orchestrator States

| State | Description | UI Indication |
|-------|-------------|----------------|
| `PLANNING` | LLM generating task steps | Purple breathing capsule |
| `EXECUTING` | Robot performing actions | Green spinning capsule, progress bar |
| `REFLECTING` | Analyzing results, planning corrections | Purple breathing capsule |
| `IDLE` | No active task | Blue static capsule |
| `ERROR` | Backend unavailable | Red pulsing capsule |

## Data Flow

### 1. Fast System (Chat) Flow

```
User Input (Chat)
    ↓
ChatPanel → AgentApi.chat()
    ↓
HTTP POST /api/agent/chat
    ↓
AgentService.chatWithScreenshot()
    ↓
ScreenCapturer.captureScreenAsBase64()
    ↓
LLM (OpenAI/Gemini) → Response
    ↓
AgentApi → ChatPanel
    ↓
ReactMarkdown Render → Display
```

### 2. Slow System (Task) Flow - Unified ReAct Loop

```
User Goal (Task)
    ↓
TaskPanel → AgentApi.executeTask()
    ↓
HTTP POST /api/agent/task
    ↓
TaskOrchestrator.executeGoal()
    ↓
Loop: Perceive → Decide → Execute
    ↓
1. ScreenCapturer.captureScreenWithCursorAsBase64()
    ↓
2. LLM.chat(ChatRequest + ResponseFormat) → DecisionBundle
    ↓
3. Check: is_goal_complete?
    ├── true → Return success
    └── false → Continue
    ↓
4. LocalExecutor.executeBatch(ExecuteNow)
    ↓
5. RobotDriver.click()/type()/pressKeys()
    ↓
Back to step 1 (next iteration)
    ↓
OrchestratorResult → TaskPanel
    ↓
Update Progress UI
```

### 3. Heartbeat Flow

```
App Mount
    ↓
agentApi.startHeartbeat(callback, 2000ms)
    ↓
SetInterval → checkStatus()
    ↓
HTTP GET /api/agent/status
    ↓
Compare to lastStatus
    ↓
If changed → callback(newStatus)
    ↓
App.setState() → UI Updates
    ↓
Capsule color, TaskPanel visibility
    ↓
On 5 consecutive errors → Slow polling (5000ms)
```

## File Structure

```
lavis/
├── src/main/java/com/lavis/
│   ├── LavisApplication.java          # Spring Boot main
│   ├── config/                       # Spring configuration
│   ├── controller/
│   │   └── AgentController.java      # REST endpoints
│   ├── cognitive/                     # AI logic
│   │   ├── AgentService.java
│   │   ├── AgentTools.java
│   │   ├── orchestrator/
│   │   │   └── TaskOrchestrator.java  # Unified ReAct loop
│   │   ├── react/                     # ReAct loop components
│   │   │   ├── DecisionBundle.java
│   │   │   ├── ExecuteNow.java
│   │   │   ├── Action.java
│   │   │   ├── ReactTaskContext.java
│   │   │   ├── LocalExecutor.java
│   │   │   └── DecisionBundleSchema.java
│   │   └── executor/
│   │       └── ToolExecutionService.java
│   ├── action/                        # Physical actions
│   │   ├── RobotDriver.java
│   │   └── BezierMouseUtils.java
│   ├── perception/                    # Screen capture
│   │   └── ScreenCapturer.java
│   └── service/                       # Services
│       ├── llm/
│       │   └── LlmFactory.java
│       └── chat/
│           └── UnifiedChatService.java
├── frontend/                         # Electron + React
│   ├── electron/
│   │   ├── main.ts                   # Electron main process
│   │   └── preload.ts                # IPC bridge
│   ├── src/
│   │   ├── api/
│   │   │   └── agentApi.ts            # API client
│   │   ├── components/
│   │   │   ├── Capsule.tsx
│   │   │   ├── ChatPanel.tsx
│   │   │   ├── TaskPanel.tsx
│   │   │   └── *.css
│   │   ├── types/
│   │   │   └── agent.ts
│   │   ├── App.tsx
│   │   └── App.css
│   ├── build/                         # Build resources
│   ├── electron-builder.json             # Packaging config
│   └── package.json
├── pom.xml                          # Maven config
└── application.properties               # Spring config
```

## Performance Optimizations

### Backend
- **Screenshot compression:** Downscale to 768px width
- **Human-like mouse movement:** Bezier curves + easing
- **Concurrent execution:** Separate threads for UI vs tasks

### Frontend
- **Adaptive heartbeat:** 2000ms → 5000ms on errors
- **Lazy status updates:** Only callback on actual changes
- **Thumbnail screenshots:** `?thumbnail=true` param for preview
- **Error-based throttling:** 5 consecutive errors triggers slower polling

## Development Workflow

### Backend Development
```bash
# Install dependencies
mvn clean install

# Run with Spring Boot
mvn spring-boot:run

# Run with custom port
mvn spring-boot:run -Dserver.port=8081

# Package JAR
mvn clean package
```

### Frontend Development
```bash
# Install dependencies
cd frontend && npm install

# Start Vite dev server
npm run dev

# Start Electron (with hot reload)
npm run electron:dev

# Build for production
npm run build

# Package Electron app
npm run electron:build
```

### Testing
```bash
# Test backend
curl http://localhost:18765/api/agent/status

# Test chat
curl -X POST http://localhost:18765/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "What do you see?"}'

# Test task
curl -X POST http://localhost:18765/api/agent/task \
  -H "Content-Type: application/json" \
  -d '{"goal": "Open calculator"}'
```

## Configuration

### Backend (application.properties)
```properties
# Server
server.port=18765

# LLM Models
app.llm.models.openai.api-key=${OPENAI_API_KEY}
app.llm.models.gemini.api-key=${GEMINI_API_KEY}
app.llm.models.custom.endpoint=${CUSTOM_ENDPOINT}

# Agent Settings
app.agent.screenshot.width=768
app.agent.mouse.human-like=true
app.agent.retry.max-attempts=3
```

### Frontend (Environment Variables)
```bash
# Backend URL (defaults to localhost:18765)
BACKEND_URL=http://localhost:18765

# Electron settings
ELECTRON_IS_DEV=true
```

## Deployment

### Package for Distribution
```bash
# Build backend
mvn clean package

# Build frontend and package Electron
cd frontend && npm run electron:build

# Output: frontend/dist-electron/
#   - Lavis-0.1.0-mac.dmg (macOS)
#   - Lavis-0.1.0-mac-arm64.dmg (macOS ARM)
#   - Lavis Setup 0.1.0.exe (Windows)
#   - Lavis-0.1.0.AppImage (Linux)
```

### One-Click Distribution (Future)
1. Embed Spring Boot JAR in Electron app
2. Auto-start backend on app launch
3. Dynamic port assignment to avoid conflicts

## Security Considerations

### Backend
- **API Key storage:** Environment variables only
- **Input validation:** All user inputs sanitized
- **Privilege isolation:** Agent requires explicit user authorization

### Frontend
- **Context isolation:** Node integration disabled
- **Content Security:** CSP headers (future)
- **IPC validation:** Only whitelisted channels

## Voice Interaction

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Voice Pipeline                            │
├─────────────────────────────────────────────────────────────┤
│  Wake Word Detection (Vosk)                                  │
│  └── useVoskWakeWord.ts → Offline keyword detection          │
├─────────────────────────────────────────────────────────────┤
│  Voice Recording (Browser API)                               │
│  └── useVoiceRecorder.ts → MediaRecorder API                 │
├─────────────────────────────────────────────────────────────┤
│  Speech-to-Text (Backend)                                    │
│  └── Whisper API → Transcription                             │
├─────────────────────────────────────────────────────────────┤
│  Text-to-Speech (Backend)                                    │
│  └── POST /api/agent/tts → Audio Base64                      │
│  └── audioService.ts → Playback                              │
└─────────────────────────────────────────────────────────────┘
```

### TTS Flow

1. Backend generates announcement text (async, non-blocking)
2. WebSocket sends `voice_announcement` event with text only
3. Frontend calls `POST /api/agent/tts` to convert text to audio
4. Frontend plays audio via singleton Audio instance
5. Audio blob URL revoked after playback

## Memory Safety

### Frontend Memory Management

| Strategy | Implementation | Location |
|----------|----------------|----------|
| URL Revoke | `URL.revokeObjectURL()` after audio playback | `audioService.ts` |
| Singleton Audio | Single `Audio()` instance reused | `audioService.ts` |
| Virtual Scrolling | `react-window` for chat history | `ChatPanel.tsx` |
| Conditional Render | Hide ChatPanel in mini mode | `App.tsx` |

### Backend Memory Management

| Strategy | Implementation | Location |
|----------|----------------|----------|
| Image Content Cleanup | Custom `ImageContentCleanableChatMemory` | `memory/` |
| Screenshot Compression | Downscale to 768px width | `ScreenCapturer.java` |
| Context Cleanup | Clear GlobalContext after task | `TaskOrchestrator.java` |
| Stream Closure | try-with-resources for BufferedImage | `ScreenCapturer.java` |

### ImageContentCleanableChatMemory

Custom ChatMemory implementation that automatically cleans up old ImageContent:

- Keeps full content for last N messages (default: 4)
- Replaces older ImageContent with placeholder text
- Thread-safe with read-write locks
- Saves ~90% heap memory in long-running sessions

## Future Enhancements

### Planned Features
- [ ] Multi-screen support
- [ ] Task templates / shortcuts
- [ ] History search
- [ ] Export/import task plans
- [ ] Click highlight overlay

### Intelligence Improvements
- [ ] Tool use: File operations, browser control
- [ ] Self-correction threshold tuning
- [ ] Plan optimization caching
- [ ] Multi-agent collaboration
- [ ] Reinforcement learning from user feedback

## Troubleshooting

### Common Issues

**Backend not starting**
- Check Java version: `java -version` (requires 21+)
- Check port availability: `lsof -i :18765`
- Verify API keys in `application.properties`

**Frontend not connecting**
- Verify backend is running: `curl http://localhost:18765/api/agent/status`
- Check CORS settings in Spring Boot
- Try dynamic port detection: `agentApi.detectBackendPort()`

**Electron window blank**
- Check console for errors: DevTools (Cmd+Option+I)
- Verify Vite dev server is running: `npm run dev`
- Check preload script is compiled to JS

**Mouse/keyboard not working**
- Check macOS permissions (Accessibility)
- Grant assistive device access in System Settings
- Verify AWT Robot is available

## Development History & Evolution

> This section documents the development history and implementation status of the Lavis project, providing context for contributors to understand "why things are the way they are."

**Last Updated**: 2026-02-04

---

### Scope

This section focuses on historical records and design decisions, not as the primary entry point for users/developers.

- For user and developer entry points, see: `docs/INDEX.md`
- For current architecture details, see the sections above in this document.

---

### ✅ Completed Modules (Summary)

#### 1) Final Step Voice Announcement

- **Goal**: Provide brief voice feedback when task plan is completed; only final step triggers, avoiding interruption
- **Result**: Implemented (backend pushes text event, frontend calls TTS on-demand and plays)

#### 2) Frontend Window States & Interaction

- **Goal**: Audio wake-up, visual restraint; Mini/Expanded state switching
- **Result**: Implemented (Capsule + state machine + Electron size switching)

#### 3) End-to-End Memory Safety

- **Goal**: Support long-running sessions, avoid OOM/render freezes
- **Result**: Implemented (image cleanup, context compression, session persistence, frontend virtual scrolling, etc.)

#### 4) Context Engineering

- **Goal**: Enable high-performance execution of long-path, high-visual-load tasks through context isolation, intelligent compression, and perceptual deduplication
- **Result**: Implemented (2026-02-01)
  - **Turn Infrastructure**: Introduced Turn (interaction round) concept, managing context by task cycle
  - **Visual Compression Engine**: First and last anchor points retained, middle images replaced with placeholders, historical visual tokens reduced by 95%+
  - **Perceptual Deduplication**: Uses dHash algorithm to detect screen changes, reducing redundant screenshot generation
  - **Web Search Sub-Agent**: Depth-first search, up to 5 iterations, confidence-driven termination

---

### Detailed Records (Preserved Original History)

The following content comes from early development records, preserved in original form with minor path/terminology corrections.

#### ✅ 1. Backend: Final Step Voice Announcement

When all task plan steps are completed (final step), push brief voice feedback to frontend, notifying users that the task is complete, while ensuring it doesn't block automation task execution speed.

#### ✅ 2. Frontend: Electron Window States & Interaction

Implement "audio wake-up, visual restraint". When voice wake-up occurs, don't directly occupy the screen, only provide feedback through capsule component animations, reducing user interference.

#### ✅ 3. End-to-End Memory Safety Strategy

Covers browser side, Electron process communication, and Java backend heap memory: timely audio resource release, DOM virtualization, screenshot and context eviction, etc.

#### ✅ 4. Context Engineering

**Development Time**: 2026-02-01  
**Branch**: `feature/context-engineering`

**Problems Solved**:
- Model "blindness": Scheduled blind deletion of images causing task interruption
- Token inflation: Full retention of historical images causing OOM
- Lack of temporal awareness: Cleanup by message count, unable to identify key frames
- Redundant screenshots: Still generating new images when screen hasn't changed

**Implemented Features**:

1. **Turn Infrastructure**
   - Database migration: Added `turn_id`, `image_id`, `is_compressed`, `turn_position` fields
   - `TurnContext` class: Manages Turn lifecycle (begin/current/end)
   - `AgentService` integration: Request entry generates turnId, triggers compression on completion

2. **Visual Compression Engine**
   - `ColdStorage` service: Archives compressed images to filesystem (~/.lavis/cold-storage/)
   - `VisualCompactor` service: First-last retention algorithm + exception frame retention
   - Compression strategy: First and last images retain full Base64, middle images replaced with placeholders

3. **Perceptual Deduplication**
   - dHash (difference hash) algorithm: Pure Java implementation, no external dependencies
   - `captureWithDedup()` method: Supports screen change detection, reuses similar images
   - Configuration: `lavis.perception.dedup.enabled/threshold`

4. **Web Search Sub-Agent**
   - `WebSearchService`: Supports DuckDuckGo (free) and Tavily (requires API Key)
   - `SearchAgent`: Up to 5 iterations, confidence-driven termination (threshold 0.8)
   - `internetSearch` and `quickSearch` tools: Deep search and quick single search

**Expected Effects**:
- Token efficiency: Historical visual overhead reduced by 95%+
- Logical robustness: Completely solves "images being mistakenly deleted causing task interruption"
- Information depth: Sub-agent 5 iterations ensure network information reliability

---

## License

Copyright © 2025 Lavis. All rights reserved.
