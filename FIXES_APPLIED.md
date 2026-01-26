# WebSocket 消息格式修复

## 问题分析

从日志中发现两个关键错误：

### 错误1: TTS 音频数据丢失
```
📩 [WS] 收到原始消息: {"requestId":"...","index":4,"isLast":true,"type":"tts_audio","data":"UklGRiSEAwBXQVZFZm10IBAAAAABAAEAwF0AAIC7AAACABAAZGF0YQCEAwD+//3//f/+//8AAAAAAAABAAIAAgADAAMAAwADAAMAAgABAAEAAgACAAAAAAABAAEAAgABAAIAAQAAAAAAAAAAAAAAAAD//wAAAAAAAP//AAAAAP///v/+/////////////v///////v/+//7//v////7//f/+//7//f/9//7//v////7//v/+///////+//3//v/+//7//v/+//7//v/+//7//f/9//7//v/+//3//v/+//7//v/+//7//v/+//7//v///////v/9//3//v/+//7//v/+//7//f/8//3//v/+///////+//7//v/+//3//v/+/...
🔍 [WS] 处理消息: tts_audio data length: 0
```

**原因**：后端发送的消息格式与前端期望的格式不匹配
- 后端发送：`{type: "tts_audio", requestId: "...", data: "...", index: 0, isLast: false}`
- 前端期望：`{type: "tts_audio", data: {requestId: "...", data: "...", index: 0, isLast: false}}`

### 错误2: connected 消息的 sessionId 解析失败
```
✅ [WS] 收到 connected 消息: [object Object]
⚠️ [WS] connected 消息中未找到 sessionId，data: undefined
```

**原因**：后端发送的 `sessionId` 在顶层，但前端从 `data.sessionId` 读取
- 后端发送：`{type: "connected", message: "...", sessionId: "..."}`
- 前端期望：`{type: "connected", data: {message: "...", sessionId: "..."}}`

## 修复内容

### 1. 修复 TTS 消息格式 (`AsyncTtsService.java`)

**修复前**：
```java
private boolean sendTtsAudio(String sessionId, String requestId, String audioBase64, int index, boolean isLast) {
    return webSocketHandler.sendToSessionById(sessionId, Map.of(
        "type", "tts_audio",
        "requestId", requestId,
        "data", audioBase64,
        "index", index,
        "isLast", isLast
    ));
}
```

**修复后**：
```java
private boolean sendTtsAudio(String sessionId, String requestId, String audioBase64, int index, boolean isLast) {
    Map<String, Object> data = new java.util.HashMap<>();
    data.put("requestId", requestId);
    data.put("data", audioBase64);
    data.put("index", index);
    data.put("isLast", isLast);
    
    Map<String, Object> message = new java.util.HashMap<>();
    message.put("type", "tts_audio");
    message.put("data", data);
    message.put("timestamp", System.currentTimeMillis());
    
    return webSocketHandler.sendToSessionById(sessionId, message);
}
```

同样修复了 `sendTtsSkip` 和 `sendTtsError` 方法。

### 2. 修复 connected 消息格式 (`AgentWebSocketHandler.java`)

**修复前**：
```java
sendToSession(session, Map.of(
    "type", "connected",
    "message", "Connected to Lavis Agent WebSocket",
    "sessionId", session.getId()
));
```

**修复后**：
```java
Map<String, Object> data = new HashMap<>();
data.put("message", "Connected to Lavis Agent WebSocket");
data.put("sessionId", session.getId());

Map<String, Object> message = new HashMap<>();
message.put("type", "connected");
message.put("data", data);
message.put("timestamp", System.currentTimeMillis());

sendToSession(session, message);
```

## 修复后的消息格式

所有 WebSocket 消息现在都遵循统一的格式：
```json
{
  "type": "消息类型",
  "data": {
    // 消息数据
  },
  "timestamp": 1234567890
}
```

这与其他工作流事件（如 `plan_created`、`step_started` 等）的格式保持一致。

## 预期效果

1. ✅ TTS 音频数据能够正确解析和播放
2. ✅ `sessionId` 能够正确提取和保存
3. ✅ 所有 WebSocket 消息格式统一，便于维护

## 测试建议

1. 测试 TTS 音频播放是否正常
2. 测试 WebSocket 连接后 `sessionId` 是否正确保存
3. 检查浏览器控制台，确认不再出现 `data length: 0` 的警告


