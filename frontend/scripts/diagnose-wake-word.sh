#!/bin/bash
# 诊断打包后应用的唤醒词问题
# 使用方法: ./scripts/diagnose-wake-word.sh [app-path]

# 默认应用路径
DEFAULT_APP_PATH="frontend/dist-electron/mac-arm64/Lavis.app"

APP_PATH="${1:-$DEFAULT_APP_PATH}"

echo "🔍 诊断打包后应用的唤醒词问题"
echo "=================================="
echo ""

# 检查应用是否存在
if [ ! -d "$APP_PATH" ]; then
  echo "❌ 应用不存在: $APP_PATH"
  echo ""
  echo "请先打包应用:"
  echo "  cd frontend && npm run package"
  exit 1
fi

# 检查模型文件是否存在
RESOURCES_PATH="$APP_PATH/Contents/Resources"
ASAR_PATH="$RESOURCES_PATH/app.asar"
MODEL_PATH_IN_ASAR="$ASAR_PATH/dist/models/vosk-model-small-en-us-0.15.tar.gz"

echo "📦 检查应用结构..."
echo "  应用路径: $APP_PATH"
echo "  Resources: $RESOURCES_PATH"
echo ""

# 检查 asar 文件
if [ -f "$ASAR_PATH" ]; then
  echo "✅ app.asar 存在"
  
  # 尝试列出 asar 中的文件（需要 asar 工具）
  if command -v asar &> /dev/null; then
    echo ""
    echo "📂 app.asar 内容:"
    asar list "$ASAR_PATH" | grep -E "(models|vosk)" | head -20
    echo ""
    
    # 检查模型文件
    if asar list "$ASAR_PATH" | grep -q "models/vosk-model-small-en-us-0.15.tar.gz"; then
      echo "✅ 模型文件在 app.asar 中"
    else
      echo "❌ 模型文件不在 app.asar 中"
      echo "   请检查 public/models/ 目录是否存在模型文件"
    fi
  else
    echo "⚠️  未安装 asar 工具，无法检查 asar 内容"
    echo "   安装方法: npm install -g asar"
  fi
else
  echo "❌ app.asar 不存在"
fi

echo ""
echo "🔧 启动应用并打开开发者工具..."
echo ""

# 设置环境变量
export ELECTRON_DEVTOOLS=1
export OPEN_DEVTOOLS=1

# 启动应用
if [[ "$OSTYPE" == "darwin"* ]]; then
  open -a "$APP_PATH"
  
  echo "✅ 应用已启动"
  echo ""
  echo "📋 诊断步骤:"
  echo "  1. 在开发者工具的 Console 中查看 [Vosk] 日志"
  echo "  2. 检查模型加载是否成功"
  echo "  3. 查看是否有路径错误（404 或 file not found）"
  echo "  4. 检查 Network 标签，查看模型文件请求"
  echo "  5. 测试唤醒词检测（说 'hi lavis'）"
  echo ""
  echo "💡 常见问题:"
  echo "  - 如果看到 '404' 或 'not found'，可能是路径解析问题"
  echo "  - 如果模型加载失败，可能需要将模型文件从 asar 中解压"
  echo "  - 检查 Console 中的完整错误信息"
else
  echo "⚠️  此脚本目前仅支持 macOS"
fi

