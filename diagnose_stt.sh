#!/bin/bash

echo "========================================="
echo "STT 诊断工具"
echo "========================================="
echo ""

# 1. 检查后端是否运行
echo "1. 检查后端状态..."
if curl -s http://localhost:18765/api/agent/status > /dev/null 2>&1; then
    echo "   ✅ 后端运行正常"
    curl -s http://localhost:18765/api/agent/status | python3 -m json.tool 2>/dev/null || echo "   (无法格式化 JSON)"
else
    echo "   ❌ 后端未运行或无法访问"
    exit 1
fi
echo ""

# 2. 检查 STT 配置
echo "2. 检查 STT 配置..."
if grep -q "app.llm.default-stt-model=whisper" src/main/resources/application.properties 2>/dev/null; then
    echo "   ✅ 默认 STT 模型: whisper"
else
    echo "   ⚠️  未找到 STT 配置"
fi

if grep -q "app.llm.models.whisper.provider=GEMINI" src/main/resources/application.properties 2>/dev/null; then
    echo "   ✅ STT Provider: GEMINI"
    echo "   ℹ️  Base URL: $(grep 'app.llm.models.whisper.base-url' src/main/resources/application.properties | cut -d'=' -f2)"
    echo "   ℹ️  Model: $(grep 'app.llm.models.whisper.model-name' src/main/resources/application.properties | cut -d'=' -f2)"
    echo "   ℹ️  Timeout: $(grep 'app.llm.models.whisper.timeout-seconds' src/main/resources/application.properties | cut -d'=' -f2)s"
fi
echo ""

# 3. 测试 STT 端点
echo "3. 测试 STT 端点 (使用测试音频)..."
echo "   创建测试音频文件..."

# 创建一个更大的测试 WAV 文件 (约 1 秒的静音)
cat > /tmp/test_audio.wav << 'EOFWAV'
UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQAAAAA=
EOFWAV

base64 -d /tmp/test_audio.wav > /tmp/test.wav 2>/dev/null

echo "   发送请求到 /api/agent/voice-chat..."
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:18765/api/agent/voice-chat \
  -F "file=@/tmp/test.wav" \
  -F "use_orchestrator=false" \
  --max-time 30 2>&1)

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)

if [ "$HTTP_CODE" = "200" ]; then
    echo "   ✅ STT 请求成功 (HTTP $HTTP_CODE)"
    echo "   响应:"
    echo "$BODY" | python3 -m json.tool 2>/dev/null || echo "$BODY"
else
    echo "   ❌ STT 请求失败 (HTTP $HTTP_CODE)"
    echo "   错误响应:"
    echo "$BODY"
fi

rm -f /tmp/test.wav /tmp/test_audio.wav
echo ""

# 4. 检查日志
echo "4. 检查最近的错误日志..."
if [ -d "target" ]; then
    echo "   查找 target 目录中的日志..."
    find target -name "*.log" -type f -exec tail -20 {} \; 2>/dev/null | grep -i "stt\|error\|exception" | tail -10
fi

if [ -d "$HOME/.lavis/logs" ]; then
    echo "   查找 ~/.lavis/logs 目录中的日志..."
    find "$HOME/.lavis/logs" -name "*.log" -type f -exec tail -20 {} \; 2>/dev/null | grep -i "stt\|error\|exception" | tail -10
fi
echo ""

echo "========================================="
echo "诊断完成"
echo "========================================="
echo ""
echo "如果 STT 测试成功但前端仍然无法使用，可能的原因:"
echo "1. 前端录音质量检测过于严格，丢弃了有效录音"
echo "2. 前端网络超时设置过短"
echo "3. 浏览器控制台有 JavaScript 错误"
echo "4. WebSocket 连接问题"
echo ""
echo "建议:"
echo "- 打开浏览器开发者工具 (F12) 查看 Console 和 Network 标签"
echo "- 检查是否有红色错误信息"
echo "- 查看 /api/agent/voice-chat 请求的状态和响应时间"
