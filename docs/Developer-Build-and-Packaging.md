## Lavis 开发者构建与打包指南 / Developer Build & Packaging Guide

> 本文面向 **开发者**，说明如何在本地构建、调试 Lavis，以及如何使用 **GraalVM Native Image** 打包后端、Electron 打包前端。  
> This document targets **developers**, describing how to build and debug Lavis locally, and how to package the backend with **GraalVM Native Image** and the frontend with Electron.

---

## 1. 环境准备 / Environment Setup

### 1.1 必备工具 / Prerequisites

**中文：**
- JDK 21（推荐使用 GraalVM for JDK 21）
- Maven 3.9+
- Node.js 18+
- pnpm / npm / yarn 三选一（项目默认使用 npm）
- macOS（开发与测试环境）

**English:**
- JDK 21 (GraalVM for JDK 21 recommended)
- Maven 3.9+
- Node.js 18+
- pnpm / npm / yarn (npm used by default)
- macOS as the development and testing platform

### 1.2 GraalVM 安装建议 / GraalVM Installation

**中文：**
- 从 GraalVM 官方或 SDKMAN 安装 `GraalVM for JDK 21`：
  - 使用 SDKMAN: `sdk install java 21-graal`
  - 或者从官网下载安装包并配置 `JAVA_HOME` 为 GraalVM。
- 确认 `native-image` 工具可用：

```bash
native-image --version
```

**English:**
- Install `GraalVM for JDK 21` via SDKMAN or GraalVM website:
  - With SDKMAN: `sdk install java 21-graal`
  - Or download from the official website and set `JAVA_HOME` to GraalVM.
- Ensure `native-image` is available:

```bash
native-image --version
```

---

## 2. 后端开发构建 / Backend Development Build

### 2.1 本地开发 / Local Development

**中文：**

```bash
./mvnw spring-boot:run
```

- 默认端口：`8080`
- 修改端口：在 `application.properties` 中设置 `server.port` 或在命令行添加参数。

**English:**

```bash
./mvnw spring-boot:run
```

- Default port: `8080`
- To change the port, set `server.port` in `application.properties` or pass it as a command-line argument.

### 2.2 传统 JAR 打包 / Traditional JAR Packaging

**中文：**

```bash
./mvnw clean package
```

生成物：`target/lavis-0.0.1-SNAPSHOT.jar`（文件名以实际版本为准），通过：

```bash
java -jar target/lavis-0.0.1-SNAPSHOT.jar
```

即可运行。

**English:**

```bash
./mvnw clean package
```

Artifact: `target/lavis-0.0.1-SNAPSHOT.jar` (name depends on version), runnable via:

```bash
java -jar target/lavis-0.0.1-SNAPSHOT.jar
```

---

## 3. 使用 GraalVM Native Image 打包后端 / Backend Packaging with GraalVM Native Image

### 3.1 概念与优势 / Concept & Benefits

**中文：**
- **AOT 编译**：GraalVM 将 Spring Boot + 业务代码在构建阶段编译为平台原生机器码。
- **无字节码**：最终产物不包含 `.class` 文件，传统 Java 反编译工具（JD-GUI、CFR）无法还原源码。
- **提升安全性**：逆向需基于汇编，配合编译优化（内联、死代码消除），极大增加破解成本。

**English:**
- **AOT compilation**: GraalVM compiles Spring Boot and your code into native machine code at build time.
- **No bytecode**: The final binary contains no `.class` files, so common Java decompilers (JD-GUI, CFR) cannot recover source code.
- **Security**: Reverse engineering falls back to assembly-level analysis, and heavy optimizations make it much harder.

### 3.2 Maven 插件集成（示例）/ Maven Plugin Integration (Example)

> **说明 / Note**: 下面是推荐的配置示例，可根据项目实际情况添加到 `pom.xml` 中。  
> **This is a recommended example configuration; please adapt and add it to `pom.xml` as needed.**

**中文/English:**

在 `pom.xml` 的 `<build><plugins>` 部分中增加：

```xml
<plugin>
    <groupId>org.graalvm.buildtools</groupId>
    <artifactId>native-maven-plugin</artifactId>
    <version>0.10.2</version>
    <extensions>true</extensions>
    <configuration>
        <imageName>lavis-backend</imageName>
        <buildArgs>
            <!-- 根据需要开启调试/日志等参数 -->
            <!-- <buildArg>--verbose</buildArg> -->
        </buildArgs>
    </configuration>
</plugin>
```

> 实际版本号和配置请参考 GraalVM 官方文档，尤其是与 Spring Boot 3.5.9 的兼容性。  
> For exact versions and options, refer to the official GraalVM docs, especially for Spring Boot 3.5.9 compatibility.

### 3.3 构建 Native Image / Build Native Image

**中文：**

```bash
./mvnw -Pnative -DskipTests native:compile
```

构建成功后，将在 `target/` 下生成可执行二进制（如 `lavis-backend`），直接运行：

```bash
./target/lavis-backend
```

**English:**

```bash
./mvnw -Pnative -DskipTests native:compile
```

After a successful build, an executable binary (e.g. `lavis-backend`) will be placed under `target/`:

```bash
./target/lavis-backend
```

### 3.4 反射与资源配置 / Reflection & Resource Configuration

**中文：**
- GraalVM 采用「封闭世界」假设，默认只保留被静态分析到的代码路径。
- 如果项目使用反射（如一些 Spring 或 LangChain4j 功能），可能需要额外的反射配置文件或使用官方 Spring Native 支持。

**English:**
- GraalVM uses the “closed world” assumption and only keeps code paths reachable via static analysis.
- If you rely on reflection (e.g., some Spring or LangChain4j features), you may need additional reflection configuration files or Spring Native support.

---

## 4. 前端开发与打包 / Frontend Development & Packaging

### 4.1 开发模式 / Development Mode

**中文：**

```bash
cd frontend
npm install

# 启动 Vite 开发服务器
npm run dev

# 启动 Electron（推荐，带热重载）
npm run electron:dev
```

**English:**

```bash
cd frontend
npm install

# Start Vite dev server
npm run dev

# Start Electron with hot reload (recommended)
npm run electron:dev
```

### 4.2 生产构建 / Production Build

**中文：**

```bash
cd frontend

# 构建前端静态资源
npm run build

# 打包 Electron 应用
npm run electron:build
```

生成物位于 `frontend/dist-electron/`，例如：
- `Lavis AI-1.0.0-arm64.dmg`
- `mac-arm64/Lavis AI.app`

**English:**

```bash
cd frontend

npm run build
npm run electron:build
```

Artifacts will be located under `frontend/dist-electron/`, e.g.:
- `Lavis AI-1.0.0-arm64.dmg`
- `mac-arm64/Lavis AI.app`

---

## 5. 端到端打包流程 / End-to-End Packaging Flow

### 5.1 推荐流程（生产环境）/ Recommended Production Flow

**中文：**
1. 使用 GraalVM 将后端打包为 Native Image，可执行文件如 `lavis-backend`。
2. 在 macOS 上测试该二进制，确保所有 API 正常工作。
3. 在 Electron 主进程中：
   - 启动应用时，检测/启动后端二进制进程（可选：嵌入或旁挂）。
   - 监听应用退出事件，优雅关闭后端进程。
4. 使用 `npm run electron:build` 打包为 `.dmg` 或 `.app` 进行分发。

**English:**
1. Build the backend as a native image executable (e.g. `lavis-backend`) using GraalVM.
2. Test the binary on macOS to ensure all APIs work correctly.
3. In the Electron main process:
   - Optionally start or detect the backend binary on app launch.
   - Gracefully shut down the backend when the app exits.
4. Run `npm run electron:build` to package the app into `.dmg` / `.app` for distribution.

> 当前仓库中尚未完全自动化「Electron 内嵌后端进程」的逻辑，以上为推荐架构方向。  
> The current repository may not fully automate “backend embedded in Electron”; the above is a recommended direction.

---

## 6. 调试与故障排查 / Debugging & Troubleshooting

### 6.1 后端 / Backend

**中文：**
- 使用普通 JAR 运行时：

```bash
./mvnw spring-boot:run
```

- 使用 Native Image 时，若启动失败：
  - 加上 `--verbose` 构建参数重新构建。
  - 检查缺失的反射配置或资源文件。

**English:**
- With regular JAR:

```bash
./mvnw spring-boot:run
```

- With native image, if startup fails:
  - Rebuild with `--verbose` build arg.
  - Check for missing reflection configs or resource files.

### 6.2 前端 / Frontend

**中文：**
- Electron 窗口空白：
  - 打开 DevTools（`Cmd+Option+I`）查看报错。
  - 确认 Vite 开发服务器或打包资源是否正常。
- 后端连接失败：
  - 使用 `curl http://localhost:8080/api/agent/status` 检查后端。

**English:**
- Blank Electron window:
  - Open DevTools (`Cmd+Option+I`) and inspect errors.
  - Ensure Vite dev server or built assets are available.
- Backend connectivity:
  - Use `curl http://localhost:8080/api/agent/status` to verify backend.

---

## 7. 开源项目建议 / Open-Source Best Practices

**中文：**
- 在 README 中清晰说明：
  - 项目定位与主要特性
  - 基本安装/运行步骤
  - 安全说明（尤其是 Native Image 带来的代码保护优势）
- 在 `docs/` 目录中维护：
  - 用户使用说明（本仓库中的 `User-Guide.md`）
  - 构建与打包指南（本文档）
  - 架构说明（如 `ARCHITECTURE.md`）

**English:**
- In `README`, clearly describe:
  - Project purpose and key features
  - Basic installation / run steps
  - Security notes (especially code protection with native image)
- Under `docs/` directory, maintain:
  - User guide (`User-Guide.md`)
  - Build & packaging guide (this file)
  - Architecture overview (e.g. `ARCHITECTURE.md`)


