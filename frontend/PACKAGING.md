# Lavis 打包指南

## 快速开始

### 1. 准备图标文件

在 `frontend/build/` 目录下放置以下图标文件：

| 文件名 | 格式 | 用途 | 尺寸要求 |
|--------|------|------|----------|
| `icon.icns` | ICNS | macOS 应用图标 | 包含多尺寸 (16-1024px) |
| `icon.ico` | ICO | Windows 应用图标 | 包含多尺寸 (16-256px) |
| `icon.png` | PNG | Linux 应用图标 | 512x512 或 1024x1024 |

**生成图标的方法：**

1. 准备一张 1024x1024 的 PNG 图片
2. 使用在线工具转换：
   - macOS: https://cloudconvert.com/png-to-icns
   - Windows: https://cloudconvert.com/png-to-ico
3. 或使用命令行工具：
   ```bash
   # macOS (需要安装 iconutil)
   iconutil -c icns icon.iconset

   # 使用 ImageMagick
   convert icon.png -define icon:auto-resize=256,128,64,48,32,16 icon.ico
   ```

### 2. 配置 API Keys

在打包前，确保后端的 `application.properties` 中配置了必要的 API Keys：

```properties
# LLM API
langchain4j.open-ai.chat-model.api-key=your-api-key

# 语音服务 (可选)
lavis.tts.dashscope.api-key=your-dashscope-key
lavis.stt.gemini.api-key=your-gemini-key
```

### 3. 一键打包

```bash
cd frontend

# 打包当前平台
npm run package

# 或指定平台
npm run package:mac     # macOS (arm64)
npm run package:win     # Windows (x64)
npm run package:linux   # Linux (x64)
```

### 4. 打包输出

打包完成后，安装包在 `frontend/dist-electron/` 目录：

| 平台 | 文件 | 大小 |
|------|------|------|
| macOS | `Lavis-1.0.0-arm64.dmg` | ~250MB |
| Windows | `Lavis Setup 1.0.0.exe` | ~270MB |
| Linux | `Lavis-1.0.0-x64.AppImage` | ~250MB |

---

## 分发给用户

### macOS 用户

1. 发送 `.dmg` 文件给用户
2. 用户双击打开 DMG
3. 将 Lavis 图标拖到 Applications 文件夹
4. 首次运行时，右键点击 → 打开（绕过 Gatekeeper）

**注意：** 未签名的应用会被 macOS 阻止。如需正式分发，需要：
- Apple Developer 账号 ($99/年)
- 代码签名证书
- 公证 (Notarization)

### Windows 用户

1. 发送 `.exe` 安装包给用户
2. 用户双击运行安装程序
3. 按提示完成安装
4. 从桌面快捷方式或开始菜单启动

**注意：** 未签名的应用会触发 SmartScreen 警告。如需正式分发，需要：
- EV 代码签名证书 (~$300/年)

### Linux 用户

**AppImage 方式：**
```bash
chmod +x Lavis-1.0.0-x64.AppImage
./Lavis-1.0.0-x64.AppImage
```

**DEB 方式 (Debian/Ubuntu)：**
```bash
sudo dpkg -i lavis_1.0.0_amd64.deb
```

---

## 用户首次使用

1. **启动应用** - 双击图标
2. **等待初始化** - 后端服务自动启动（约 5-10 秒）
3. **授权麦克风** - 首次使用语音功能时会请求权限
4. **开始使用** - 说 "Hi Lavis" 或点击胶囊开始对话

---

## 故障排除

### 应用无法启动

1. 检查系统要求：
   - macOS 10.15+
   - Windows 10+
   - Linux (glibc 2.17+)

2. 查看日志：
   - macOS: `~/Library/Logs/Lavis/`
   - Windows: `%APPDATA%\Lavis\logs\`
   - Linux: `~/.config/Lavis/logs/`

### 后端启动失败

1. 检查端口 8080 是否被占用
2. 确保有足够内存 (至少 512MB 可用)

### 语音功能不工作

1. 检查麦克风权限
2. 确保 API Keys 已正确配置
