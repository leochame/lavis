## Lavis 开发者构建与打包指南

> 本文面向 **开发者**，说明如何在本地构建、调试 Lavis，以及如何使用 **GraalVM Native Image**（高级选项）打包后端、Electron 打包前端。
>
> **重要提示**：
> - **默认打包方式**：项目默认使用 JAR 文件打包后端，详见 `frontend/PACKAGING.md`
> - **GraalVM Native Image**：本节介绍的是可选的高级选项，用于 AOT 编译和更强的代码保护
> - **生产环境推荐**：大多数情况下，使用 `frontend/PACKAGING.md` 中的 JAR 打包方式即可满足需求

---

## 1. 环境准备

### 1.1 必备工具

- JDK 21（推荐使用 GraalVM for JDK 21）
- Maven 3.9+
- Node.js 18+
- pnpm / npm / yarn 三选一（项目默认使用 npm）
- macOS（开发与测试环境）

### 1.2 GraalVM 安装建议

- 从 GraalVM 官方或 SDKMAN 安装 `GraalVM for JDK 21`：
  - 使用 SDKMAN: `sdk install java 21-graal`
  - 或者从官网下载安装包并配置 `JAVA_HOME` 为 GraalVM。
- 确认 `native-image` 工具可用：

```bash
native-image --version
```

---

## 2. 后端开发构建

### 2.1 本地开发

```bash
./mvnw spring-boot:run
```

- 默认端口：`8080`
- 修改端口：在 `application.properties` 中设置 `server.port` 或在命令行添加参数。

### 2.2 传统 JAR 打包

```bash
./mvnw clean package
```

生成物：`target/lavis-0.0.1-SNAPSHOT.jar`（文件名以实际版本为准），通过：

```bash
java -jar target/lavis-0.0.1-SNAPSHOT.jar
```

即可运行。

---

## 3. 使用 GraalVM Native Image 打包后端（高级选项）

> **注意**：这是可选的高级选项。项目默认使用 JAR 文件打包（见 `frontend/PACKAGING.md`）。  
> 仅在需要 AOT 编译、更强的代码保护或特定性能优化时，才考虑使用 GraalVM Native Image。

### 3.1 概念与优势

- **AOT 编译**：GraalVM 将 Spring Boot + 业务代码在构建阶段编译为平台原生机器码。
- **无字节码**：最终产物不包含 `.class` 文件，传统 Java 反编译工具（JD-GUI、CFR）无法还原源码。
- **提升安全性**：逆向需基于汇编，配合编译优化（内联、死代码消除），极大增加破解成本。

### 3.2 Maven 插件集成（示例）

> 下面是推荐的配置示例，可根据项目实际情况添加到 `pom.xml` 中。

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

### 3.3 构建 Native Image

```bash
./mvnw -Pnative -DskipTests native:compile
```

构建成功后，将在 `target/` 下生成可执行二进制（如 `lavis-backend`），直接运行：

```bash
./target/lavis-backend
```

### 3.4 反射与资源配置

- GraalVM 采用「封闭世界」假设，默认只保留被静态分析到的代码路径。
- 如果项目使用反射（如一些 Spring 或 LangChain4j 功能），可能需要额外的反射配置文件或使用官方 Spring Native 支持。

---

## 4. 前端开发与打包

### 4.1 开发模式

```bash
cd frontend
npm install

# 启动 Vite 开发服务器
npm run dev

# 启动 Electron（推荐，带热重载）
npm run electron:dev
```

### 4.2 生产构建

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

---

## 5. 端到端打包流程

### 5.1 默认方式（推荐）：使用 JAR 打包

项目默认使用 JAR 文件打包后端，这是最简单、最稳定的方式：

```bash
cd frontend
npm run package
```

详细说明请参考 `frontend/PACKAGING.md`。

### 5.2 高级方式：使用 GraalVM Native Image

如果需要使用 GraalVM Native Image 打包后端：

1. 使用 GraalVM 将后端打包为 Native Image，可执行文件如 `lavis-backend`。
2. 在 macOS 上测试该二进制，确保所有 API 正常工作。
3. 在 Electron 主进程中：
   - 启动应用时，检测/启动后端二进制进程（可选：嵌入或旁挂）。
   - 监听应用退出事件，优雅关闭后端进程。
4. 使用 `npm run electron:build` 打包为 `.dmg` 或 `.app` 进行分发。

> **注意**：当前仓库中尚未完全自动化「Electron 内嵌 Native Image 后端进程」的逻辑，上述为推荐架构方向。  
> 如需使用 Native Image，需要手动修改 Electron 主进程代码以支持启动二进制文件而非 JAR。

---

## 6. 调试与故障排查

### 6.1 后端

- 使用普通 JAR 运行时：

```bash
./mvnw spring-boot:run
```

- 使用 Native Image 时，若启动失败：
  - 加上 `--verbose` 构建参数重新构建；
  - 检查缺失的反射配置或资源文件。

### 6.2 前端

- Electron 窗口空白：
  - 打开 DevTools（`Cmd+Option+I`）查看报错；
  - 确认 Vite 开发服务器或打包资源是否正常。
- 后端连接失败：
  - 使用 `curl http://localhost:8080/api/agent/status` 检查后端。

---

## 7. 开源项目建议

- 在 `README.md` 中清晰说明：
  - 项目定位与主要特性；
  - 基本安装/运行步骤；
  - 安全说明（尤其是 Native Image 带来的代码保护优势）。
- 在 `docs/` 目录中维护：
  - 用户使用说明（例如 `User-Guide-zh.md` / `User-Guide-en.md`）；
  - 构建与打包指南（本文档以及英文版）；
  - 架构说明（如 `ARCHITECTURE.md`）。


