package com.lavis.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
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
        sendToSession(session, Map.of(
            "type", "connected",
            "message", "Connected to Lavis Agent WebSocket",
            "sessionId", session.getId()
        ));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("🔌 WebSocket 连接关闭: {} ({})", session.getId(), status);
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
    private void sendToSession(WebSocketSession session, Map<String, Object> message) {
        if (session.isOpen()) {
            try {
                String json = objectMapper.writeValueAsString(message);
                session.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.error("发送 WebSocket 消息失败: {}", session.getId(), e);
            }
        }
    }

    /**
     * 获取当前连接数
     */
    public int getConnectionCount() {
        return sessions.size();
    }
}

