package com.lavis;

import com.lavis.cognitive.AgentService;
import com.lavis.ui.JavaFXInitializer;
import com.lavis.ui.OverlayWindow;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Lavis - macOS 系统级多模态智能体
 * 
 * 一个运行在 macOS 上的 "Jarvis"，能够通过视觉感知屏幕、
 * 通过鼠标键盘操作系统的自主智能体。
 * 
 * 核心特性：
 * - 视觉感知：实时截图分析
 * - 自主操作：鼠标键盘控制
 * - 反思机制：Action-Observation-Correction 闭环
 * - 透明 UI：HUD 抬头显示器展示思考过程
 */
@Slf4j
@SpringBootApplication
@RequiredArgsConstructor
public class LavisApplication {

    private final JavaFXInitializer javaFXInitializer;
    private final AgentService agentService;

    public static void main(String[] args) {
        // 设置 JavaFX 相关系统属性
        System.setProperty("java.awt.headless", "false");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        
        // 抑制 macOS 输入法相关警告 (TSM/IMK)
        System.setProperty("apple.awt.UIElement", "true");
        
        // 禁用 JavaFX 对辅助功能的警告
        System.setProperty("glass.accessible.force", "false");
        
        SpringApplication.run(LavisApplication.class, args);
    }

    @PostConstruct
    public void init() {
        log.info("===========================================");
        log.info("   Lavis - macOS AI Agent 正在启动...");
        log.info("===========================================");
        
        // 初始化 JavaFX UI
        initializeUI();
        
        // 打印启动信息
        printStartupInfo();
    }

    private void initializeUI() {
        try {
            // 在后台线程启动 JavaFX
            javaFXInitializer.initializeAsync();
            
            // 等待初始化完成
            Thread.sleep(1000);
            
            // 设置用户输入回调
            javaFXInitializer.setUserInputCallback(this::handleUserInput);
            
            // 显示 UI
            javaFXInitializer.showOverlay();
            javaFXInitializer.addLog("Lavis 已启动，等待指令...");
            
            log.info("JavaFX UI 初始化完成");
        } catch (Exception e) {
            log.error("UI 初始化失败", e);
        }
    }

    private void handleUserInput(String input) {
        log.info("收到用户输入: {}", input);
        
        // 异步处理用户输入
        new Thread(() -> {
            try {
                javaFXInitializer.updateState(OverlayWindow.AgentState.THINKING);
                javaFXInitializer.setThinkingText("正在处理...");
                javaFXInitializer.addLog("用户: " + input);
                
                // 调用 Agent 处理
                String response = agentService.chatWithScreenshot(input);
                
                javaFXInitializer.updateState(OverlayWindow.AgentState.IDLE);
                javaFXInitializer.setThinkingText("");
                javaFXInitializer.addLog("Lavis: " + response);
                
            } catch (Exception e) {
                log.error("处理用户输入失败", e);
                javaFXInitializer.updateState(OverlayWindow.AgentState.ERROR);
                javaFXInitializer.addLog("错误: " + e.getMessage());
            }
        }, "UserInput-Handler").start();
    }

    private void printStartupInfo() {
        log.info("");
        log.info("┌─────────────────────────────────────────┐");
        log.info("│          Lavis 启动成功!                 │");
        log.info("├─────────────────────────────────────────┤");
        log.info("│  REST API: http://localhost:8080        │");
        log.info("│  状态:     GET  /api/agent/status       │");
        log.info("│  对话:     POST /api/agent/chat         │");
        log.info("│  截图对话: POST /api/agent/chat-with-screenshot │");
        log.info("│  执行任务: POST /api/agent/execute      │");
        log.info("│  截图:     GET  /api/agent/screenshot   │");
        log.info("├─────────────────────────────────────────┤");
        log.info("│  模型: " + agentService.getModelInfo());
        log.info("└─────────────────────────────────────────┘");
        log.info("");
        
        if (!agentService.isAvailable()) {
            log.warn("⚠️  Agent 未可用！请检查 GEMINI_API_KEY 环境变量");
            log.warn("    设置方法: export GEMINI_API_KEY=your_api_key");
        }
    }
}
