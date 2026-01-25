## Lavis User Guide

> This document is for **end users**, explaining how to install, start, and use the Lavis AI Agent on macOS.

---

## 1. System Requirements

- **OS**: macOS (Intel or Apple Silicon)
- **Network**: Stable internet connection for cloud LLM and voice services
- **Backend Service**:
  - Lavis backend installed and running (Spring Boot app, GraalVM Native Image binary recommended; see developer docs)
  - Default port is `8080`. If you change it, update the frontend configuration accordingly.

---

## 2. Installation & Launch

### 2.1 Install the Electron Desktop App

- Open the installer (e.g. `Lavis AI-1.0.0-arm64.dmg`) and drag `Lavis AI.app` into the `Applications` folder.
- On first launch, if macOS warns about an unidentified developer, go to `System Settings -> Privacy & Security` and allow the app to run.

### 2.2 Start the Backend Service

- The backend can run either as a regular Spring Boot JAR or as a GraalVM Native Image binary (see the Developer Build Guide).
- Once started, visiting `http://localhost:8080/api/agent/status` in a browser should return a JSON status.

### 2.3 Grant macOS Permissions

On the first run, please grant the following permissions:

- **Screen Recording**: allows Lavis to see the screen for visual reasoning.
- **Accessibility**: allows Lavis to control mouse and keyboard.

Path: `System Settings -> Privacy & Security -> Screen Recording / Accessibility`, then enable `Lavis AI`.

---

## 3. First-Time Configuration

### 3.1 Configure LLM & Voice Services

- Lavis requires several API keys (general LLM, vision LLM, ASR, TTS, etc.).
- It is recommended to configure these keys in the backend via environment variables or `application.properties`. The frontend never handles raw keys.

### 3.2 Connectivity Check between Frontend & Backend

- After launching `Lavis AI.app`, the capsule should appear in **Ready** state (blue gradient, static).
- If the backend is down or unreachable, the capsule will enter an **Error** state (red pulsing).

---

## 4. Basic Usage

### 4.1 Window States

- **Idle**: dormant, agent stays in the menu bar / tray.
- **Listening**: voice wake / listening mode with a small capsule window and lightweight feedback.
- **Expanded**: full UI with chat panel and workflow/task panel.

### 4.2 Wake & Shortcuts

- Use a wake word (e.g. “Hi Lavis”) or keyboard shortcuts:
  - `Alt + Space`: toggle capsule or chat window
  - `Cmd + K`: open quick chat
  - `Escape`: hide the window
- Double-click the capsule to toggle between Listening and Expanded modes.

### 4.3 Typical Use Cases

- “Open Safari and search for today’s weather.”
- “Read aloud the error message currently on screen.”
- “Open WeChat and send a message to Alice: see you at 8 PM tonight.”

---

## 5. Security & Privacy

- All screenshots and automation run locally; by default, nothing is uploaded to third-party services.
- Screenshots are used only for real-time visual analysis and are automatically cleaned up to prevent memory leaks.
- API keys are stored only in local configuration or environment variables and are never uploaded by the app.

---

## 6. FAQ

### Q1. The capsule is always red, what does it mean?

This usually means the backend is unavailable. Please ensure the backend service is running and check the network and port configuration.

### Q2. Mouse and keyboard actions do not work?

Make sure `Lavis AI` is allowed under `Privacy & Security -> Accessibility`, then restart the app.

### Q3. Voice does not work properly?

Check microphone permission, network connectivity, and backend voice service configuration (ASR/TTS API keys).

---

## 7. Getting Support

- If you encounter any issues:
  - Refer to `README.md` and `docs/ARCHITECTURE.md` in the project root.
  - Open an Issue for the maintainers (if the project is public).


