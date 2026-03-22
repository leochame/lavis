# Lavis

<p align="center">
  <img src="docs/images/icon.png" alt="Lavis Logo" width="128" />
</p>

Lavis is a macOS desktop multimodal AI agent that can observe the screen, execute mouse/keyboard actions, and support voice interaction.

## Why Lavis

Lavis is designed for local desktop automation with an agent-style loop:
- perceive screen state
- decide next action
- execute and re-observe until the goal is done

It is suitable for personal workflow automation, repetitive GUI tasks, and voice-assisted desktop control.

## Key Features

- Screen perception with screenshot-driven reasoning
- System-level mouse/keyboard action execution
- Voice pipeline (wake word, STT, TTS)
- Electron desktop UI for chat, task progress, and management
- Built-in scheduler and skills extension system
- Local-first runtime model and local config management

## Tech Stack

| Layer | Stack |
|---|---|
| Backend | Java 21, Spring Boot |
| Frontend | React, TypeScript, Vite |
| Desktop | Electron |
| State | Zustand |
| HTTP Client | Axios |

## User Documentation

- English: [docs/User-Guide-en.md](docs/User-Guide-en.md)
- 中文: [docs/User-Guide-zh.md](docs/User-Guide-zh.md)

All end-user content (installation, permissions, environment setup, task/skills usage, FAQ) is maintained in the user guides above.

## Developer Quick Start

### Prerequisites

- macOS
- JDK 21+
- Node.js 18+

### Configure Runtime

```bash
cp .env.example .env
# Fill app.llm.models.* keys in .env
```

### One-Click Start

```bash
./start.sh
```

This single command will check prerequisites, install dependencies, start the backend and frontend, and open the Electron app. Press `Ctrl+C` to stop everything.

### Manual Start (Alternative)

If you prefer to start services separately:

```bash
# Terminal 1: Start Backend
./mvnw spring-boot:run

# Terminal 2: Start Frontend
cd frontend
npm install
npm run electron:dev
```

Default backend port: `18765`.

## Development Workflow

1. Run `./start.sh` (or start backend and frontend separately)
2. Iterate on backend/frontend code
3. Validate behavior in Electron app

## Packaging (macOS)

```bash
cd frontend
npm install
npm run package
```

## Architecture Overview

```text
Frontend (Electron + React)
  -> HTTP/WebSocket
Backend (Spring Boot Agent Services)
  -> LLM/STT/TTS Providers
  -> System Actions (screen, mouse, keyboard)
```

## Repository Layout

```text
lavis/
├── src/main/java/com/lavis/   # Java backend
├── src/main/resources/        # Backend config and resources
├── frontend/                  # Electron + React frontend
└── docs/                      # User guides (EN/ZH)
```

## Contributing

Pull requests are welcome. Recommended flow:
1. Create a feature branch
2. Keep changes scoped and documented
3. Include verification steps in PR description

## Roadmap

- Better task reliability and recovery strategies
- Richer skills ecosystem and management UX
- Improved cross-platform packaging consistency

## License

MIT
