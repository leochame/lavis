#!/bin/bash
# 测试打包后的应用，并自动打开开发者工具
# 使用方法: ./scripts/test-packaged-app.sh [app-path]

# 默认应用路径
DEFAULT_APP_PATH="frontend/dist-electron/mac-arm64/Lavis.app"

# 如果提供了路径，使用提供的路径；否则使用默认路径
APP_PATH="${1:-$DEFAULT_APP_PATH}"

# 检查应用是否存在
if [ ! -d "$APP_PATH" ]; then
  echo "❌ 应用不存在: $APP_PATH"
  echo ""
  echo "请先打包应用:"
  echo "  cd frontend && npm run package"
  echo ""
  echo "使用方法:"
  echo "  ./scripts/test-packaged-app.sh [app-path]"
  exit 1
fi

echo "🧪 测试打包后的应用"
echo "📦 应用路径: $APP_PATH"
echo ""

# 设置环境变量
# ELECTRON_DEVTOOLS=1 会触发主进程自动打开开发者工具
export ELECTRON_DEVTOOLS=1
export OPEN_DEVTOOLS=1

# 在 macOS 上打开应用
if [[ "$OSTYPE" == "darwin"* ]]; then
  echo "🚀 启动应用（开发者工具会自动打开）..."
  open -a "$APP_PATH"
  
  echo ""
  echo "✅ 应用已启动"
  echo ""
  echo "📋 测试清单:"
  echo "  1. 检查 Console 中的 [Vosk] 日志"
  echo "  2. 查看模型是否成功加载"
  echo "  3. 测试唤醒词检测是否工作"
  echo "  4. 检查是否有路径错误"
  echo ""
  echo "💡 提示:"
  echo "  - 如果开发者工具没有自动打开，使用快捷键: Cmd+Alt+I"
  echo "  - 查看 Console 标签中的日志输出"
  echo "  - 检查 Network 标签，查看模型文件是否成功加载"
else
  echo "⚠️  此脚本目前仅支持 macOS"
  echo "   在其他系统上，请手动设置环境变量并启动应用:"
  echo "   export ELECTRON_DEVTOOLS=1"
  echo "   ./$APP_PATH"
fi

