package com.lavis.controller;

import com.lavis.service.config.DynamicApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * API Key 配置控制器
 *
 * 提供 REST 端点用于管理运行时 API Key：
 * - POST /api/config/api-key: 设置 API Key
 * - GET /api/config/api-key/status: 检查 API Key 状态
 * - DELETE /api/config/api-key: 清除 API Key
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ApiKeyController {

    private final DynamicApiKeyService dynamicApiKeyService;

    /**
     * 设置 API Key
     */
    @PostMapping("/api-key")
    public ResponseEntity<Map<String, Object>> setApiKey(@RequestBody Map<String, String> request) {
        String apiKey = request.get("apiKey");

        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "API Key is required"
            ));
        }

        try {
            dynamicApiKeyService.setApiKey(apiKey);
            log.info("✅ API Key set via REST endpoint");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "API Key configured successfully"
            ));
        } catch (Exception e) {
            log.error("❌ Failed to set API Key", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * 获取 API Key 配置状态
     */
    @GetMapping("/api-key/status")
    public ResponseEntity<Map<String, Object>> getApiKeyStatus() {
        boolean configured = dynamicApiKeyService.isConfigured();

        return ResponseEntity.ok(Map.of(
                "configured", configured
        ));
    }

    /**
     * 清除 API Key
     */
    @DeleteMapping("/api-key")
    public ResponseEntity<Map<String, Object>> clearApiKey() {
        try {
            dynamicApiKeyService.clearApiKey();
            log.info("✅ API Key cleared via REST endpoint");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "API Key cleared successfully"
            ));
        } catch (Exception e) {
            log.error("❌ Failed to clear API Key", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }
}
