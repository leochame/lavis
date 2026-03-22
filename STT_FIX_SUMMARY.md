# STT 问题修复总结

## 🔍 问题诊断

**根本原因**: 前端的静音检测过于严格，将正常的语音录音误判为"全程静音"并丢弃。

### 问题表现
- 用户说话后，录音被前端丢弃
- 控制台显示: `⚠️ Full silence detected, discarding`
- 录音从未发送到后端 STT 服务
- 后端 STT 功能本身完全正常

## ✅ 已修复的问题

### 1. 降低全程静音检测阈值
**文件**: `frontend/src/hooks/useVoiceRecorder.ts`

**修改前**:
```typescript
if (energyInfo.avgAudioEnergy < 0.01 && energyInfo.samplesCount > 10) {
```

**修改后**:
```typescript
if (energyInfo.avgAudioEnergy < 0.001 && energyInfo.samplesCount > 10) {
```

**说明**: 将阈值从 0.01 降低到 0.001，减少误判

### 2. 调整实时静音检测阈值
**修改前**:
```typescript
const silenceThreshold = 0.02; // 静音阈值
```

**修改后**:
```typescript
const silenceThreshold = 0.015; // 静音阈值（降低以减少误判）
```

### 3. 添加调试日志
- 添加音频能量实时监控
- 添加静音检测详细日志
- 方便后续调试和优化

## 🧪 测试步骤

1. **刷新浏览器页面** (Cmd+R 或 F5)
2. **打开开发者工具** (F12)
3. **在 Console 中启用调试**:
   ```javascript
   window.sttDebug.enable()
   ```
4. **测试录音**:
   - 说 "hi lavis" 唤醒
   - 说一些话
   - 观察 Console 输出

### 预期结果
- 应该看到 `[VoiceRecorder] Audio level:` 日志
- 应该看到 `[VoiceChat]` 相关日志
- 录音应该被发送到后端
- 应该收到 STT 转录结果

## 📊 调试工具使用

已添加全局调试工具 `window.sttDebug`，可用命令：

```javascript
// 启用详细调试
window.sttDebug.enable()

// 测试 STT 端点
await window.sttDebug.testEndpoint()

// 检查配置
window.sttDebug.checkConfig()

// 查看日志
window.sttDebug.getLogs()

// 导出日志
window.sttDebug.exportLogs()

// 禁用调试
window.sttDebug.disable()
```

## 🔧 如果问题仍然存在

### 检查清单
1. ✅ 后端是否运行: `curl http://localhost:18765/api/agent/status`
2. ✅ 前端是否运行: 访问 http://localhost:5173
3. ✅ 麦克风权限: 浏览器是否授予麦克风权限
4. ✅ 音频能量: 查看 Console 中的 `[VoiceRecorder] Audio level:` 日志

### 进一步调整
如果录音仍然被丢弃，可以进一步降低阈值：

**文件**: `frontend/src/hooks/useVoiceRecorder.ts`

```typescript
// 第 119 行 - 实时检测阈值
const silenceThreshold = 0.01; // 从 0.015 降低到 0.01

// 第 273 行 - 全程静音检测阈值
if (energyInfo.avgAudioEnergy < 0.0005 && energyInfo.samplesCount > 10) {
```

### 临时禁用静音检测
如果需要完全禁用静音检测（用于测试）：

```typescript
// 第 265 行 - 注释掉全程静音检测
// if (energyInfo.avgAudioEnergy < 0.001 && energyInfo.samplesCount > 10) {
//   console.warn('⚠️ Full silence detected, discarding');
//   setIsTooShort(true);
//   setAudioBlob(null);
//   setAudioDuration(0);
// }
```

## 📝 后续优化建议

1. **自适应阈值**: 根据环境噪音自动调整静音检测阈值
2. **用户配置**: 允许用户在设置中调整灵敏度
3. **音频预处理**: 添加降噪和增益控制
4. **更好的反馈**: 显示实时音频能量指示器

## 🎯 验证后端 STT 正常

已通过测试验证后端 STT 功能完全正常：
- ✅ 端点可访问
- ✅ 音频转录成功
- ✅ 响应时间合理（约 8-11 秒）
- ✅ 返回正确的 JSON 格式

问题完全在前端的静音检测逻辑。
