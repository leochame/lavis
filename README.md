## Lavis - macOS System-level Multimodal AI Agent

Lavis is a macOS desktop AI agent that **perceives your screen**, **controls mouse & keyboard**, and supports **voice interaction**.

> **中文版本 / Chinese Version**: See [README-zh.md](README-zh.md)

---

## Key Features

- **Visual Perception**: Real-time screenshot analysis with Retina support
- **Autonomous Actions**: Mouse, keyboard, and system shortcut control
- **Reflection Loop**: Closed loop for self-correction
- **System Integration**: AppleScript, app control, shell commands
- **Voice Interaction**: Wake word, ASR, TTS
- **Transparent UI**: HUD-style UI showing internal reasoning
- **Memory Safety**: Automatic cleanup for long-running sessions
- **Context Engineering**: Intelligent compression and perceptual deduplication, reducing historical visual tokens by 95%+
- **Web Search**: Deep search agent with up to 5 iterations

---

## Tech Stack

| Layer | Technology | Version |
|-------|------------|---------|
| **Backend** | Spring Boot | 3.5.9 |
| **Language** | Java | 21 |
| **AI Framework** | LangChain4j | 0.35.0 |
| **Frontend** | React | 19.x |
| **Desktop** | Electron | 40.x |
| **Build** | Vite | 7.x |
| **State** | Zustand | 5.x |

---

## Quick Start

### Prerequisites

- macOS (Intel / Apple Silicon)
- JDK 21+
- Node.js 18+
- At least one LLM API key

### 1. Configure Backend API Keys

**Option 1 (recommended): Environment variables**

```bash
export FAST_MODEL_API_KEY=your_fast_model_api_key  # Fast LLM (e.g. Gemini Flash)
export WHISPER_API_KEY=your_whisper_api_key        # ASR
export TTS_API_KEY=your_tts_api_key                # TTS
```

**Option 2: Configuration file**

```bash
cp src/main/resources/application.properties.example src/main/resources/application.properties
# Edit and fill your API keys
```

### 2. Start Backend

```bash
./mvnw spring-boot:run
```

> For AOT compilation and stronger reverse-engineering resistance with GraalVM Native Image, see `docs/Build-and-Packaging-en.md` (English) or `docs/Build-and-Packaging-zh.md` (中文).

### 3. Start Frontend

```bash
cd frontend
npm install
npm run electron:dev
```

### 4. Grant macOS Permissions

1. **Screen Recording**: System Settings → Privacy & Security → Screen Recording → enable Lavis
2. **Accessibility**: System Settings → Privacy & Security → Accessibility → enable Lavis

---

## Packaging & Distribution

### One-Click Build

Lavis supports **fully automated one-click packaging**, including embedded Java runtime.

**Quick Build:**

```bash
cd frontend
npm install  # Install dependencies for first run
npm run package
```

This command will automatically:
1. Check prerequisites (Java, Maven, Node.js)
2. Build Java backend JAR
3. Build frontend code
4. Compile Electron main process
5. Package app with electron-builder

**Features:**
- ✅ **Embedded Java** - JRE 21 embedded, no Java installation required
- ✅ **Auto-start** - Backend service starts automatically on launch
- ✅ **Cross-platform Ready** - Clean architecture, easy to extend to other platforms

**Output:**
- macOS: `frontend/dist-electron/Lavis-1.0.0-arm64.dmg` (~250MB)

**Detailed Docs:**
- [Complete Build & Packaging Guide](docs/Build-and-Packaging-en.md) - Includes building, packaging, debugging, troubleshooting, and GraalVM Native Image

---

## Project Structure

```text
lavis/
├── src/main/java/com/lavis/        # Java backend
│   ├── cognitive/                  # Cognitive logic
│   ├── perception/                 # Perception (screen)
│   ├── action/                     # Actions
│   ├── controller/                 # REST API
│   ├── websocket/                  # WebSocket
│   ├── service/                    # Services (TTS/ASR)
│   ├── scheduler/                  # Scheduler (Cron tasks + history)
│   ├── skills/                     # Skills plugin system (SKILL.md, dynamic load)
│   ├── memory/                     # Memory & context engineering (sessions, images)
│   ├── entity/                     # JPA entities (tasks, logs, sessions, skills, etc.)
│   └── repository/                 # JPA repositories (SQLite)
├── frontend/                       # Electron + React frontend
│   ├── electron/                  # Electron main process (tray, windows, shortcuts)
│   │   ├── main.ts                 # Main process entry
│   │   ├── backend-manager.ts     # Backend process manager
│   │   └── preload.ts             # Preload script
│   ├── src/                        # React UI & hooks (including Skills/Scheduler management)
│   │   ├── components/            # UI components
│   │   │   ├── Capsule.tsx        # Floating capsule UI
│   │   │   ├── ChatPanel.tsx      # Chat interface (virtual scroll)
│   │   │   ├── TaskPanel.tsx      # Task progress panel
│   │   │   ├── SkillsPanel.tsx    # Skills management
│   │   │   ├── SchedulerPanel.tsx # Scheduler management
│   │   │   └── VoicePanel.tsx     # Voice interaction panel
│   │   ├── hooks/                 # React Hooks
│   │   │   ├── useWebSocket.ts    # WebSocket connection
│   │   │   ├── useVoskWakeWord.ts # Vosk wake word detection
│   │   │   └── useVoiceRecorder.ts # Voice recording
│   │   └── store/                 # Zustand state management
│   └── scripts/                    # Packaging and dev tools
│       ├── package.js             # One-click packaging script
│       └── test-packaged-app.sh   # Test packaged app
├── docs/                           # Documentation
│   ├── User-Guide-en.md           # User guide
│   ├── Build-and-Packaging-en.md  # Build & packaging guide (English)
│   ├── Build-and-Packaging-zh.md  # Build & packaging guide (Chinese)
│   └── ARCHITECTURE.md            # Architecture documentation
```

---

## REST API Overview

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/agent/status` | Get system status |
| POST | `/api/agent/chat` | Chat with screenshot context |
| POST | `/api/agent/task` | Execute automation task |
| POST | `/api/agent/stop` | Emergency stop |
| POST | `/api/agent/reset` | Reset state |
| GET | `/api/agent/screenshot` | Get screenshot |
| POST | `/api/agent/tts` | Text-to-speech |
| GET | `/api/agent/history` | Get task history |

**Examples**

```bash
# Check status
curl http://localhost:18765/api/agent/status

# Send a chat message
curl -X POST http://localhost:18765/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "What is displayed on the screen?"}'

# Execute a task
curl -X POST http://localhost:18765/api/agent/task \
  -H "Content-Type: application/json" \
  -d '{"goal": "Open Safari and search for weather"}'
```

---

## Frontend Development

### Tech Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Electron | 40.x | Desktop app shell |
| React | 19.x | UI framework |
| TypeScript | 5.9.x | Type safety |
| Vite | 7.x | Build tool |
| Zustand | 5.x | State management |
| react-window | - | Virtual scrolling |

### Window States

| State | Description | Window Size | Trigger |
|-------|-------------|-------------|---------|
| **Idle** | Dormant/standby | Hidden or tray only | Default |
| **Listening** | Voice wake/listening | Mini (200x60px) | Wake word detected |
| **Expanded** | Full interaction | Full (800x600px) | Double-click capsule |

### Global Shortcuts

| Shortcut | Action |
|----------|--------|
| `Alt+Space` | Toggle capsule/chat window |
| `Cmd+K` | Quick chat |
| `Escape` | Hide window |

### Development Commands

```bash
# Install dependencies
cd frontend
npm install

# Start Vite dev server only
npm run dev

# Start Electron with hot reload (recommended)
npm run electron:dev

# Build for production
npm run build
```

### Voice Interaction

- **Wake Word Detection**: Uses Vosk for offline wake word detection
- **Voice Recording**: Browser MediaRecorder API
- **TTS Playback**: Backend TTS proxy with audio streaming

---

## Documentation

- `docs/User-Guide-en.md`  
  User guide: Installation, running, permissions, basic usage.
- `docs/Build-and-Packaging-en.md` / `docs/Build-and-Packaging-zh.md`  
  Complete build & packaging guide: Development mode, one-click packaging (JAR method), GraalVM Native Image (advanced option), debugging, troubleshooting.
- `docs/ARCHITECTURE.md`  
  System architecture, data flow details, and development history.

---

## Security & Privacy

- All automation runs locally; screenshots are transient and used only for visual reasoning.
- API keys live in local env/config only and are never exposed to the frontend or third parties.
- GraalVM Native Image packaging removes `.class` files, making reverse engineering significantly harder.

---

## License

MIT License
