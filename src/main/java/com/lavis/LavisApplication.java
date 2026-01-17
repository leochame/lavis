package com.lavis;

import com.lavis.cognitive.AgentService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Lavis - Headless Desktop AI Agent
 *
 * A headless AI agent running on macOS that:
 * - Perceives the screen via screenshots
 * - Controls the system via mouse/keyboard
 * - Reflects via Action-Observation-Correction loop
 * - Exposes REST APIs for Electron UI integration
 */
@Slf4j
@SpringBootApplication
@RequiredArgsConstructor
public class LavisApplication {

    private final AgentService agentService;

    public static void main(String[] args) {
        // FIX: Explicitly disable headless mode so java.awt.Robot can work
        new SpringApplicationBuilder(LavisApplication.class)
                .headless(false)
                .run(args);
    }

    @PostConstruct
    public void init() {
        log.info("===========================================");
        log.info("   Lavis - Headless AI Agent 启动中...");
        log.info("===========================================");

        // 打印启动信息
        printStartupInfo();
    }

    private void printStartupInfo() {
        log.info("");
        log.info("┌─────────────────────────────────────────┐");
        log.info("│          Lavis 启动成功!                 │");
        log.info("├─────────────────────────────────────────┤");
        log.info("│  REST API: http://localhost:8080        │");
        log.info("│  状态:     GET  /api/agent/status       │");
        log.info("│  快系统:   POST /api/agent/chat         │");
        log.info("│  慢系统:   POST /api/agent/task         │");
        log.info("│  停止:     POST /api/agent/stop         │");
        log.info("│  重置:     POST /api/agent/reset        │");
        log.info("│  截图:     GET  /api/agent/screenshot   │");
        log.info("├─────────────────────────────────────────┤");
        log.info("│  模型: " + agentService.getModelInfo());
        log.info("└─────────────────────────────────────────┘");
        log.info("");

        if (!agentService.isAvailable()) {
            log.warn("⚠️  Agent 未可用！请检查配置");
            log.warn("    检查 application.properties 中的 app.llm.models.* 配置");
        }
    }
}