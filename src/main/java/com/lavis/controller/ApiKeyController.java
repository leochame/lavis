package com.lavis.controller;

import com.lavis.service.config.DynamicApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * API 配置控制器
 *
 * 提供 REST 端点用于管理运行时 API 配置：
 * - POST /api/config/api-key: 设置 API Key 和 Base URL
 * - GET /api/config/api-key/status: 检查配置状态
 * - DELETE /api/config/api-key: 清除配置
 *
 * 支持两种模式：
 * 1. Gemini 官方 - 只设置 apiKey，不设置 baseUrl
 * 2. 中转站模式 - 同时设置 apiKey 和 baseUrl
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ApiKeyController {

    private final DynamicApiKeyService dynamicApiKeyService;

    /**
     * 设置 API 配置（API Key 和可选的 Base URL）
     *
     * 请求体：
     * {
     *   "apiKey": "your-api-key",      // 必填
     *   "baseUrl": "https://..."       // 可选，不填则使用 Gemini 官方
     * }
     */
    @PostMapping("/api-key")
    public ResponseEntity<Map<String, Object>> setApiConfig(@RequestBody Map<String, String> request) {
        String apiKey = request.get("apiKey");
        String baseUrl = request.get("baseUrl");

        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "API Key is required"
            ));
        }

        try {
            // 设置 API Key
            dynamicApiKeyService.setApiKey(apiKey);

            // 设置 Base URL（可以为空，表示使用 Gemini 官方）
            dynamicApiKeyService.setBaseUrl(baseUrl);

            String mode = (baseUrl != null && !baseUrl.isBlank()) ? "proxy" : "official";
            log.info("✅ API config set via REST endpoint (mode: {})", mode);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "API configuration saved successfully",
                    "mode", mode
            ));
        } catch (Exception e) {
            log.error("❌ Failed to set API config", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * 获取 API 配置状态
     *
     * 返回：
     * {
     *   "configured": true/false,
     *   "mode": "official" | "proxy",
     *   "baseUrl": "https://..." | null
     * }
     */
    @GetMapping("/api-key/status")
    public ResponseEntity<Map<String, Object>> getApiKeyStatus() {
        boolean configured = dynamicApiKeyService.isConfigured();
        boolean usingProxy = dynamicApiKeyService.isUsingProxy();
        String baseUrl = dynamicApiKeyService.getBaseUrl();

        Map<String, Object> response = new HashMap<>();
        response.put("configured", configured);
        response.put("mode", usingProxy ? "proxy" : "official");
        response.put("baseUrl", baseUrl);

        return ResponseEntity.ok(response);
    }

    /**
     * 清除 API 配置
     */
    @DeleteMapping("/api-key")
    public ResponseEntity<Map<String, Object>> clearApiConfig() {
        try {
            dynamicApiKeyService.clearConfig();
            log.info("✅ API config cleared via REST endpoint");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "API configuration cleared successfully"
            ));
        } catch (Exception e) {
            log.error("❌ Failed to clear API config", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }
}
