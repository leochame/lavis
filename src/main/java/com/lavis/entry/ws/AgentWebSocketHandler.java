package com.lavis.entry.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent WebSocket 处理器
 * 管理前端连接，发送实时工作流状态
 */
@Slf4j
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("🔌 WebSocket 连接建立: {}", session.getId());
        
        // 发送欢迎消息
        Map<String, Object> data = new HashMap<>();
        data.put("message", "Connected to Lavis Agent WebSocket");
        data.put("sessionId", session.getId());
        
        Map<String, Object> message = new HashMap<>();
        message.put("type", "connected");
        message.put("data", data);
        message.put("timestamp", System.currentTimeMillis());
        
        sendToSession(session, message);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("🔌 WebSocket 连接关闭: {} ({})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session.getId());
        if (isExpectedDisconnect(exception)) {
            log.warn("WebSocket 传输已断开: {} ({})", session.getId(), exception.getClass().getSimpleName());
        } else {
            log.error("WebSocket 传输异常: {}", session.getId(), exception);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            String payload = message.getPayload();
            log.debug("📩 收到消息: {}", payload);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            String type = (String) data.get("type");
            
            switch (type) {
                case "ping" -> sendToSession(session, Map.of("type", "pong"));
                case "subscribe" -> log.info("📢 客户端订阅工作流更新: {}", session.getId());
                default -> log.warn("未知消息类型: {}", type);
            }
        } catch (Exception e) {
            log.error("处理 WebSocket 消息失败", e);
        }
    }

    /**
     * 广播消息给所有连接的客户端
     */
    public void broadcast(Map<String, Object> message) {
        sessions.values().forEach(session -> sendToSession(session, message));
    }

    /**
     * 发送消息到指定 Session
     */
    private boolean sendToSession(WebSocketSession session, Map<String, Object> message) {
        if (!session.isOpen()) {
            sessions.remove(session.getId());
            log.debug("跳过已关闭的 WebSocket session: {}", session.getId());
            return false;
        }

        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
            return true;
        } catch (IOException e) {
            sessions.remove(session.getId());
            if (isExpectedDisconnect(e)) {
                log.warn("WebSocket 客户端已断开，停止发送: {}", session.getId());
            } else {
                log.error("发送 WebSocket 消息失败: {}", session.getId(), e);
            }
            return false;
        }
    }

    /**
     * 根据 Session ID 发送消息
     * 用于异步 TTS 推送场景
     *
     * @param sessionId WebSocket Session ID
     * @param message 消息内容
     * @return true 如果发送成功，false 如果 session 不存在或已经关闭
     */
    public boolean sendToSessionById(String sessionId, Map<String, Object> message) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            log.warn("WebSocket session not found: {}", sessionId);
            return false;
        }
        if (!session.isOpen()) {
            log.warn("WebSocket session is closed: {}", sessionId);
            sessions.remove(sessionId);
            return false;
        }
        return sendToSession(session, message);
    }

    /**
     * 获取第一个可用的 Session ID
     * 用于单客户端场景（如语音交互）
     *
     * @return Session ID，如果没有连接则返回 null
     */
    public String getFirstSessionId() {
        return sessions.keySet().stream().findFirst().orElse(null);
    }

    /**
     * 检查指定 Session 是否存在且打开
     */
    public boolean isSessionActive(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        return session != null && session.isOpen();
    }

    /**
     * 获取当前连接数
     */
    public int getConnectionCount() {
        return sessions.size();
    }

    private boolean isExpectedDisconnect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof EOFException || current instanceof ClosedChannelException) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("broken pipe")
                        || lower.contains("connection reset")
                        || lower.contains("closed")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
