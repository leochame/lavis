package com.lavis.ui;

import javafx.application.Application;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * JavaFX 初始化器
 * 负责在 Spring Boot 环境中启动 JavaFX
 */
@Slf4j
@Component
public class JavaFXInitializer {

    private static OverlayWindow overlayWindow;
    private static Consumer<String> userInputCallback;
    private static final CountDownLatch latch = new CountDownLatch(1);
    private static boolean initialized = false;

    /**
     * JavaFX Application 入口
     */
    public static class LavisApp extends Application {
        @Override
        public void start(Stage primaryStage) {
            overlayWindow = new OverlayWindow();
            overlayWindow.initialize(primaryStage);
            
            if (userInputCallback != null) {
                overlayWindow.setOnUserInput(userInputCallback);
            }
            
            initialized = true;
            latch.countDown();
            
            log.info("JavaFX Application 启动完成");
        }

        @Override
        public void stop() {
            log.info("JavaFX Application 停止");
        }
    }

    /**
     * 初始化 JavaFX (在后台线程中)
     */
    public void initializeAsync() {
        Thread jfxThread = new Thread(() -> {
            try {
                Application.launch(LavisApp.class);
            } catch (Exception e) {
                log.error("JavaFX 启动失败", e);
            }
        }, "JavaFX-Launcher");
        jfxThread.setDaemon(true);
        jfxThread.start();
        
        log.info("JavaFX 初始化线程已启动");
    }

    /**
     * 等待 JavaFX 初始化完成
     */
    public void waitForInitialization() throws InterruptedException {
        latch.await();
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 获取 Overlay 窗口
     */
    public OverlayWindow getOverlayWindow() {
        return overlayWindow;
    }

    /**
     * 显示 Overlay 窗口
     */
    public void showOverlay() {
        if (overlayWindow != null) {
            overlayWindow.show();
        }
    }

    /**
     * 隐藏 Overlay 窗口
     */
    public void hideOverlay() {
        if (overlayWindow != null) {
            overlayWindow.hide();
        }
    }

    /**
     * 设置用户输入回调
     */
    public void setUserInputCallback(Consumer<String> callback) {
        userInputCallback = callback;
        if (overlayWindow != null) {
            overlayWindow.setOnUserInput(callback);
        }
    }

    /**
     * 更新状态
     */
    public void updateState(OverlayWindow.AgentState state) {
        if (overlayWindow != null) {
            overlayWindow.setState(state);
        }
    }

    /**
     * 添加日志
     */
    public void addLog(String message) {
        if (overlayWindow != null) {
            overlayWindow.addLog(message);
        }
    }

    /**
     * 设置思考文本
     */
    public void setThinkingText(String text) {
        if (overlayWindow != null) {
            overlayWindow.setThinkingText(text);
        }
    }

    /**
     * 显示点击高亮
     */
    public void showClickHighlight(int x, int y) {
        if (overlayWindow != null) {
            overlayWindow.showClickHighlight(x, y);
        }
    }
}

