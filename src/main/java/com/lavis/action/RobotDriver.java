package com.lavis.action;

import com.lavis.perception.ScreenCapturer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * M3 执行模块 - 机器人驱动器
 * 封装 mouseMove, click, type 操作
 * 关键点：实现逻辑坐标与物理坐标的换算
 * 
 * 【重要改进】所有操作返回 ExecutionResult，包含：
 * - 是否成功
 * - 实际执行位置
 * - 偏差信息
 * - 详细诊断信息
 */
@Slf4j
@Component
public class RobotDriver {

    private final Robot robot;
    private final ScreenCapturer screenCapturer;

    // 默认操作延迟 (毫秒)
    private static final int DEFAULT_DELAY = 100;
    private static final int TYPE_DELAY = 50;

    // 允许的最大偏差（像素）
    private static final int MAX_ALLOWED_DEVIATION = 10;

    // 是否开启拟人化移动
    private boolean humanLikeMode = true;

    // 鼠标移动速度因子 (1.0 = 正常，2.0 = 快速，0.5 = 慢速)
    // 【优化】提高默认速度，减少拖沓感
    private double mouseSpeedFactor = 3.5;
    
    // 基础步间延迟 (毫秒) - 【优化】大幅减少步间延迟
    private static final int BASE_STEP_DELAY_MS = 0;
    
    // 拖拽操作的额外延迟 - 【优化】减少拖拽延迟
    private static final int DRAG_STEP_DELAY_MS = 1;

    public RobotDriver(ScreenCapturer screenCapturer) throws AWTException {
        this.robot = new Robot();
        this.screenCapturer = screenCapturer;
        this.robot.setAutoDelay(DEFAULT_DELAY);
        log.info("RobotDriver 初始化完成");
    }

    /**
     * 将坐标转换为安全的逻辑屏幕坐标（带边界检查）
     * 
     * 【坐标系统说明】
     * - 逻辑坐标：macOS 屏幕逻辑坐标（如 1440x900），AI 直接使用这个坐标
     * - 物理坐标：Retina 屏幕实际像素（如 2880x1800），仅截图内部使用
     * 
     * 安全特性：
     * - 越界保护：确保坐标在屏幕范围内
     * - 安全边距：避免触发 Hot Corners、菜单栏等
     * 
     * @param x 逻辑屏幕坐标 X
     * @param y 逻辑屏幕坐标 Y
     * @return 安全的逻辑屏幕坐标
     */
    public Point convertToRobotCoordinates(int x, int y) {
        return convertToRobotCoordinates(x, y, ScreenCapturer.SafeZoneConfig.DEFAULT);
    }
    
    /**
     * 使用自定义安全配置转换坐标
     */
    public Point convertToRobotCoordinates(int x, int y, 
                                           ScreenCapturer.SafeZoneConfig safeConfig) {
        Dimension screenSize = screenCapturer.getScreenSize();
        
        // 安全边界
        int minX = safeConfig.leftMargin;
        int maxX = screenSize.width - safeConfig.rightMargin;
        int minY = safeConfig.topMargin;
        int maxY = screenSize.height - safeConfig.bottomMargin;
        
        // 钳位
        int safeX = Math.max(minX, Math.min(x, maxX));
        int safeY = Math.max(minY, Math.min(y, maxY));
        
        // 如果发生修正，记录日志
        if (safeX != x || safeY != y) {
            log.warn("🛡️ 坐标安全修正: ({},{}) -> ({},{}) [边界: {}-{}, {}-{}]",
                    x, y, safeX, safeY, minX, maxX, minY, maxY);
        }
        
        return new Point(safeX, safeY);
    }
    
    /**
     * 检查坐标是否安全
     */
    public boolean isCoordinateSafe(int x, int y) {
        Dimension screenSize = screenCapturer.getScreenSize();
        ScreenCapturer.SafeZoneConfig config = ScreenCapturer.SafeZoneConfig.DEFAULT;
        
        return x >= config.leftMargin 
            && x <= screenSize.width - config.rightMargin
            && y >= config.topMargin 
            && y <= screenSize.height - config.bottomMargin;
    }

    /**
     * 移动鼠标到指定位置（逻辑屏幕坐标）
     * 
     * 【M3-1 增强】使用贝塞尔曲线 + 随机延迟实现拟人化移动
     * 
     * @param x 逻辑屏幕坐标 X
     * @param y 逻辑屏幕坐标 Y
     * @return 执行结果，包含是否成功和偏差信息
     */
    public ExecutionResult moveTo(int x, int y) {
        long startTime = System.currentTimeMillis();

        // 记录移动前位置
        Point beforePos = getMouseLocation();

        // 转换为安全坐标（边界检查）
        Point targetPos = convertToRobotCoordinates(x, y);

        if (humanLikeMode) {
            // 【增强】拟人化移动 - 使用增强的贝塞尔曲线
            double distance = beforePos.distance(targetPos);
            int steps = BezierMouseUtils.calculateRecommendedSteps(distance, mouseSpeedFactor);
            
            java.util.List<Point> path = BezierMouseUtils.generatePath(
                    beforePos, targetPos, steps,
                    BezierMouseUtils.EasingType.HUMAN_LIKE, true);
            
            // 沿路径移动，带随机延迟
            for (int i = 0; i < path.size(); i++) {
                Point p = path.get(i);
                robot.mouseMove(p.x, p.y);
                
                // 【增强】随机延迟，模拟人类速度变化
                int stepDelay = BezierMouseUtils.generateStepDelay(i, path.size(), BASE_STEP_DELAY_MS);
                if (stepDelay > 0) {
                    robot.delay(stepDelay);
                }
            }
            
            // 确保最后精准落在目标点
            robot.mouseMove(targetPos.x, targetPos.y);
        } else {
            // 机械瞬间移动
            robot.mouseMove(targetPos.x, targetPos.y);
        }

        // 等待移动完成 - 【优化】减少等待时间
        delay(humanLikeMode ? 2 : 20);

        // 验证移动是否成功
        Point afterPos = getMouseLocation();
        int deltaX = afterPos.x - targetPos.x;
        int deltaY = afterPos.y - targetPos.y;
        int absDeltaX = Math.abs(deltaX);
        int absDeltaY = Math.abs(deltaY);

        long executionTime = System.currentTimeMillis() - startTime;

        ExecutionResult result = new ExecutionResult();
        result.setActionType("moveTo");
        result.setRequestedAiCoord(new Point(x, y));
        result.setTargetLogicalCoord(targetPos);
        result.setActualLogicalCoord(afterPos);
        result.setDeviationX(deltaX);
        result.setDeviationY(deltaY);
        result.setExecutionTimeMs(executionTime);

        if (absDeltaX > MAX_ALLOWED_DEVIATION || absDeltaY > MAX_ALLOWED_DEVIATION) {
            result.setSuccess(false);
            result.setMessage(String.format("❌ 鼠标移动失败！目标:(%d,%d) 实际:(%d,%d) 偏差:(%d,%d)",
                    targetPos.x, targetPos.y, afterPos.x, afterPos.y, deltaX, deltaY));
            log.error(result.getMessage());
        } else {
            result.setSuccess(true);
            result.setMessage(String.format("✅ 移动成功: 目标(%d,%d)->实际(%d,%d)",
                    x, y, afterPos.x, afterPos.y));
            log.info(result.getMessage());
        }

        return result;
    }

    /**
     * 移动鼠标到指定位置 (屏幕逻辑坐标)
     */
    public void moveToScreen(int x, int y) {
        robot.mouseMove(x, y);
        log.info("鼠标移动到屏幕坐标: ({}, {})", x, y);
    }

    /**
     * 单击鼠标左键
     */
    public void click() {
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(10);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        log.info("鼠标左键单击");
    }

    /**
     * 移动并点击（逻辑屏幕坐标）
     * 
     * @param x 逻辑屏幕坐标 X
     * @param y 逻辑屏幕坐标 Y
     * @return 执行结果，包含是否成功和偏差信息
     */
    public ExecutionResult clickAt(int x, int y) {
        log.info("🖱️ 准备点击: 坐标({},{})", x, y);

        // 先移动
        ExecutionResult moveResult = moveTo(x, y);
        if (!moveResult.isSuccess()) {
            // 移动失败，记录但继续尝试点击
            log.warn("⚠️ 移动有偏差，但仍尝试点击");
        }

        delay(50);
        click();

        // 获取点击后的实际位置
        Point actualPos = getMouseLocation();

        // 构建点击结果
        ExecutionResult result = new ExecutionResult();
        result.setActionType("click");
        result.setRequestedAiCoord(new Point(x, y));
        result.setTargetLogicalCoord(moveResult.getTargetLogicalCoord());
        result.setActualLogicalCoord(actualPos);
        result.setDeviationX(moveResult.getDeviationX());
        result.setDeviationY(moveResult.getDeviationY());
        result.setExecutionTimeMs(moveResult.getExecutionTimeMs() + 50);
        result.setSuccess(moveResult.isSuccess());

        if (moveResult.isSuccess()) {
            result.setMessage(String.format("✅ 点击成功: 目标(%d,%d)->实际(%d,%d)",
                    x, y, actualPos.x, actualPos.y));
        } else {
            result.setMessage(String.format("⚠️ 点击完成但有偏差: 目标(%d,%d) 实际(%d,%d) 偏差(%d,%d)",
                    x, y,
                    actualPos.x, actualPos.y,
                    moveResult.getDeviationX(), moveResult.getDeviationY()));
        }

        log.info("🖱️ {}", result.getMessage());
        return result;
    }

    /**
     * 双击鼠标左键
     */
    public void doubleClick() {
        click();
        delay(50);
        click();
        log.info("鼠标左键双击");
    }

    /**
     * 移动并双击（逻辑屏幕坐标）
     */
    public void doubleClickAt(int x, int y) {
        moveTo(x, y);
        delay(50);
        doubleClick();
    }

    /**
     * 右键单击
     */
    public void rightClick() {
        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
        log.info("鼠标右键单击");
    }

    /**
     * 移动并右键点击（逻辑屏幕坐标）
     */
    public void rightClickAt(int x, int y) {
        moveTo(x, y);
        delay(50);
        rightClick();
    }

    /**
     * 拖拽操作（逻辑屏幕坐标）
     * 
     * 【M3-1 增强】使用基于轨迹的平滑拖拽，解决断触问题：
     * 1. 先移动到起点并稳定
     * 2. 按下鼠标后等待系统响应
     * 3. 使用专门的拖拽路径（更稳定、更慢）
     * 4. 确保每一步都发送事件，避免断触
     * 5. 到达终点后再释放
     * 
     * @return 执行结果
     */
    public ExecutionResult drag(int fromX, int fromY, int toX, int toY) {
        long startTime = System.currentTimeMillis();
        log.info("🎯 开始拖拽: ({},{}) -> ({},{})", fromX, fromY, toX, toY);
        
        // 1. 先移动到起点
        ExecutionResult moveResult = moveTo(fromX, fromY);
        if (!moveResult.isSuccess()) {
            log.warn("⚠️ 移动到拖拽起点有偏差，继续尝试拖拽");
        }
        
        // 稳定等待
        delay(80);
        
        // 获取安全坐标
        Point startPos = convertToRobotCoordinates(fromX, fromY);
        Point targetPos = convertToRobotCoordinates(toX, toY);
        
        // 2. 按下鼠标
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        
        // 【关键】按下后等待系统响应，避免拖拽失效
        delay(50);
        
        try {
            if (humanLikeMode) {
                // 3. 【增强】使用专门的拖拽路径
                double distance = startPos.distance(targetPos);
                int steps = Math.max(30, (int) (distance / 3));  // 拖拽需要更多步数
                
                java.util.List<Point> path = BezierMouseUtils.generateDragPath(startPos, targetPos, steps);
                
                // 4. 沿路径拖拽，每步都确保事件发送
                for (int i = 0; i < path.size(); i++) {
                    Point p = path.get(i);
                    robot.mouseMove(p.x, p.y);
                    
                    // 【关键】拖拽时使用更长的延迟，确保事件被系统处理
                    int stepDelay = BezierMouseUtils.generateStepDelay(i, path.size(), DRAG_STEP_DELAY_MS);
                    robot.delay(Math.max(stepDelay, 1));  // 拖拽时每步至少 1ms
                }
                
                // 确保精确到达终点
                robot.mouseMove(targetPos.x, targetPos.y);
            } else {
                // 非拟人模式：分段移动，避免瞬移导致拖拽失效
                int segments = 10;
                for (int i = 1; i <= segments; i++) {
                    int x = startPos.x + (targetPos.x - startPos.x) * i / segments;
                    int y = startPos.y + (targetPos.y - startPos.y) * i / segments;
                    robot.mouseMove(x, y);
                    robot.delay(5);
                }
            }
            
            // 到达终点后稳定等待
            delay(50);
            
        } finally {
            // 5. 释放鼠标
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }
        
        // 构建结果
        long executionTime = System.currentTimeMillis() - startTime;
        Point afterPos = getMouseLocation();
        
        ExecutionResult result = new ExecutionResult();
        result.setActionType("drag");
        result.setRequestedAiCoord(new Point(toX, toY));
        result.setTargetLogicalCoord(targetPos);
        result.setActualLogicalCoord(afterPos);
        result.setDeviationX(afterPos.x - targetPos.x);
        result.setDeviationY(afterPos.y - targetPos.y);
        result.setExecutionTimeMs(executionTime);
        
        int absDev = Math.abs(result.getDeviationX()) + Math.abs(result.getDeviationY());
        if (absDev > MAX_ALLOWED_DEVIATION * 2) {
            result.setSuccess(false);
            result.setMessage(String.format("⚠️ 拖拽完成但有较大偏差: 从(%d,%d)到(%d,%d) 偏差:(%d,%d)",
                    fromX, fromY, toX, toY, result.getDeviationX(), result.getDeviationY()));
        } else {
            result.setSuccess(true);
            result.setMessage(String.format("✅ 拖拽成功: 从(%d,%d)到(%d,%d)", fromX, fromY, toX, toY));
        }
        
        log.info("🎯 {}", result.getMessage());
        return result;
    }

    /**
     * 设置鼠标移动速度因子
     * @param factor 速度因子 (1.0 = 正常，2.0 = 快速，0.5 = 慢速)
     */
    public void setMouseSpeedFactor(double factor) {
        this.mouseSpeedFactor = Math.max(0.2, Math.min(factor, 5.0));
        log.info("🖱️ 鼠标速度因子设置为: {}", this.mouseSpeedFactor);
    }
    
    /**
     * 设置是否启用拟人化移动
     */
    public void setHumanLikeMode(boolean enabled) {
        this.humanLikeMode = enabled;
        log.info("🖱️ 拟人化模式: {}", enabled ? "开启" : "关闭");
    }
    
    /**
     * 获取当前是否为拟人化模式
     */
    public boolean isHumanLikeMode() {
        return humanLikeMode;
    }

    /**
     * 滚动鼠标滚轮
     * 
     * @param amount 正数向下滚动，负数向上滚动
     */
    public void scroll(int amount) {
        robot.mouseWheel(amount);
        log.info("滚轮滚动: {}", amount);
    }

    /**
     * 输入文本 (支持中英文)
     */
    public void type(String text) {
        log.info("输入文本: {}", text);
        for (char c : text.toCharArray()) {
            typeChar(c);
            delay(TYPE_DELAY);
        }
    }

    /**
     * 输入单个字符
     */
    private void typeChar(char c) {
        // 对于 ASCII 可打印字符，尝试直接输入
        if (c >= 32 && c <= 126) {
            int keyCode = KeyEvent.getExtendedKeyCodeForChar(c);
            if (keyCode != KeyEvent.VK_UNDEFINED) {
                boolean needShift = Character.isUpperCase(c) || isShiftRequired(c);
                if (needShift) {
                    robot.keyPress(KeyEvent.VK_SHIFT);
                }
                robot.keyPress(keyCode);
                robot.keyRelease(keyCode);
                if (needShift) {
                    robot.keyRelease(KeyEvent.VK_SHIFT);
                }
                return;
            }
        }

        // 对于非 ASCII 字符 (如中文)，使用剪贴板
        typeViaClipboard(String.valueOf(c));
    }

    /**
     * 判断字符是否需要 Shift 键
     */
    private boolean isShiftRequired(char c) {
        return "~!@#$%^&*()_+{}|:\"<>?".indexOf(c) >= 0;
    }

    /**
     * 通过剪贴板输入文本 (支持中文)
     */
    public void typeViaClipboard(String text) {
        // 保存原剪贴板内容
        java.awt.datatransfer.Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        java.awt.datatransfer.StringSelection selection = new java.awt.datatransfer.StringSelection(text);
        clipboard.setContents(selection, selection);

        // Command+V 粘贴
        robot.keyPress(KeyEvent.VK_META);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_META);

        delay(100);
    }

    /**
     * 按下组合键
     */
    public void pressKeys(int... keyCodes) {
        for (int keyCode : keyCodes) {
            robot.keyPress(keyCode);
        }
        for (int i = keyCodes.length - 1; i >= 0; i--) {
            robot.keyRelease(keyCodes[i]);
        }
    }

    /**
     * 按下单个键
     */
    public void pressKey(int keyCode) {
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }

    /**
     * 常用快捷键：Command+C
     */
    public void copy() {
        pressKeys(KeyEvent.VK_META, KeyEvent.VK_C);
        log.info("执行复制 (Command+C)");
    }

    /**
     * 常用快捷键：Command+V
     */
    public void paste() {
        pressKeys(KeyEvent.VK_META, KeyEvent.VK_V);
        log.info("执行粘贴 (Command+V)");
    }

    /**
     * 常用快捷键：Command+A
     */
    public void selectAll() {
        pressKeys(KeyEvent.VK_META, KeyEvent.VK_A);
        log.info("执行全选 (Command+A)");
    }

    /**
     * 常用快捷键：Command+S
     */
    public void save() {
        pressKeys(KeyEvent.VK_META, KeyEvent.VK_S);
        log.info("执行保存 (Command+S)");
    }

    /**
     * 常用快捷键：Command+Z
     */
    public void undo() {
        pressKeys(KeyEvent.VK_META, KeyEvent.VK_Z);
        log.info("执行撤销 (Command+Z)");
    }

    /**
     * 按 Enter 键
     */
    public void pressEnter() {
        pressKey(KeyEvent.VK_ENTER);
        log.info("按下 Enter");
    }

    /**
     * 按 Escape 键
     */
    public void pressEscape() {
        pressKey(KeyEvent.VK_ESCAPE);
        log.info("按下 Escape");
    }

    /**
     * 按 Tab 键
     */
    public void pressTab() {
        pressKey(KeyEvent.VK_TAB);
        log.info("按下 Tab");
    }

    /**
     * 按 Delete/Backspace 键
     */
    public void pressBackspace() {
        pressKey(KeyEvent.VK_BACK_SPACE);
        log.info("按下 Backspace");
    }

    /**
     * 延迟执行
     */
    public void delay(int ms) {
        robot.delay(ms);
    }

    /**
     * 获取当前鼠标位置
     */
    public Point getMouseLocation() {
        return MouseInfo.getPointerInfo().getLocation();
    }

    /**
     * 执行结果 - 包含详细的执行状态和偏差信息
     * 用于支持 Agent 的反思和修正
     */
    @Data
    public static class ExecutionResult {
        private String actionType; // 操作类型: moveTo, click, doubleClick 等
        private boolean success; // 是否成功
        private String message; // 详细消息
        private Point requestedAiCoord; // 请求的 AI 坐标
        private Point targetLogicalCoord; // 目标逻辑坐标
        private Point actualLogicalCoord; // 实际逻辑坐标
        private int deviationX; // X 偏差（像素）
        private int deviationY; // Y 偏差（像素）
        private long executionTimeMs; // 执行耗时

        /**
         * 获取偏差描述
         */
        public String getDeviationDescription() {
            if (deviationX == 0 && deviationY == 0) {
                return "无偏差";
            }
            StringBuilder sb = new StringBuilder();
            if (deviationX != 0) {
                sb.append(deviationX > 0 ? "向右" : "向左").append(Math.abs(deviationX)).append("px");
            }
            if (deviationY != 0) {
                if (sb.length() > 0)
                    sb.append(", ");
                sb.append(deviationY > 0 ? "向下" : "向上").append(Math.abs(deviationY)).append("px");
            }
            return sb.toString();
        }

        /**
         * 生成给 LLM 的反馈信息
         */
        public String toFeedback() {
            StringBuilder sb = new StringBuilder();
            sb.append(success ? "✅ " : "❌ ").append(message);

            if (!success || (Math.abs(deviationX) > 5 || Math.abs(deviationY) > 5)) {
                sb.append("\n📍 偏差信息: ").append(getDeviationDescription());
                sb.append("\n💡 建议: ");
                if (deviationX > 0)
                    sb.append("减小X坐标 ");
                if (deviationX < 0)
                    sb.append("增大X坐标 ");
                if (deviationY > 0)
                    sb.append("减小Y坐标 ");
                if (deviationY < 0)
                    sb.append("增大Y坐标 ");
            }

            return sb.toString();
        }
    }
}
