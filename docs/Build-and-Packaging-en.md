# Lavis Build & Packaging Guide

Complete guide for building, packaging, debugging, and troubleshooting.

> **Note**: This guide covers both **default JAR packaging** (recommended for most users) and **GraalVM Native Image** (advanced option for AOT compilation and stronger code protection).

---

## 1. Environment Setup

### 1.1 Prerequisites

1. **Java Development Environment**
   - JDK 21 or higher
   - Maven 3.9+ (project includes `mvnw`, no separate installation needed; set `MAVEN_CMD` to specify Maven path if needed)

2. **Node.js Environment**
   - Node.js 18+
   - npm (installed with Node.js)
   - pnpm / yarn (optional alternatives)

3. **macOS Development Environment**
   - macOS 10.15+ (for building macOS apps)

### 1.2 GraalVM Installation (Optional, for Native Image)

> **Note**: GraalVM is only required if you want to use Native Image for AOT compilation. For default JAR packaging, regular JDK 21 is sufficient.

- Install `GraalVM for JDK 21` via SDKMAN or from the official website:
  - With SDKMAN: `sdk install java 21-graal`
  - Or download from GraalVM website and set `JAVA_HOME` to GraalVM.
- Ensure the `native-image` tool is available:

```bash
native-image --version
```

---

## 2. Development Mode

### 2.1 Backend Development

**Local Development:**
```bash
./mvnw spring-boot:run
```

- Default port: `18765`
- To change the port, set `server.port` in `application.properties` or pass it as a command-line argument.

**Build JAR for Testing:**
```bash
./mvnw clean package
```

Artifact: `target/lavis-0.0.1-SNAPSHOT.jar` (name depends on version), runnable via:

```bash
java -jar target/lavis-0.0.1-SNAPSHOT.jar
```

### 2.2 Frontend Development

```bash
cd frontend
npm install

# Start Vite dev server
npm run dev

# Start Electron with hot reload (recommended)
npm run electron:dev
```

---

## 3. Packaging Process

### 3.1 Default Method (Recommended): JAR Packaging

The project uses JAR files for backend packaging by default, which is the simplest and most stable approach.

**One-Click Build:**
```bash
cd frontend
npm install  # Install dependencies for first run
npm run package
```

This command will automatically:
1. Check prerequisites (Java, Maven, Node.js)
2. Build Java backend JAR file
3. Build frontend code
4. Compile Electron main process
5. Package app with electron-builder

### 3.2 Build Output

After packaging, application files are located in `frontend/dist-electron/`:

```
dist-electron/
├── Lavis-1.0.0-arm64.dmg              # macOS installer (recommended for distribution)
├── Lavis-1.0.0-arm64.dmg.blockmap      # DMG incremental update map
├── Lavis-1.0.0-arm64-mac.zip           # ZIP archive (alternative distribution)
├── Lavis-1.0.0-arm64-mac.zip.blockmap  # ZIP incremental update map
└── mac-arm64/
    └── Lavis.app/                       # macOS application bundle
        └── Contents/
            ├── Info.plist               # Application metadata
            ├── MacOS/
            │   └── Lavis                # Electron main executable
            ├── Frameworks/              # Electron framework and dependencies
            └── Resources/               # Application resources
                ├── app.asar             # Frontend code (packaged)
                ├── app.asar.unpacked/   # Files extracted from asar
                │   └── dist/models/     # Vosk model files
                ├── backend/
                │   └── lavis.jar        # Java backend JAR file
                └── jre/                 # Embedded Java runtime
                    └── mac-arm64/
                        └── Contents/Home/bin/java
```

### 3.3 macOS Gatekeeper Handling (Automatic)

The packaging script automatically handles macOS Gatekeeper issues, no manual action required:

- ✅ **Auto-remove Quarantine Attribute**: After packaging, the script automatically removes the `com.apple.quarantine` extended attribute
- ✅ **No Developer Certificate Required**: App uses adhoc signing (temporary signature), suitable for free distribution
- ✅ **Ready to Run**: Packaged app can run directly without additional configuration

**If you encounter "app is damaged" error**:

1. **First Run**: Right-click the app, select "Open", then click "Open" in the dialog
2. **Manual Removal** (if auto-handling fails):
   ```bash
   xattr -d com.apple.quarantine /path/to/Lavis.app
   ```

> **Note**: For formal distribution (e.g., via App Store), consider obtaining an Apple Developer certificate and configuring code signing and notarization. See [8.2 Code Signing](#82-code-signing) and [8.3 Notarization](#83-notarization).

---

## 4. Advanced Option: GraalVM Native Image

> **Note**: This is an optional advanced option. The project uses JAR files for packaging by default.  
> Consider using GraalVM Native Image only if you need AOT compilation, stronger code protection, or specific performance optimizations.

### 4.1 Concept & Benefits

- **AOT compilation**: GraalVM compiles Spring Boot and your business code into native machine code at build time.
- **No bytecode**: The final artifact contains no `.class` files; common Java decompilers (JD-GUI, CFR) cannot recover the source.
- **Security**: Reverse engineering falls back to assembly-level analysis; combined with optimizations (inlining, dead-code elimination), this greatly increases the cost of cracking.

### 4.2 Maven Plugin Integration

> The following is a recommended configuration example. Adapt and add it to `pom.xml` according to your project's needs.

Add this to the `<build><plugins>` section of `pom.xml`:

```xml
<plugin>
    <groupId>org.graalvm.buildtools</groupId>
    <artifactId>native-maven-plugin</artifactId>
    <version>0.10.2</version>
    <extensions>true</extensions>
    <configuration>
        <imageName>lavis-backend</imageName>
        <buildArgs>
            <!-- Enable extra debug/logging flags if needed -->
            <!-- <buildArg>--verbose</buildArg> -->
        </buildArgs>
    </configuration>
</plugin>
```

> For exact versions and options, refer to the official GraalVM documentation, especially for Spring Boot 3.5.9 compatibility.

### 4.3 Build the Native Image

```bash
./mvnw -Pnative -DskipTests native:compile
```

After a successful build, an executable binary (e.g. `lavis-backend`) will be placed under `target/`:

```bash
./target/lavis-backend
```

### 4.4 Reflection & Resource Configuration

- GraalVM uses the "closed world" assumption and only keeps code paths reachable via static analysis.
- If you rely on reflection (e.g., certain Spring or LangChain4j features), you may need additional reflection configuration files or Spring Native support.

### 4.5 Using Native Image with Electron

If you need to use GraalVM Native Image for backend packaging:

1. Build the backend as a native image executable (e.g. `lavis-backend`) using GraalVM.
2. Test the binary on macOS to ensure all APIs work correctly.
3. In the Electron main process:
   - On app launch, detect/start the backend binary (either embedded or sidecar).
   - Listen for app exit events and gracefully shut down the backend process.
4. Run `npm run electron:build` to package the app into `.dmg` / `.app` for distribution.

> **Note**: The current repository may not fully automate "backend embedded in Electron with Native Image"; this is the recommended architecture direction.  
> To use Native Image, you'll need to manually modify the Electron main process code to support launching the binary instead of the JAR.

---

## 5. Packaging Tools and Files

### Core Packaging Files

| File Path | Description |
|-----------|-------------|
| `frontend/scripts/package.js` | One-click packaging script, automates entire packaging process |
| `frontend/electron-builder.config.js` | electron-builder configuration |
| `frontend/package.json` | npm scripts and dependency configuration |
| `frontend/build/entitlements.mac.plist` | macOS permissions configuration |
| `frontend/build/icon.icns` | Application icon file |

### Development and Testing Tools

| File Path | Description |
|-----------|-------------|
| `frontend/scripts/test-packaged-app.sh` | Test packaged app, auto-open DevTools |
| `frontend/scripts/diagnose-wake-word.sh` | Diagnose wake word issues in packaged app |
| `frontend/scripts/open-devtools.sh` | Open DevTools for packaged app |
| `frontend/scripts/generate-icon.js` | Generate macOS app icon (.icns) |
| `frontend/scripts/electron-dev.js` | Start Electron app in development mode |

### Related Source Files

| File Path | Packaging-Related Functionality |
|-----------|-------------------------------|
| `frontend/electron/main.ts` | Detect packaged environment, manage backend process |
| `frontend/electron/backend-manager.ts` | Start embedded JRE and JAR in packaged environment |
| `frontend/electron/preload.ts` | Provide secure API bridge |
| `frontend/vite.config.ts` | Build frontend resources |

---

## 6. How It Works

1. **Auto-start Backend**: On app launch, Electron main process automatically detects and starts embedded Java backend
2. **JRE Management**: Uses embedded JRE to run Java backend, no Java installation required for users
3. **Resource Management**: JAR and JRE are packaged into app Resources directory via `extraResources`
4. **Process Management**: Automatically closes Java backend process on app exit

### Development Mode vs Production Mode

**Development Mode**:
- Uses system-installed Java
- JAR loaded from project `target/` directory
- Frontend loaded from Vite dev server

**Production Mode (Packaged)**:
- Uses embedded JRE
- JAR loaded from app Resources directory
- Frontend loaded from packaged `app.asar`

---

## 7. Debugging & Troubleshooting

### 7.1 Opening Developer Tools

#### Method 1: Using Test Script (Recommended)

```bash
# Use default path
./frontend/scripts/test-packaged-app.sh

# Or specify app path
./frontend/scripts/test-packaged-app.sh /path/to/Lavis.app
```

#### Method 2: Using Diagnostic Script

```bash
# Diagnose app structure and model files
./frontend/scripts/diagnose-wake-word.sh
```

#### Method 3: Manual Environment Variables

```bash
export ELECTRON_DEVTOOLS=1
export OPEN_DEVTOOLS=1
open -a frontend/dist-electron/mac-arm64/Lavis.app
```

#### Method 4: Keyboard Shortcut

While app is running, use:
- **macOS**: `Cmd+Alt+I`
- **Windows/Linux**: `Ctrl+Alt+I`

### 7.2 Viewing Logs

In Developer Tools **Console** tab, look for:

- `[Vosk] Loading model from: ...` - Model loading started
- `[Vosk] ✅ Model loaded successfully` - Model loaded successfully
- `[Vosk] 🎤 Recognized: "..."` - Recognized text
- `[Vosk] ✅ Wake word matched!` - Wake word matched successfully

### 7.3 Backend Debugging

**With a regular JAR:**
```bash
./mvnw spring-boot:run
```

**With a native image, if startup fails:**
- Rebuild with a `--verbose` build argument;
- Check for missing reflection configuration or resource files.

**Backend connectivity:**
- Use `curl http://localhost:18765/api/agent/status` to verify the backend is running.

### 7.4 Frontend Debugging

**Blank Electron window:**
- Open DevTools (`Cmd+Option+I`) and inspect errors;
- Ensure the Vite dev server or built assets are available.

### 7.5 Common Issues

#### 1. Packaging Failed: JAR File Not Found

**Error Message**:
```
JAR file not found at: ...
```

**Solution**:
- Ensure `mvn clean package` has been run to build backend
- Check if `target/lavis-0.0.1-SNAPSHOT.jar` exists

#### 2. Packaging Failed: Maven Not Found

**Solution**:
- Script automatically uses system Maven or project's `mvnw`
- To specify Maven path, use: `MAVEN_CMD=/path/to/mvn npm run package`
- Confirm JDK is installed and `java -version` works
- If `JAVA_HOME` is not set, run: `export JAVA_HOME=$(/usr/libexec/java_home)`

#### 3. JRE Not Found

**Error Message**:
```
Java executable not found at: ...
```

**Solution**:
- Ensure `frontend/jre/mac-arm64/` directory exists
- Check JRE directory structure is correct

#### 4. Packaging Failed

**Possible Causes**:
- Missing dependencies: Run `npm install` to install dependencies
- electron-builder not installed: Run `npm install -D electron-builder`
- Permission issues: Ensure write permission for `dist-electron` directory

#### 5. App Cannot Start Backend

**Check Steps**:
1. View app logs (Console.app or terminal)
2. Confirm JAR and JRE paths are correct
3. Check if JRE has execute permission

#### 6. Wake Word Not Working

**Check Steps**:
1. Are model files extracted from asar? (Should be in `app.asar.unpacked/dist/models/`)
2. Use diagnostic script: `./frontend/scripts/diagnose-wake-word.sh`
3. Check model loading info in Console logs

**Common Issues**:

- **Model Files Not Found (404 Error)**
  - Check if `frontend/public/models/` directory contains model files
  - Confirm `asarUnpack` in packaging config includes `dist/models/**/*.tar.gz`
  - Rebuild app

- **Model Loading Failed**
  - Check if model files are complete
  - View Network tab, confirm model file request URL
  - Check file permissions

- **Text Recognized But Not Matched**
  - Check wake word configuration (default is "hi lavis")
  - View recognized text, may need to adjust phonetic mapping
  - Try speaking more clearly

#### 7. macOS Gatekeeper Blocking App

**Error Message**:
```
"Lavis" is damaged and can't be opened. You should move it to the Trash.
```

**Cause**:
- Files downloaded from the internet are automatically marked with `com.apple.quarantine` attribute by macOS
- Gatekeeper blocks unsigned or unnotarized apps from running

**Solution**:

1. **Automatic Handling** (Recommended): The packaging script automatically removes quarantine attribute, just rebuild:
   ```bash
   cd frontend
   npm run package
   ```

2. **Manual Removal**: If you've already downloaded the app, manually remove quarantine attribute:
   ```bash
   # For .app file
   xattr -d com.apple.quarantine /path/to/Lavis.app
   
   # For .dmg file
   xattr -d com.apple.quarantine /path/to/Lavis-1.0.0-arm64.dmg
   ```

3. **Allow via System Settings**:
   - Right-click the app, select "Open"
   - Click "Open" in the dialog
   - System will remember your choice, you can run it directly next time

> **Note**: The app uses adhoc signing (temporary signature), which is the free solution. For formal distribution, consider obtaining an Apple Developer certificate and configuring code signing and notarization.

---

## 8. Advanced Configuration

### 8.1 Custom Application Icon

1. Prepare icon file (.icns format)
2. Place at `frontend/build/icon.icns`
3. electron-builder will automatically use it

Or use tool to generate:

```bash
cd frontend
node scripts/generate-icon.js
```

### 8.2 Code Signing

To enable code signing, add to `frontend/electron-builder.config.js`:

```javascript
mac: {
  identity: 'Developer ID Application: Your Name (TEAM_ID)',
  // ...
}
```

### 8.3 Notarization

To enable notarization, configure environment variables:

```bash
export APPLE_ID="your@email.com"
export APPLE_ID_PASSWORD="app-specific-password"
export APPLE_TEAM_ID="TEAM_ID"
```

---

## 9. Related Resources

- [Electron Builder Documentation](https://www.electron.build/)
- [Project Root README](../README.md)
- [System Architecture](ARCHITECTURE.md)

