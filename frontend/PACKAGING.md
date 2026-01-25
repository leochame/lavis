# Lavis 打包完整指南

本指南包含打包、调试和故障排除的完整信息。

## 📦 打包流程

### 一键打包

```bash
cd frontend
npm install  # 首次运行需要安装依赖
npm run package
```

这个命令会自动：
1. 检查前置条件（Java、Maven、Node.js）
2. 构建 Java 后端 JAR 文件
3. 构建前端代码
4. 编译 Electron 主进程代码
5. 使用 electron-builder 打包应用

### 前置要求

1. **Java 开发环境**
   - JDK 21 或更高版本
   - Maven 3.9+（项目已包含 `mvnw`，无需单独安装；如需指定 Maven，可设置 `MAVEN_CMD`）

2. **Node.js 环境**
   - Node.js 18+ 
   - npm（随 Node.js 安装）

3. **macOS 开发环境**
   - macOS 10.15+（用于构建 macOS 应用）

## 📁 打包输出

打包完成后，应用文件位于 `frontend/dist-electron/` 目录：

```
dist-electron/
├── Lavis-1.0.0-arm64.dmg              # macOS 安装包（推荐分发）
├── Lavis-1.0.0-arm64.dmg.blockmap      # DMG 增量更新映射
├── Lavis-1.0.0-arm64-mac.zip           # 压缩包（备用分发）
├── Lavis-1.0.0-arm64-mac.zip.blockmap  # ZIP 增量更新映射
└── mac-arm64/
    └── Lavis.app/                       # macOS 应用程序包
        └── Contents/
            ├── Info.plist               # 应用元数据
            ├── MacOS/
            │   └── Lavis                # Electron 主程序可执行文件
            ├── Frameworks/              # Electron 框架和依赖
            └── Resources/               # 应用资源
                ├── app.asar             # 前端代码（打包）
                ├── app.asar.unpacked/   # 从 asar 中解压的文件
                │   └── dist/models/     # Vosk 模型文件
                ├── backend/
                │   └── lavis.jar        # Java 后端 JAR 文件
                └── jre/                 # 内嵌 Java 运行时
                    └── mac-arm64/
                        └── Contents/Home/bin/java
```

## 🔧 打包工具和文件

### 核心打包文件

| 文件路径 | 说明 |
|---------|------|
| `scripts/package.js` | 一键打包脚本，自动化整个打包流程 |
| `electron-builder.config.js` | electron-builder 配置文件 |
| `package.json` | npm 脚本和依赖配置 |
| `build/entitlements.mac.plist` | macOS 权限配置 |
| `build/icon.icns` | 应用图标文件 |

### 开发和测试工具

| 文件路径 | 说明 |
|---------|------|
| `scripts/test-packaged-app.sh` | 测试打包后的应用，自动打开开发者工具 |
| `scripts/diagnose-wake-word.sh` | 诊断打包后应用的唤醒词问题 |
| `scripts/open-devtools.sh` | 打开打包后应用的开发者工具 |
| `scripts/generate-icon.js` | 生成 macOS 应用图标 (.icns) |
| `scripts/electron-dev.js` | 开发模式启动 Electron 应用 |

### 相关源代码文件

| 文件路径 | 打包相关功能 |
|---------|------------|
| `electron/main.ts` | 检测打包环境，管理后端进程 |
| `electron/backend-manager.ts` | 在打包环境中启动内嵌 JRE 和 JAR |
| `electron/preload.ts` | 提供安全的 API 桥接 |
| `vite.config.ts` | 构建前端资源 |

## 🛠️ 工作原理

1. **自动启动后端**：应用启动时，Electron 主进程会自动检测并启动内嵌的 Java 后端
2. **JRE 管理**：使用内嵌的 JRE 运行 Java 后端，无需用户安装 Java
3. **资源管理**：JAR 和 JRE 通过 `extraResources` 打包到应用的 Resources 目录
4. **进程管理**：应用退出时自动关闭 Java 后端进程

### 开发模式 vs 生产模式

**开发模式**：
- 使用系统安装的 Java
- JAR 从项目 `target/` 目录加载
- 前端从 Vite 开发服务器加载

**生产模式（打包后）**：
- 使用内嵌的 JRE
- JAR 从应用 Resources 目录加载
- 前端从打包的 `app.asar` 加载

## 🐛 调试打包后的应用

### 打开开发者工具

#### 方法 1: 使用测试脚本（推荐）

```bash
# 使用默认路径
./frontend/scripts/test-packaged-app.sh

# 或指定应用路径
./frontend/scripts/test-packaged-app.sh /path/to/Lavis.app
```

#### 方法 2: 使用诊断脚本

```bash
# 诊断应用结构和模型文件
./frontend/scripts/diagnose-wake-word.sh
```

#### 方法 3: 手动设置环境变量

```bash
export ELECTRON_DEVTOOLS=1
export OPEN_DEVTOOLS=1
open -a frontend/dist-electron/mac-arm64/Lavis.app
```

#### 方法 4: 快捷键

在应用运行时，使用快捷键：
- **macOS**: `Cmd+Alt+I`
- **Windows/Linux**: `Ctrl+Alt+I`

### 查看日志

在开发者工具的 **Console** 标签中，查找以下日志：

- `[Vosk] Loading model from: ...` - 模型加载开始
- `[Vosk] ✅ Model loaded successfully` - 模型加载成功
- `[Vosk] 🎤 Recognized: "..."` - 识别到的文本
- `[Vosk] ✅ Wake word matched!` - 唤醒词匹配成功

## ❓ 常见问题

### 1. 打包失败：JAR 文件未找到

**错误信息**：
```
JAR file not found at: ...
```

**解决方法**：
- 确保已运行 `mvn clean package` 构建后端
- 检查 `target/lavis-0.0.1-SNAPSHOT.jar` 是否存在

### 2. 打包失败：Maven 未找到

**解决方法**：
- 脚本会自动使用系统 Maven 或项目自带 `mvnw`
- 如需指定 Maven 路径，使用：`MAVEN_CMD=/path/to/mvn npm run package`
- 确认已安装 JDK 并且 `java -version` 可用
- 如提示 `JAVA_HOME` 未设置，可先执行：`export JAVA_HOME=$(/usr/libexec/java_home)`

### 3. JRE 未找到

**错误信息**：
```
Java executable not found at: ...
```

**解决方法**：
- 确保 `frontend/jre/mac-arm64/` 目录存在
- 检查 JRE 目录结构是否正确

### 4. 打包失败

**可能原因**：
- 缺少依赖：运行 `npm install` 安装依赖
- electron-builder 未安装：运行 `npm install -D electron-builder`
- 权限问题：确保有写入 `dist-electron` 目录的权限

### 5. 应用无法启动后端

**检查步骤**：
1. 查看应用日志（Console.app 或终端）
2. 确认 JAR 和 JRE 路径正确
3. 检查 JRE 是否有执行权限

### 6. 唤醒词不工作

**检查步骤**：
1. 模型文件是否从 asar 中解压（应在 `app.asar.unpacked/dist/models/`）
2. 使用诊断脚本检查：`./frontend/scripts/diagnose-wake-word.sh`
3. 检查 Console 日志中的模型加载信息

**常见问题**：

- **模型文件未找到（404 错误）**
  - 检查 `frontend/public/models/` 目录是否存在模型文件
  - 确认打包配置中 `asarUnpack` 包含 `dist/models/**/*.tar.gz`
  - 重新打包应用

- **模型加载失败**
  - 检查模型文件是否完整
  - 查看 Network 标签，确认模型文件请求的 URL
  - 检查文件权限

- **识别到文本但不匹配**
  - 检查唤醒词配置（默认是 "hi lavis"）
  - 查看识别到的文本，可能需要调整音近词映射
  - 尝试更清晰地发音

## ⚙️ 进阶配置

### 自定义应用图标

1. 准备图标文件（.icns 格式）
2. 放置到 `frontend/build/icon.icns`
3. electron-builder 会自动使用

或使用工具生成：

```bash
cd frontend
node scripts/generate-icon.js
```

### 代码签名

如需代码签名，在 `electron-builder.config.js` 中添加：

```javascript
mac: {
  identity: 'Developer ID Application: Your Name (TEAM_ID)',
  // ...
}
```

### 公证（Notarization）

如需公证，配置环境变量：

```bash
export APPLE_ID="your@email.com"
export APPLE_ID_PASSWORD="app-specific-password"
export APPLE_TEAM_ID="TEAM_ID"
```

## 📚 相关资源

- [Electron Builder 文档](https://www.electron.build/)
- [项目根目录 README](../README.md)
