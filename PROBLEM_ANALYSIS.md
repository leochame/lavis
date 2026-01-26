# 前端管理问题核心原因分析

## 问题1: Lavis 不能二次唤醒

### 核心原因

1. **状态恢复逻辑缺陷** (`useGlobalVoice.ts`)
   - 在 `playNextInQueue` 函数中（第236-285行），当音频队列播放完毕时，只有当 `receivedLastRef.current === true` 时才会调用 `updateState('idle')`
   - 如果 `receivedLastRef.current` 没有正确设置（例如音频播放过程中出错，或者最后一个片段没有正确标记为 `isLast`），状态可能卡在 `'speaking'` 或 `'awaiting_audio'`，导致唤醒词监听无法恢复

2. **requestId 匹配问题** (`useGlobalVoice.ts:289-311`)
   - `handleTtsAudio` 函数中，如果 `currentRequestId` 为 null 或不匹配，会直接返回，不处理音频
   - 如果 `currentRequestId` 没有正确设置或清理，后续的音频片段可能被忽略

3. **状态清理不完整** (`useGlobalVoice.ts:347-401`)
   - 在 `handleVoiceChat` 中，当 `response.audio_pending === false` 时，直接调用 `updateState('idle')`，但没有清理 `currentRequestId` 和 `receivedLastRef.current`
   - 这可能导致下次唤醒时状态不一致

4. **唤醒词监听条件** (`useGlobalVoice.ts:431`)
   - 唤醒词监听只在 `voiceState === 'idle' || voiceState === 'error'` 时启用
   - 如果状态卡在 `'speaking'` 或 `'awaiting_audio'`，唤醒词监听不会恢复

### 修复建议

1. 在 `playNextInQueue` 中添加超时机制，如果队列长时间为空且没有新片段，强制清理状态并回到 `'idle'`
2. 在 `handleVoiceChat` 中，无论 `audio_pending` 是否为 true，都要清理 `currentRequestId` 和 `receivedLastRef.current`
3. 在 `handleTtsAudio` 中，如果 `currentRequestId` 为 null，应该允许处理（可能是新的请求）
4. 添加状态恢复机制，定期检查状态是否卡住，如果卡住则强制恢复

---

## 问题2: 前端后端 WebSocket 通信没做好，后端还没停止，前端按钮颜色就变为初始颜色

### 核心原因

1. **工作流状态更新不完整** (`useWebSocket.ts:269-282`)
   - 当收到 `plan_completed` 事件时，只更新 `status: 'completed'`，但没有重置为 `'idle'`
   - 前端 `isWorking` 计算（`App.tsx:259-275`）只检查 `'executing'` 和 `'planning'` 状态，不检查 `'completed'` 状态
   - 这导致后端完成工作后，前端可能仍然认为后端在工作，或者状态没有正确更新

2. **状态同步问题** (`App.tsx:259-275`)
   - `isWorking` 的计算基于 `wsWorkflow.status` 和 `status?.orchestrator_state`
   - 如果后端没有发送 `plan_completed` 事件，或者前端没有正确处理，`wsWorkflow.status` 可能仍然保持为 `'executing'` 或 `'planning'`
   - 另外，`status?.orchestrator_state` 来自心跳接口，可能滞后于实际状态

3. **后端状态更新时机** (`TaskOrchestrator.java`)
   - 后端在完成计划时发送 `plan_completed` 事件（第319行），但可能在某些异常情况下没有发送
   - 如果执行过程中出错或被中断，可能没有正确发送状态更新事件

### 修复建议

1. 在 `useWebSocket.ts` 中，当收到 `plan_completed` 事件时，应该将 `status` 重置为 `'idle'`，而不是保持为 `'completed'`
2. 在 `App.tsx` 中，`isWorking` 的计算应该排除 `'completed'` 状态
3. 添加状态超时机制，如果长时间没有收到状态更新，自动重置为 `'idle'`
4. 在后端，确保在所有执行路径（包括异常情况）都发送状态更新事件

---

## 问题3: 后端返回的音频不能正常播放

### 核心原因

1. **音频格式检测不准确** (`useGlobalVoice.ts:257-259`)
   - 当前使用简单的字符串前缀检测：`item.data.startsWith('UklGR') || item.data.startsWith('Ukl')`
   - 这种方法不够可靠，因为：
     - Base64 编码的 WAV 文件可能以不同的前缀开始（取决于 WAV 文件头）
     - MP3 文件的前缀可能不是 `'Ukl'`，导致误判
     - 如果音频格式不是 WAV 或 MP3，可能无法正确识别

2. **Base64 数据格式问题**
   - 后端返回的音频数据是 Base64 编码的字符串，但可能没有包含正确的 MIME 类型信息
   - 前端直接使用 `data:audio/wav;base64,` 或 `data:audio/mp3;base64,`，但如果实际格式不匹配，浏览器可能无法播放

3. **音频播放错误处理不完善** (`useGlobalVoice.ts:268-284`)
   - 当音频播放失败时，只是继续播放下一个，但没有记录错误或通知用户
   - 如果所有音频片段都播放失败，状态可能卡在 `'speaking'` 或 `'awaiting_audio'`

4. **后端音频格式不统一** (`AsyncTtsService.java`)
   - 后端使用不同的 TTS 模型（DashScope、OpenAI 等），可能返回不同格式的音频
   - 后端没有在 WebSocket 消息中包含音频格式信息，前端只能猜测

### 修复建议

1. 改进音频格式检测：
   - 使用更可靠的方法检测音频格式（例如检查 Base64 解码后的文件头）
   - 或者，后端在 WebSocket 消息中包含音频格式信息（`format: 'wav'` 或 `format: 'mp3'`）

2. 添加音频格式信息到 WebSocket 消息：
   - 在 `AsyncTtsService.java` 的 `sendTtsAudio` 方法中，添加 `format` 字段
   - 前端根据 `format` 字段设置正确的 MIME 类型

3. 改进错误处理：
   - 当音频播放失败时，记录错误并通知用户
   - 如果连续多个音频片段播放失败，应该停止播放并回到 `'idle'` 状态

4. 添加音频格式验证：
   - 在播放前验证 Base64 数据是否有效
   - 如果数据无效，跳过该片段并记录错误

---

## 总结

三个问题的根本原因都是**状态管理不完善**和**错误处理不充分**：

1. **状态恢复机制缺失**：当异常情况发生时（如音频播放失败、WebSocket 消息丢失），状态可能卡住，无法自动恢复
2. **状态同步不一致**：前端和后端的状态更新不同步，导致 UI 显示错误
3. **错误处理不完善**：当错误发生时，没有适当的恢复机制，导致状态卡住

### 修复优先级

1. **高优先级**：修复状态恢复机制，确保状态能够正确回到 `'idle'`
2. **中优先级**：改进 WebSocket 状态同步，确保前端和后端状态一致
3. **低优先级**：改进音频格式检测和错误处理，提高播放成功率


