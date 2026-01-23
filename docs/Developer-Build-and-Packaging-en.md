## Lavis Developer Build & Packaging Guide

> This document targets **developers**, describing how to build and debug Lavis locally, and how to package the backend with **GraalVM Native Image** and the frontend with Electron.

---

## 1. Environment Setup

### 1.1 Prerequisites

- JDK 21 (GraalVM for JDK 21 recommended)
- Maven 3.9+
- Node.js 18+
- pnpm / npm / yarn (npm is used by default)
- macOS as the development and testing platform

### 1.2 GraalVM Installation

- Install `GraalVM for JDK 21` via SDKMAN or from the official website:
  - With SDKMAN: `sdk install java 21-graal`
  - Or download from GraalVM website and set `JAVA_HOME` to GraalVM.
- Ensure the `native-image` tool is available:

```bash
native-image --version
```

---

## 2. Backend Development Build

### 2.1 Local Development

```bash
./mvnw spring-boot:run
```

- Default port: `8080`
- To change the port, set `server.port` in `application.properties` or pass it as a command-line argument.

### 2.2 Traditional JAR Packaging

```bash
./mvnw clean package
```

Artifact: `target/lavis-0.0.1-SNAPSHOT.jar` (name depends on version), runnable via:

```bash
java -jar target/lavis-0.0.1-SNAPSHOT.jar
```

---

## 3. Backend Packaging with GraalVM Native Image

### 3.1 Concept & Benefits

- **AOT compilation**: GraalVM compiles Spring Boot and your business code into native machine code at build time.
- **No bytecode**: The final artifact contains no `.class` files; common Java decompilers (JD-GUI, CFR) cannot recover the source.
- **Security**: Reverse engineering falls back to assembly-level analysis; combined with optimizations (inlining, dead-code elimination), this greatly increases the cost of cracking.

### 3.2 Maven Plugin Integration (Example)

> The following is a recommended configuration example. Adapt and add it to `pom.xml` according to your project’s needs.

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

### 3.3 Build the Native Image

```bash
./mvnw -Pnative -DskipTests native:compile
```

After a successful build, an executable binary (e.g. `lavis-backend`) will be placed under `target/`:

```bash
./target/lavis-backend
```

### 3.4 Reflection & Resource Configuration

- GraalVM uses the “closed world” assumption and only keeps code paths reachable via static analysis.
- If you rely on reflection (e.g., certain Spring or LangChain4j features), you may need additional reflection configuration files or Spring Native support.

---

## 4. Frontend Development & Packaging

### 4.1 Development Mode

```bash
cd frontend
npm install

# Start Vite dev server
npm run dev

# Start Electron with hot reload (recommended)
npm run electron:dev
```

### 4.2 Production Build

```bash
cd frontend

npm run build
npm run electron:build
```

Artifacts will be located under `frontend/dist-electron/`, for example:

- `Lavis AI-1.0.0-arm64.dmg`
- `mac-arm64/Lavis AI.app`

---

## 5. End-to-End Packaging Flow (Recommended for Production)

1. Build the backend as a native image executable (e.g. `lavis-backend`) using GraalVM.
2. Test the binary on macOS to ensure all APIs work correctly.
3. In the Electron main process:
   - On app launch, detect/start the backend binary (either embedded or sidecar).
   - Listen for app exit events and gracefully shut down the backend process.
4. Run `npm run electron:build` to package the app into `.dmg` / `.app` for distribution.

> The current repository may not fully automate “backend embedded in Electron”; this is the recommended architecture direction.

---

## 6. Debugging & Troubleshooting

### 6.1 Backend

- With a regular JAR:

```bash
./mvnw spring-boot:run
```

- With a native image, if startup fails:
  - Rebuild with a `--verbose` build argument;
  - Check for missing reflection configuration or resource files.

### 6.2 Frontend

- Blank Electron window:
  - Open DevTools (`Cmd+Option+I`) and inspect errors;
  - Ensure the Vite dev server or built assets are available.
- Backend connectivity:
  - Use `curl http://localhost:8080/api/agent/status` to verify the backend is running.

---

## 7. Open-Source Best Practices

- In `README.md`, clearly describe:
  - Project purpose and key features;
  - Basic installation and run steps;
  - Security notes (especially code protection with native image).
- Under the `docs/` directory, maintain:
  - User guides (e.g. `User-Guide-zh.md` / `User-Guide-en.md`);
  - Build & packaging guides (this document and the Chinese version);
  - Architecture overview (e.g. `ARCHITECTURE.md`).


