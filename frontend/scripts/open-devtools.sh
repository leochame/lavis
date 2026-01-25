#!/bin/bash
# 通过命令行打开打包后的 Electron 应用的开发者工具
# 使用方法: ./scripts/open-devtools.sh [app-path]

# 默认应用路径
DEFAULT_APP_PATH="frontend/dist-electron/mac-arm64/Lavis.app"

# 如果提供了路径，使用提供的路径；否则使用默认路径
APP_PATH="${1:-$DEFAULT_APP_PATH}"

# 检查应用是否存在
if [ ! -d "$APP_PATH" ]; then
  echo "❌ 应用不存在: $APP_PATH"
  echo ""
  echo "使用方法:"
  echo "  ./scripts/open-devtools.sh [app-path]"
  echo ""
  echo "示例:"
  echo "  ./scripts/open-devtools.sh"
  echo "  ./scripts/open-devtools.sh frontend/dist-electron/mac-arm64/Lavis.app"
  exit 1
fi

echo "🔧 正在打开开发者工具..."
echo "📦 应用路径: $APP_PATH"
echo ""

# 设置环境变量并启动应用
# ELECTRON_DEVTOOLS=1 会触发主进程自动打开开发者工具
export ELECTRON_DEVTOOLS=1
export OPEN_DEVTOOLS=1

# 在 macOS 上打开应用
if [[ "$OSTYPE" == "darwin"* ]]; then
  open -a "$APP_PATH"
  echo "✅ 应用已启动，开发者工具应该会自动打开"
  echo ""
  echo "💡 提示: 如果开发者工具没有自动打开，可以使用快捷键:"
  echo "   macOS: Cmd+Alt+I"
  echo "   Windows/Linux: Ctrl+Alt+I"
else
  echo "⚠️  此脚本目前仅支持 macOS"
  echo "   在其他系统上，请手动设置环境变量并启动应用:"
  echo "   export ELECTRON_DEVTOOLS=1"
  echo "   ./$APP_PATH"
fi

