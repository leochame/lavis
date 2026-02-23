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

### 1. Configure API Key

Lavis uses Google Gemini API for all AI services (chat, speech-to-text, and text-to-speech). You only need **one API key** to get started.

**Option 1 (recommended): Environment variable**

```bash
export GEMINI_API_KEY=your_gemini_api_key_here
```

**Option 2: Frontend Settings Panel (Easiest)**

1. Launch the app after starting frontend
2. Open Settings panel (via menu bar icon or `Cmd + K`)
3. Enter your Gemini API key in the settings form
4. Click "Save"

**Option 3: Configuration file**

```bash
cp src/main/resources/application.properties.example src/main/resources/application.properties
# Edit application.properties and set GEMINI_API_KEY or fill API keys directly
```

#### Getting Your Gemini API Key

1. Visit [Google AI Studio](https://aistudio.google.com/app/apikey)
2. Sign in with your Google account
3. Click "Create API Key"
4. Copy the generated key

> **Security Note**: API keys are stored locally only and never exposed to third parties.

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

Lavis supports **fully automated one-click packaging**, including embedded Java runtime. Anyone can easily package the app without complex configuration.

#### Prerequisites

- macOS (Intel / Apple Silicon)
- JDK 21+ (for compiling backend)
- Node.js 18+ (for building frontend)
- Maven (project includes `mvnw`, no separate installation needed)

#### Quick Build

```bash
cd frontend
npm install  # Install dependencies for first run
npm run package
```

This command will automatically:
1. ✅ Check prerequisites (Java, Maven, Node.js)
2. ✅ Build Java backend JAR
3. ✅ Auto-download JRE for current architecture (arm64 or x64)
4. ✅ Build frontend code
5. ✅ Compile Electron main process
6. ✅ Package app with electron-builder
7. ✅ Generate DMG installer

#### Features

- ✅ **Embedded Java** - JRE 21 embedded, no Java installation required for end users
- ✅ **Auto-start** - Backend service starts automatically on launch
- ✅ **Cross-architecture** - Automatically detects and packages current architecture (arm64/x64)
- ✅ **User-friendly** - DMG includes auto-install script and instructions

#### Output

After packaging, you'll find in `frontend/dist-electron/`:
- `Lavis-1.0.0-arm64.dmg` (Apple Silicon) or `Lavis-1.0.0-x64.dmg` (Intel)
- `Lavis-1.0.0-arm64.zip` (alternative format)

#### Installation Instructions

The DMG package includes:
1. **Lavis.app** - Main application
2. **自动安装.command** - One-click install script (recommended)
3. **安装说明.rtf** - Detailed installation instructions

**First-time Installation:**

1. Double-click the DMG file to open
2. Double-click `自动安装.command` script (recommended)
   - If security prompt appears, click "Open"
   - Script will automatically handle permissions and install the app
3. Or manually drag `Lavis.app` to Applications folder
4. On first launch, if you see "app is damaged" message:
   - **Method 1**: Right-click app → Select "Open" → Click "Open" in dialog
   - **Method 2**: Run in terminal: `xattr -dr com.apple.quarantine /Applications/Lavis.app`
   - This is macOS security mechanism, only needed once

#### Common Issues

**Q: Build fails, can't find Java?**  
A: Make sure JDK 21+ is installed, verify with `java -version` in terminal.

**Q: Build fails, can't find Maven?**  
A: Project includes `mvnw` (Maven Wrapper), no separate Maven installation needed.

**Q: Build takes a long time?**  
A: First build downloads JRE (~150MB), subsequent builds are faster.

**Q: How to package for other architectures?**  
A: Run the build command on a machine with that architecture, electron-builder auto-detects it.

**Detailed Documentation:**
- Complete build guide, debugging, troubleshooting: `docs/Build-and-Packaging-en.md`
- GraalVM Native Image advanced option: `docs/Build-and-Packaging-en.md`

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
