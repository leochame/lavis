package com.lavis;

import com.lavis.cognitive.AgentService;
import com.lavis.cognitive.orchestrator.TaskOrchestrator;
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
    private final TaskOrchestrator taskOrchestrator;

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
            
            // 设置模式切换回调
            OverlayWindow overlayWindow = javaFXInitializer.getOverlayWindow();
            if (overlayWindow != null) {
                overlayWindow.setOnModeChange(this::handleModeChange);
            }
            
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
        
        // 获取当前模式
        OverlayWindow overlayWindow = javaFXInitializer.getOverlayWindow();
        boolean isTaskMode = overlayWindow != null && overlayWindow.isTaskMode();
        
        // 异步处理用户输入
        new Thread(() -> {
            try {
                if (isTaskMode) {
                    // 慢系统：使用 TaskOrchestrator
                    javaFXInitializer.updateState(OverlayWindow.AgentState.EXECUTING);
                    javaFXInitializer.setThinkingText("规划任务中...");
                    javaFXInitializer.addLog("🎯 任务: " + input);
                    
                    TaskOrchestrator.OrchestratorResult result = taskOrchestrator.executeGoal(input);
                    
                    javaFXInitializer.updateState(result.isSuccess() ?
                            OverlayWindow.AgentState.SUCCESS : OverlayWindow.AgentState.ERROR);
                    javaFXInitializer.setThinkingText("");
                    javaFXInitializer.addLog("✅ 结果: " + result.getMessage());
                    
                    if (result.getPlan() != null) {
                        javaFXInitializer.addLog("📋 计划: " + result.getPlan().generateSummary());
                    }
                } else {
                    // 快系统：使用 chatWithScreenshot
                    javaFXInitializer.updateState(OverlayWindow.AgentState.THINKING);
                    javaFXInitializer.setThinkingText("分析屏幕...");
                    javaFXInitializer.addLog("👤 用户: " + input);
                    
                    String response = agentService.chatWithScreenshot(input);
                    
                    javaFXInitializer.updateState(OverlayWindow.AgentState.IDLE);
                    javaFXInitializer.setThinkingText("");
                    javaFXInitializer.addLog("🤖 Lavis: " + response);
                }
                
            } catch (Exception e) {
                log.error("处理用户输入失败", e);
                javaFXInitializer.updateState(OverlayWindow.AgentState.ERROR);
                javaFXInitializer.addLog("❌ 错误: " + e.getMessage());
            }
        }, "UserInput-Handler").start();
    }
    
    private void handleModeChange(boolean isTaskMode) {
        log.info("模式切换: {}", isTaskMode ? "慢系统(任务模式)" : "快系统(对话模式)");
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
