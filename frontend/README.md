# Lavis Desktop - Electron Frontend

Modern desktop UI for Lavis AI Agent using Electron + React + TypeScript + Vite.

## Tech Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Electron | 40.x | Desktop app shell |
| React | 19.x | UI framework |
| TypeScript | 5.9.x | Type safety |
| Vite | 7.x | Build tool |
| Zustand | 5.x | State management |
| react-window | - | Virtual scrolling |

## Project Structure

```
frontend/
├── electron/
│   ├── main.ts              # Electron main process
│   └── preload.ts           # Preload script for IPC
├── src/
│   ├── api/
│   │   └── agentApi.ts      # Axios client with heartbeat
│   ├── components/
│   │   ├── Capsule.tsx      # Floating capsule UI
│   │   ├── ChatPanel.tsx    # Chat interface with virtual scroll
│   │   ├── TaskPanel.tsx    # Task progress panel
│   │   └── VoicePanel.tsx   # Voice interaction panel
│   ├── hooks/
│   │   ├── useWebSocket.ts  # WebSocket connection
│   │   ├── useGlobalVoice.ts # Global voice state
│   │   ├── useVoiceRecorder.ts # Voice recording
│   │   ├── useWakeWord.ts   # Wake word detection
│   │   └── useVoskWakeWord.ts # Vosk-based wake word
│   ├── store/
│   │   └── uiStore.ts       # Zustand UI state
│   ├── services/
│   │   └── audioService.ts  # Audio playback service
│   ├── platforms/           # Platform-specific code
│   ├── types/
│   │   └── agent.ts         # TypeScript types
│   ├── App.tsx
│   └── App.css
├── public/
│   └── models/              # Vosk wake word models
└── package.json
```

## Features

### Window States

| State | Description | Window Size | Trigger |
|-------|-------------|-------------|---------|
| **Idle** | Dormant/standby | Hidden or tray only | Default |
| **Listening** | Voice wake/listening | Mini (200x60px) | Wake word detected |
| **Expanded** | Full interaction | Full (800x600px) | Double-click capsule |

### Capsule States

| State | Color | Animation | Meaning |
|-------|-------|-----------|---------|
| Idle | Blue gradient | Static | Agent ready |
| Thinking | Purple gradient | Breathing | Processing visual info |
| Executing | Green gradient | Spinning | Running task |
| Speaking | Blue gradient | Sound waves | TTS playing |
| Error | Red gradient | Pulsing | Backend unavailable |

### Global Shortcuts

| Shortcut | Action |
|----------|--------|
| `Alt+Space` | Toggle capsule/chat window |
| `Cmd+K` | Quick chat |
| `Escape` | Hide window |

### Voice Interaction

- **Wake Word Detection**: Uses Vosk for offline wake word detection
- **Voice Recording**: Browser MediaRecorder API
- **TTS Playback**: Backend TTS proxy with audio streaming

## Development

```bash
# Install dependencies
npm install

# Start Vite dev server only
npm run dev

# Start Electron with hot reload (recommended)
npm run electron:dev

# Build for production
npm run build

# Build Electron app
npm run electron:build
```

## Backend Integration

The frontend connects to Spring Boot backend on `http://localhost:8080`.

Make sure backend is running before starting Electron app:

```bash
# In the project root directory
./mvnw spring-boot:run
```

## API Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/agent/chat` | POST | Fast system - visual Q&A |
| `/api/agent/task` | POST | Slow system - task orchestration |
| `/api/agent/status` | GET | System state |
| `/api/agent/stop` | POST | Emergency stop |
| `/api/agent/reset` | POST | Reset memory |
| `/api/agent/screenshot` | GET | Screen capture |
| `/api/agent/tts` | POST | Text-to-speech |
| `/api/agent/history` | GET/DELETE | Task history |

## WebSocket Events

| Event Type | Direction | Description |
|------------|-----------|-------------|
| `workflow_update` | Server → Client | Task progress update |
| `voice_announcement` | Server → Client | TTS announcement text |
| `step_complete` | Server → Client | Step completion notification |

## Memory Safety

The frontend implements several memory safety measures:

1. **URL Revoke**: Audio blob URLs are revoked after playback
2. **Singleton Audio**: Single Audio instance reused globally
3. **Virtual Scrolling**: react-window for long chat histories
4. **Conditional Rendering**: ChatPanel hidden in mini mode

## IPC Channels

| Channel | Direction | Purpose |
|---------|-----------|---------|
| `resize-window-mini` | Renderer → Main | Switch to mini mode |
| `resize-window-full` | Renderer → Main | Switch to full mode |
| `show-window` | Main → Renderer | Show window |
| `hide-window` | Main → Renderer | Hide window |

## Troubleshooting

### Electron window blank
- Check console for errors: DevTools (Cmd+Option+I)
- Verify Vite dev server is running: `npm run dev`
- Check preload script is compiled to JS

### Backend connection failed
- Verify backend is running: `curl http://localhost:8080/api/agent/status`
- Check CORS settings in Spring Boot
- Try restarting both frontend and backend

### Wake word not working
- Check microphone permissions in System Settings
- Verify Vosk model is downloaded in `public/models/`
- Check browser console for audio errors
