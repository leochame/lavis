package com.lavis.entry.http;

import com.lavis.entry.config.llm.LlmProperties;
import com.lavis.entry.config.llm.ModelConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * API 配置状态控制器（只读）
 *
 * 提供 REST 端点用于查询后端配置状态：
 * - GET /api/config/api-key/status: 检查配置状态
 * - POST/DELETE 端点保留为兼容接口，但已禁用前端下发密钥
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ApiKeyController {

    private final LlmProperties llmProperties;

    /**
     * 兼容端点：前端运行时下发密钥已禁用。
     */
    @PostMapping("/api-key")
    public ResponseEntity<Map<String, Object>> setApiConfig(@RequestBody(required = false) Map<String, String> ignored) {
        log.warn("🚫 Rejected runtime API config update from frontend. Use .env/application.properties instead.");
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "success", false,
                "error", "Runtime API config update is disabled. Configure keys in .env and restart backend."
        ));
    }

    /**
     * 获取 API 配置状态
     *
     * 返回：
     * {
     *   "configured": true/false,           // chat/stt/tts 默认模型均已配置
     *   "mode": "official" | "proxy",       // 主聊天模型的 base-url 推断
     *   "baseUrl": "https://..." | null,    // 主聊天模型的 base-url
     *   "source": "env",                    // 配置来源：本地环境/配置文件
     *   "readOnly": true
     * }
     */
    @GetMapping("/api-key/status")
    public ResponseEntity<Map<String, Object>> getApiKeyStatus() {
        String chatAlias = llmProperties.getDefaultModel();
        String sttAlias = llmProperties.getDefaultSttModel();
        String ttsAlias = llmProperties.getDefaultTtsModel();

        ModelConfig chatConfig = llmProperties.getModelConfig(chatAlias);
        ModelConfig sttConfig = llmProperties.getModelConfig(sttAlias);
        ModelConfig ttsConfig = llmProperties.getModelConfig(ttsAlias);

        boolean chatConfigured = isModelConfigured(chatConfig);
        boolean sttConfigured = isModelConfigured(sttConfig);
        boolean ttsConfigured = isModelConfigured(ttsConfig);
        boolean configured = chatConfigured && sttConfigured && ttsConfigured;

        String baseUrl = (chatConfig != null && hasText(chatConfig.getBaseUrl()))
                ? chatConfig.getBaseUrl().trim()
                : null;
        String mode = hasText(baseUrl) ? "proxy" : "official";

        Map<String, Object> response = new HashMap<>();
        response.put("configured", configured);
        response.put("mode", mode);
        response.put("baseUrl", baseUrl);
        response.put("source", "env");
        response.put("readOnly", true);
        response.put("chatConfigured", chatConfigured);
        response.put("sttConfigured", sttConfigured);
        response.put("ttsConfigured", ttsConfigured);

        return ResponseEntity.ok(response);
    }

    /**
     * 兼容端点：前端运行时清除密钥已禁用。
     */
    @DeleteMapping("/api-key")
    public ResponseEntity<Map<String, Object>> clearApiConfig() {
        log.warn("🚫 Rejected runtime API config clear from frontend. Use .env/application.properties instead.");
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "success", false,
                "error", "Runtime API config clear is disabled. Edit .env and restart backend."
        ));
    }

    private boolean isModelConfigured(ModelConfig config) {
        return config != null && hasText(config.getApiKey()) && hasText(config.getModelName());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
