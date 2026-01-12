package com.lavis.ui;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * M4 交互模块 - Overlay UI 窗口
 * JavaFX 透明穿透窗口，用于展示 Agent 思考过程
 * 
 * 快捷键:
 * - Cmd+Enter: 发送消息
 * - Cmd+K: 清空日志
 * - Cmd+R: 重置对话
 * - Escape: 隐藏窗口
 */
@Slf4j
public class OverlayWindow {

    private Stage stage;
    private Label statusLabel;
    private Label thinkingLabel;
    private TextArea logArea;
    private TextField inputField;
    private Circle statusIndicator;
    private VBox mainContainer;
    private Button sendButton;
    
    private Consumer<String> onUserInput;
    private final List<String> logHistory = new ArrayList<>();
    private final List<String> inputHistory = new ArrayList<>();
    private int historyIndex = -1;
    
    // 状态枚举
    public enum AgentState {
        IDLE("待命", Color.web("#6B7280"), "●"),
        THINKING("思考中...", Color.web("#F59E0B"), "◐"),
        EXECUTING("执行中...", Color.web("#3B82F6"), "◑"),
        SUCCESS("完成", Color.web("#10B981"), "✓"),
        ERROR("错误", Color.web("#EF4444"), "✗");
        
        private final String text;
        private final Color color;
        private final String icon;
        
        AgentState(String text, Color color, String icon) {
            this.text = text;
            this.color = color;
            this.icon = icon;
        }
    }

    /**
     * 初始化 Overlay 窗口
     */
    public void initialize(Stage primaryStage) {
        this.stage = primaryStage;
        
        // 设置透明无边框窗口
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle("Lavis");
        
        // 创建主容器
        mainContainer = createMainContainer();
        
        // 创建场景
        Scene scene = new Scene(mainContainer, 420, 520);
        scene.setFill(Color.TRANSPARENT);
        
        // 注册全局快捷键
        registerShortcuts(scene);
        
        stage.setScene(scene);
        
        // 定位到屏幕右上角
        positionWindow();
        
        // 添加拖动功能
        enableDragging();
        
        log.info("OverlayWindow 初始化完成");
    }

    /**
     * 注册快捷键
     */
    private void registerShortcuts(Scene scene) {
        // Cmd+Enter: 发送消息
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.ENTER, KeyCombination.META_DOWN),
            this::sendCurrentInput
        );
        
        // Cmd+K: 清空日志
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.K, KeyCombination.META_DOWN),
            this::clearLog
        );
        
        // Escape: 隐藏窗口
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
            } else if (e.getCode() == KeyCode.UP && inputField.isFocused()) {
                // 上箭头：历史记录
                navigateHistory(-1);
            } else if (e.getCode() == KeyCode.DOWN && inputField.isFocused()) {
                // 下箭头：历史记录
                navigateHistory(1);
            }
        });
    }

    /**
     * 发送当前输入
     */
    private void sendCurrentInput() {
        String text = inputField.getText().trim();
        if (!text.isEmpty() && onUserInput != null) {
            inputHistory.add(text);
            historyIndex = inputHistory.size();
            onUserInput.accept(text);
            inputField.clear();
        }
    }

    /**
     * 导航输入历史
     */
    private void navigateHistory(int direction) {
        if (inputHistory.isEmpty()) return;
        
        historyIndex += direction;
        if (historyIndex < 0) historyIndex = 0;
        if (historyIndex >= inputHistory.size()) {
            historyIndex = inputHistory.size();
            inputField.clear();
            return;
        }
        
        inputField.setText(inputHistory.get(historyIndex));
        inputField.positionCaret(inputField.getText().length());
    }

    /**
     * 创建主容器
     */
    private VBox createMainContainer() {
        VBox container = new VBox(12);
        container.setPadding(new Insets(16));
        container.setAlignment(Pos.TOP_CENTER);
        
        // 现代深色主题
        container.setStyle("""
            -fx-background-color: linear-gradient(to bottom, rgba(17, 24, 39, 0.95), rgba(31, 41, 55, 0.95));
            -fx-background-radius: 16;
            -fx-border-radius: 16;
            -fx-border-color: rgba(75, 85, 99, 0.4);
            -fx-border-width: 1;
            """);
        
        // 添加阴影
        DropShadow shadow = new DropShadow();
        shadow.setRadius(25);
        shadow.setOffsetY(4);
        shadow.setColor(Color.rgb(0, 0, 0, 0.4));
        container.setEffect(shadow);
        
        // 标题栏
        HBox titleBar = createTitleBar();
        
        // 状态区域
        HBox statusArea = createStatusArea();
        
        // 日志区域
        VBox logContainer = createLogArea();
        VBox.setVgrow(logContainer, Priority.ALWAYS);
        
        // 输入区域
        HBox inputArea = createInputArea();
        
        // 底部提示
        Label hint = new Label("⌘+Enter 发送 | ⌘+K 清空 | ↑↓ 历史");
        hint.setFont(Font.font("SF Pro Display", 10));
        hint.setTextFill(Color.gray(0.4));
        
        container.getChildren().addAll(titleBar, statusArea, logContainer, inputArea, hint);
        
        return container;
    }

    /**
     * 创建标题栏
     */
    private HBox createTitleBar() {
        HBox titleBar = new HBox(12);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(0, 0, 8, 0));
        
        // 窗口控制按钮 (macOS 风格，左侧)
        Circle closeBtn = new Circle(6, Color.web("#FF5F57"));
        Circle minimizeBtn = new Circle(6, Color.web("#FFBD2E"));
        Circle maximizeBtn = new Circle(6, Color.web("#28CA41"));
        
        // 悬停效果
        setupWindowButtonHover(closeBtn, "#FF5F57", "#FF3B30");
        setupWindowButtonHover(minimizeBtn, "#FFBD2E", "#FF9500");
        setupWindowButtonHover(maximizeBtn, "#28CA41", "#34C759");
        
        closeBtn.setOnMouseClicked(e -> hide());
        minimizeBtn.setOnMouseClicked(e -> stage.setIconified(true));
        
        HBox windowControls = new HBox(8);
        windowControls.setAlignment(Pos.CENTER_LEFT);
        windowControls.getChildren().addAll(closeBtn, minimizeBtn, maximizeBtn);
        
        // Logo
        Circle logo = new Circle(10);
        logo.setFill(createGradient());
        
        // 标题
        Label title = new Label("Lavis");
        title.setFont(Font.font("SF Pro Display", FontWeight.SEMI_BOLD, 16));
        title.setTextFill(Color.WHITE);
        
        // 两个 spacer 需要是不同的对象
        Region spacer1 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS);
        
        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);
        
        // 设置按钮
        Button settingsBtn = createIconButton("⚙", "设置");
        settingsBtn.setOnAction(e -> showSettings());
        
        titleBar.getChildren().addAll(windowControls, spacer1, logo, title, spacer2, settingsBtn);
        
        return titleBar;
    }

    /**
     * 设置窗口按钮悬停效果
     */
    private void setupWindowButtonHover(Circle btn, String normalColor, String hoverColor) {
        btn.setOnMouseEntered(e -> btn.setFill(Color.web(hoverColor)));
        btn.setOnMouseExited(e -> btn.setFill(Color.web(normalColor)));
    }

    /**
     * 创建图标按钮
     */
    private Button createIconButton(String icon, String tooltip) {
        Button btn = new Button(icon);
        btn.setStyle("""
            -fx-background-color: transparent;
            -fx-text-fill: #9CA3AF;
            -fx-font-size: 14;
            -fx-cursor: hand;
            -fx-padding: 4 8;
            """);
        btn.setTooltip(new Tooltip(tooltip));
        btn.setOnMouseEntered(e -> btn.setStyle(btn.getStyle() + "-fx-text-fill: white;"));
        btn.setOnMouseExited(e -> btn.setStyle(btn.getStyle().replace("-fx-text-fill: white;", "-fx-text-fill: #9CA3AF;")));
        return btn;
    }

    /**
     * 创建状态区域
     */
    private HBox createStatusArea() {
        HBox statusArea = new HBox(10);
        statusArea.setAlignment(Pos.CENTER_LEFT);
        statusArea.setPadding(new Insets(10, 12, 10, 12));
        statusArea.setStyle("""
            -fx-background-color: rgba(55, 65, 81, 0.5);
            -fx-background-radius: 10;
            """);
        
        // 状态指示灯
        statusIndicator = new Circle(5, Color.web("#6B7280"));
        
        // 状态文本
        statusLabel = new Label("待命");
        statusLabel.setFont(Font.font("SF Pro Display", FontWeight.MEDIUM, 12));
        statusLabel.setTextFill(Color.WHITE);
        
        // 分隔
        Region sep = new Region();
        sep.setPrefWidth(1);
        sep.setStyle("-fx-background-color: rgba(255,255,255,0.1);");
        sep.setPrefHeight(16);
        
        // 思考内容
        thinkingLabel = new Label("");
        thinkingLabel.setFont(Font.font("SF Pro Display", 11));
        thinkingLabel.setTextFill(Color.gray(0.6));
        thinkingLabel.setWrapText(true);
        thinkingLabel.setMaxWidth(250);
        HBox.setHgrow(thinkingLabel, Priority.ALWAYS);
        
        statusArea.getChildren().addAll(statusIndicator, statusLabel, sep, thinkingLabel);
        
        return statusArea;
    }

    /**
     * 创建日志区域
     */
    private VBox createLogArea() {
        VBox logContainer = new VBox(8);
        
        // 日志标题
        HBox logHeader = new HBox();
        logHeader.setAlignment(Pos.CENTER_LEFT);
        Label logTitle = new Label("📋 活动日志");
        logTitle.setFont(Font.font("SF Pro Display", FontWeight.MEDIUM, 11));
        logTitle.setTextFill(Color.gray(0.5));
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        Button clearBtn = new Button("清空");
        clearBtn.setStyle("""
            -fx-background-color: transparent;
            -fx-text-fill: #6B7280;
            -fx-font-size: 10;
            -fx-cursor: hand;
            -fx-padding: 2 6;
            """);
        clearBtn.setOnAction(e -> clearLog());
        
        logHeader.getChildren().addAll(logTitle, spacer, clearBtn);
        
        // 日志内容
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setFont(Font.font("SF Mono", 11));
        logArea.setStyle("""
            -fx-control-inner-background: rgba(17, 24, 39, 0.6);
            -fx-text-fill: #D1D5DB;
            -fx-background-color: transparent;
            -fx-border-color: rgba(75, 85, 99, 0.3);
            -fx-border-radius: 8;
            -fx-background-radius: 8;
            -fx-focus-color: transparent;
            -fx-faint-focus-color: transparent;
            -fx-padding: 8;
            """);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        
        logContainer.getChildren().addAll(logHeader, logArea);
        
        return logContainer;
    }

    /**
     * 创建输入区域
     */
    private HBox createInputArea() {
        HBox inputArea = new HBox(8);
        inputArea.setAlignment(Pos.CENTER);
        inputArea.setPadding(new Insets(8, 0, 0, 0));
        
        inputField = new TextField();
        inputField.setPromptText("输入指令... (支持自然语言)");
        inputField.setFont(Font.font("SF Pro Display", 13));
        inputField.setStyle("""
            -fx-background-color: rgba(55, 65, 81, 0.6);
            -fx-text-fill: white;
            -fx-prompt-text-fill: #6B7280;
            -fx-background-radius: 10;
            -fx-border-radius: 10;
            -fx-border-color: rgba(75, 85, 99, 0.4);
            -fx-border-width: 1;
            -fx-padding: 10 12;
            """);
        HBox.setHgrow(inputField, Priority.ALWAYS);
        
        // 焦点效果
        inputField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                inputField.setStyle(inputField.getStyle() + "-fx-border-color: #3B82F6;");
            } else {
                inputField.setStyle(inputField.getStyle().replace("-fx-border-color: #3B82F6;", "-fx-border-color: rgba(75, 85, 99, 0.4);"));
            }
        });
        
        // 回车发送
        inputField.setOnAction(e -> sendCurrentInput());
        
        // 发送按钮
        sendButton = new Button("→");
        sendButton.setFont(Font.font("SF Pro Display", FontWeight.BOLD, 14));
        sendButton.setStyle("""
            -fx-background-color: linear-gradient(to right, #3B82F6, #8B5CF6);
            -fx-text-fill: white;
            -fx-background-radius: 10;
            -fx-padding: 10 14;
            -fx-cursor: hand;
            """);
        sendButton.setOnAction(e -> sendCurrentInput());
        
        // 悬停效果
        sendButton.setOnMouseEntered(e -> sendButton.setStyle("""
            -fx-background-color: linear-gradient(to right, #2563EB, #7C3AED);
            -fx-text-fill: white;
            -fx-background-radius: 10;
            -fx-padding: 10 14;
            -fx-cursor: hand;
            """));
        sendButton.setOnMouseExited(e -> sendButton.setStyle("""
            -fx-background-color: linear-gradient(to right, #3B82F6, #8B5CF6);
            -fx-text-fill: white;
            -fx-background-radius: 10;
            -fx-padding: 10 14;
            -fx-cursor: hand;
            """));
        
        inputArea.getChildren().addAll(inputField, sendButton);
        
        return inputArea;
    }

    /**
     * 显示设置面板
     */
    private void showSettings() {
        // TODO: 实现设置面板
        addLog("⚙️ 设置功能开发中...");
    }

    /**
     * 创建渐变色
     */
    private LinearGradient createGradient() {
        return new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#3B82F6")),
            new Stop(1, Color.web("#8B5CF6"))
        );
    }

    /**
     * 定位窗口到屏幕右上角
     */
    private void positionWindow() {
        Screen screen = Screen.getPrimary();
        double screenWidth = screen.getBounds().getWidth();
        
        stage.setX(screenWidth - 450);
        stage.setY(60);
    }

    /**
     * 启用窗口拖动
     */
    private void enableDragging() {
        final double[] dragOffset = new double[2];
        
        mainContainer.setOnMousePressed(e -> {
            dragOffset[0] = e.getSceneX();
            dragOffset[1] = e.getSceneY();
        });
        
        mainContainer.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragOffset[0]);
            stage.setY(e.getScreenY() - dragOffset[1]);
        });
    }

    /**
     * 显示窗口
     */
    public void show() {
        Platform.runLater(() -> {
            stage.show();
            // 淡入动画
            FadeTransition fadeIn = new FadeTransition(Duration.millis(250), mainContainer);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);
            fadeIn.play();
            
            // 聚焦输入框
            inputField.requestFocus();
        });
    }

    /**
     * 隐藏窗口
     */
    public void hide() {
        Platform.runLater(() -> {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(150), mainContainer);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(e -> stage.hide());
            fadeOut.play();
        });
    }

    /**
     * 设置 Agent 状态
     */
    public void setState(AgentState state) {
        Platform.runLater(() -> {
            statusLabel.setText(state.icon + " " + state.text);
            statusIndicator.setFill(state.color);
            
            // 思考/执行状态添加脉冲动画
            if (state == AgentState.THINKING || state == AgentState.EXECUTING) {
                startPulseAnimation();
                sendButton.setDisable(true);
            } else {
                stopPulseAnimation();
                sendButton.setDisable(false);
            }
        });
    }

    /**
     * 设置思考内容
     */
    public void setThinkingText(String text) {
        Platform.runLater(() -> {
            thinkingLabel.setText(text);
        });
    }

    /**
     * 添加日志
     */
    public void addLog(String message) {
        Platform.runLater(() -> {
            String timestamp = java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            String logEntry = String.format("[%s] %s\n", timestamp, message);
            
            logHistory.add(logEntry);
            logArea.appendText(logEntry);
            
            // 保持最近 100 条日志
            if (logHistory.size() > 100) {
                logHistory.remove(0);
                refreshLogArea();
            }
            
            // 自动滚动到底部
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    /**
     * 刷新日志区域
     */
    private void refreshLogArea() {
        logArea.clear();
        for (String entry : logHistory) {
            logArea.appendText(entry);
        }
    }

    /**
     * 清空日志
     */
    public void clearLog() {
        Platform.runLater(() -> {
            logHistory.clear();
            logArea.clear();
            addLog("📋 日志已清空");
        });
    }

    /**
     * 显示高亮点击区域
     */
    public void showClickHighlight(int x, int y) {
        Platform.runLater(() -> {
            Stage highlightStage = new Stage();
            highlightStage.initStyle(StageStyle.TRANSPARENT);
            highlightStage.setAlwaysOnTop(true);
            
            Circle highlight = new Circle(20, Color.TRANSPARENT);
            highlight.setStroke(Color.web("#3B82F6"));
            highlight.setStrokeWidth(3);
            
            StackPane pane = new StackPane(highlight);
            pane.setBackground(Background.EMPTY);
            
            Scene scene = new Scene(pane, 50, 50);
            scene.setFill(Color.TRANSPARENT);
            
            highlightStage.setScene(scene);
            highlightStage.setX(x - 25);
            highlightStage.setY(y - 25);
            highlightStage.show();
            
            // 动画效果
            ScaleTransition scale = new ScaleTransition(Duration.millis(300), highlight);
            scale.setFromX(0.5);
            scale.setFromY(0.5);
            scale.setToX(1.5);
            scale.setToY(1.5);
            
            FadeTransition fade = new FadeTransition(Duration.millis(400), highlight);
            fade.setFromValue(1);
            fade.setToValue(0);
            fade.setDelay(Duration.millis(150));
            fade.setOnFinished(e -> highlightStage.close());
            
            scale.play();
            fade.play();
        });
    }

    /**
     * 设置用户输入回调
     */
    public void setOnUserInput(Consumer<String> callback) {
        this.onUserInput = callback;
    }

    private Timeline pulseAnimation;

    /**
     * 开始脉冲动画
     */
    private void startPulseAnimation() {
        if (pulseAnimation != null) {
            pulseAnimation.stop();
        }
        
        pulseAnimation = new Timeline(
            new KeyFrame(Duration.ZERO, 
                new KeyValue(statusIndicator.opacityProperty(), 1.0)),
            new KeyFrame(Duration.millis(600), 
                new KeyValue(statusIndicator.opacityProperty(), 0.3)),
            new KeyFrame(Duration.millis(1200), 
                new KeyValue(statusIndicator.opacityProperty(), 1.0))
        );
        pulseAnimation.setCycleCount(Animation.INDEFINITE);
        pulseAnimation.play();
    }

    /**
     * 停止脉冲动画
     */
    private void stopPulseAnimation() {
        if (pulseAnimation != null) {
            pulseAnimation.stop();
            statusIndicator.setOpacity(1.0);
        }
    }

    /**
     * 获取 Stage
     */
    public Stage getStage() {
        return stage;
    }
}
