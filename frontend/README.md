# Lavis Desktop - Electron Frontend

Modern desktop UI for Lavis AI Agent using Electron + React + TypeScript + Vite.

## Project Structure

```
frontend/
├── electron/
│   ├── main.ts          # Electron main process
│   └── preload.ts       # Preload script for IPC
├── src/
│   ├── api/
│   │   └── agentApi.ts  # Axios client with heartbeat
│   ├── components/
│   │   ├── Capsule.tsx   # Floating capsule UI
│   │   ├── Capsule.css
│   │   ├── ChatPanel.tsx  # Chat interface
│   │   └── ChatPanel.css
│   ├── types/
│   │   └── agent.ts      # TypeScript types
│   ├── App.tsx
│   └── App.css
└── package.json
```

## Features

- **Capsule Mode**: Desktop floating capsule with status indicators
- **Chat Mode**: Full chat interface with message history
- **Global Shortcuts**:
  - `Alt+Space`: Toggle capsule/chat window
  - `Cmd+K`: Quick chat
  - `Escape`: Hide window
- **System Tray**: Right-click menu for show/hide and backend control
- **Heartbeat**: Polls backend status every second

## Development

```bash
# Install dependencies
npm install

# Start Vite dev server
npm run dev

# Start Electron with hot reload
npm run electron:dev

# Build for production
npm run build

# Build Electron app
npm run electron:build
```

## Backend Integration

The frontend connects to Spring Boot backend on `http://localhost:8080/api/agent`.

Make sure backend is running before starting Electron app:

```bash
# In the backend directory
mvn spring-boot:run
```

## API Endpoints Used

| Endpoint | Method | Purpose |
|----------|---------|---------|
| `/chat` | POST | Fast system - visual Q&A |
| `/task` | POST | Slow system - task orchestration |
| `/status` | GET | System state |
| `/stop` | POST | Emergency stop |
| `/reset` | POST | Reset memory |
| `/screenshot` | GET | Screen capture |
| `/history` | GET/DELETE | Task history |

## Capsule States

| State | Color | Meaning |
|-------|--------|---------|
| Idle | Blue gradient | Agent ready |
| Thinking | Purple gradient + breathing | Processing visual info |
| Executing | Green gradient + spinning | Running task |
| Error | Red gradient + pulsing | Backend unavailable |
