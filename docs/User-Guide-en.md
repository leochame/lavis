## Lavis User Guide

> This document is for **end users**, explaining how to install, start, and use the Lavis AI Agent on macOS.

---

## 1. System Requirements

- **OS**: macOS (Intel or Apple Silicon)
- **Network**: Stable internet connection for cloud LLM and voice services
- **Backend Service**:
  - Lavis backend installed and running (Spring Boot app, GraalVM Native Image binary recommended; see developer docs)
  - Default port is `18765`. If you change it, update the frontend configuration accordingly.

---

## 2. Installation & Launch

### 2.1 Install the Electron Desktop App

- Open the installer (e.g. `Lavis AI-1.0.0-arm64.dmg`) and drag `Lavis AI.app` into the `Applications` folder.
- On first launch, if macOS warns about an unidentified developer, go to `System Settings -> Privacy & Security` and allow the app to run.

### 2.2 Start the Backend Service

- The backend can run either as a regular Spring Boot JAR or as a GraalVM Native Image binary (see the Developer Build Guide).
- Once started, visiting `http://localhost:18765/api/agent/status` in a browser should return a JSON status.

### 2.3 Grant macOS Permissions

On the first run, please grant the following permissions:

- **Screen Recording**: allows Lavis to see the screen for visual reasoning.
- **Accessibility**: allows Lavis to control mouse and keyboard.

Path: `System Settings -> Privacy & Security -> Screen Recording / Accessibility`, then enable `Lavis AI`.

---

## 3. First-Time Configuration

### 3.1 Configure API Key

Lavis uses Google Gemini API for all AI services (chat, speech-to-text, and text-to-speech). You only need **one API key** to get started.

#### Option 1: Environment Variable (Recommended)

Set the `GEMINI_API_KEY` environment variable before starting the app:

```bash
export GEMINI_API_KEY=your_gemini_api_key_here

# Then start the app
open /Applications/Lavis\ AI.app
```

**For persistent configuration**, add it to your shell profile (e.g., `~/.zshrc` or `~/.bash_profile`):

```bash
echo 'export GEMINI_API_KEY=your_gemini_api_key_here' >> ~/.zshrc
source ~/.zshrc
```

#### Option 2: Frontend Settings Panel (Easiest for First-Time Users)

1. Launch `Lavis AI.app`
2. Open the Settings panel (via menu bar icon or `Cmd + K`)
3. Enter your Gemini API key in the settings form
4. Click "Save" - the key will be stored and used immediately

> **Note**: The frontend settings panel provides the easiest way to configure your API key without editing files or environment variables.

#### Option 3: Configuration File (For Advanced Users)

If you're running the backend manually (developer mode):

1. Copy the example configuration:
   ```bash
   cp src/main/resources/application.properties.example src/main/resources/application.properties
   ```

2. Edit `src/main/resources/application.properties` and set:
   ```properties
   app.llm.models.gemini.api-key=your_gemini_api_key_here
   app.llm.models.whisper.api-key=your_gemini_api_key_here
   app.llm.models.tts.api-key=your_gemini_api_key_here
   ```

   Or simply set the environment variable `GEMINI_API_KEY` (the config file will use it automatically).

#### Getting Your Gemini API Key

1. Visit [Google AI Studio](https://aistudio.google.com/app/apikey)
2. Sign in with your Google account
3. Click "Create API Key"
4. Copy the generated key

> **Security Note**: API keys are stored locally only (in environment variables or local config files). They are never uploaded to third-party services or exposed to the frontend.

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

### Q4. How do I configure the API key?

You have three options:
1. **Frontend Settings Panel** (easiest): Launch the app, open Settings, enter your Gemini API key, and save.
2. **Environment Variable**: Set `GEMINI_API_KEY` in your terminal before launching the app.
3. **Configuration File**: Edit `application.properties` in the backend resources folder.

See section 3.1 for detailed instructions.

### Q5. Where do I get a Gemini API key?

Visit [Google AI Studio](https://aistudio.google.com/app/apikey), sign in with your Google account, and create a new API key. One key is sufficient for all services (chat, STT, TTS).

---

## 7. Getting Support

- If you encounter any issues:
  - Refer to `README.md` and `docs/ARCHITECTURE.md` in the project root.
  - Open an Issue for the maintainers (if the project is public).


