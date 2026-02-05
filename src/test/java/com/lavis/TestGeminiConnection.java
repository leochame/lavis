package com.lavis;

import okhttp3.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 简单的 Gemini API 连接测试
 */
@DisplayName("Gemini Connection Tests")
class TestGeminiConnection {
    
    private static final String BASE_URL = "https://api.aifuwu.icu/v1";
    private static final String API_KEY = "sk-eItWeEDa21Qx7eQfy9fhNocrPM4pH4rvT7TaSXLwuNw2KEA3";
    private static final String MODEL_NAME = "gemini-3-flash-preview";
    
    @Test
    @DisplayName("Test basic connection")
    void testBasicConnection() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        
        // 测试 1: 基本连接
        assertDoesNotThrow(() -> testBasicConnection(client));
        
        // 测试 2: 构建正确的 API URL
        String apiUrl = buildApiUrl();
        assertNotNull(apiUrl);
        assertTrue(apiUrl.contains(MODEL_NAME));
        
        // 测试 3: 发送简单的文本请求（可选，可能因为网络问题失败）
        // 注释掉以避免测试不稳定
        // testSimpleRequest(client, apiUrl);
    }
    
    private void testBasicConnection(OkHttpClient client) throws IOException {
        Request request = new Request.Builder()
                .url("https://api.aifuwu.icu")
                .get()
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            assertTrue(response.isSuccessful() || response.code() < 500, 
                    "连接应该成功或返回客户端错误，而不是服务器错误");
        }
    }
    
    private String buildApiUrl() {
        String baseUrl = BASE_URL;
        // 移除末尾的 /v1
        if (baseUrl.endsWith("/v1")) {
            String baseWithoutV1 = baseUrl.substring(0, baseUrl.length() - 3);
            return baseWithoutV1 + "/v1beta/models/" + MODEL_NAME + ":generateContent";
        } else {
            return baseUrl + "/v1beta/models/" + MODEL_NAME + ":generateContent";
        }
    }
    
    @SuppressWarnings("unused")
    private void testSimpleRequest(OkHttpClient client, String apiUrl) {
        try {
            String jsonBody = "{\"contents\":[{\"parts\":[{\"text\":\"Hello\"}]}]}";
            RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
            
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(body)
                    .build();
            
            System.out.println("发送请求到: " + apiUrl);
            long startTime = System.currentTimeMillis();
            
            try (Response response = client.newCall(request).execute()) {
                long duration = System.currentTimeMillis() - startTime;
                int statusCode = response.code();
                String responseBody = response.body() != null ? response.body().string() : "";
                
                System.out.println("HTTP Status: " + statusCode);
                System.out.println("响应时间: " + duration + "ms");
                
                if (statusCode == 200) {
                    System.out.println("✅ 请求成功!");
                    System.out.println("响应内容: " + responseBody.substring(0, Math.min(200, responseBody.length())));
                } else {
                    System.out.println("❌ 请求失败");
                    System.out.println("错误响应: " + responseBody.substring(0, Math.min(500, responseBody.length())));
                }
            }
        } catch (IOException e) {
            System.out.println("❌ 请求异常: " + e.getClass().getSimpleName());
            System.out.println("错误信息: " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("原因: " + e.getCause().getMessage());
            }
        }
    }
}

