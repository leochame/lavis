package com.lavis.action;

import com.lavis.perception.ScreenCapturer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Random;

/**
 * M3 执行模块 - 机器人驱动器
 * 封装 mouseMove, click, type 操作
 * 
 * 特性:
 * - 逻辑坐标与物理坐标的换算
 * - Bezier 曲线平滑鼠标移动 (模拟人类操作)
 * - 支持 AI 坐标和屏幕坐标两种模式
 */
@Slf4j
@Component
public class RobotDriver {

    private final Robot robot;
    private final ScreenCapturer screenCapturer;
    private final Random random = new Random();
    
    // 默认操作延迟 (毫秒)
    private static final int DEFAULT_DELAY = 100;
    private static final int TYPE_DELAY = 50;
    
    // Bezier 曲线移动配置
    @Value("${robot.smooth.enabled:true}")
    private boolean smoothMoveEnabled = true;
    
    @Value("${robot.smooth.duration.ms:300}")
    private int smoothMoveDuration = 300;  // 移动总时长
    
    @Value("${robot.smooth.steps:25}")
    private int smoothMoveSteps = 25;  // 移动步数

    public RobotDriver(ScreenCapturer screenCapturer) throws AWTException {
        this.robot = new Robot();
        this.screenCapturer = screenCapturer;
        this.robot.setAutoDelay(DEFAULT_DELAY);
        log.info("RobotDriver 初始化完成");
    }

    /**
     * 将 AI 返回的坐标转换为 Robot 使用的逻辑坐标
     * Gemini 返回的是基于压缩后图像的坐标，需要换算回屏幕逻辑坐标
     */
    public Point convertToRobotCoordinates(int geminiX, int geminiY) {
        double compressionRatio = screenCapturer.getCompressionRatio();
        
        // 将压缩图像坐标转换回原始屏幕坐标
        int screenX = (int) (geminiX / compressionRatio);
        int screenY = (int) (geminiY / compressionRatio);
        
        log.debug("坐标转换: Gemini({},{}) -> Screen({},{}), 压缩比例: {}", 
                geminiX, geminiY, screenX, screenY, compressionRatio);
        
        return new Point(screenX, screenY);
    }

    /**
     * 移动鼠标到指定位置 (AI坐标 - 基于压缩图像)
     */
    public void moveTo(int geminiX, int geminiY) {
        Point robotPoint = convertToRobotCoordinates(geminiX, geminiY);
        moveToScreenSmooth(robotPoint.x, robotPoint.y);
        log.info("鼠标移动到: ({}, {}) [AI坐标: ({}, {})]", robotPoint.x, robotPoint.y, geminiX, geminiY);
    }

    /**
     * 移动鼠标到指定位置 (屏幕逻辑坐标) - 直接移动
     */
    public void moveToScreen(int x, int y) {
        robot.mouseMove(x, y);
        log.debug("鼠标直接移动到: ({}, {})", x, y);
    }
    
    /**
     * 移动鼠标到指定位置 (屏幕逻辑坐标) - 平滑移动
     */
    public void moveToScreenSmooth(int targetX, int targetY) {
        if (!smoothMoveEnabled) {
            robot.mouseMove(targetX, targetY);
            return;
        }
        
        Point current = getMouseLocation();
        smoothMove(current.x, current.y, targetX, targetY);
    }
    
    /**
     * Bezier 曲线平滑移动
     * 使用二次贝塞尔曲线模拟人类鼠标移动轨迹
     * 
     * @param startX 起点 X
     * @param startY 起点 Y
     * @param endX 终点 X
     * @param endY 终点 Y
     */
    private void smoothMove(int startX, int startY, int endX, int endY) {
        // 计算距离
        double distance = Math.sqrt(Math.pow(endX - startX, 2) + Math.pow(endY - startY, 2));
        
        // 短距离直接移动
        if (distance < 50) {
            robot.mouseMove(endX, endY);
            return;
        }
        
        // 生成随机控制点 (模拟人类手抖动)
        Point control = generateControlPoint(startX, startY, endX, endY);
        
        // 根据距离调整步数和时长
        int steps = Math.min(smoothMoveSteps, Math.max(10, (int)(distance / 20)));
        int stepDelay = smoothMoveDuration / steps;
        
        // 沿贝塞尔曲线移动
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            
            // 应用缓动函数 (ease-out)
            t = easeOutQuad(t);
            
            // 计算贝塞尔曲线上的点
            int x = (int) quadraticBezier(startX, control.x, endX, t);
            int y = (int) quadraticBezier(startY, control.y, endY, t);
            
            // 添加微小随机抖动 (模拟手抖)
            if (i < steps - 2) {
                x += random.nextInt(3) - 1;
                y += random.nextInt(3) - 1;
            }
            
            robot.mouseMove(x, y);
            
            if (stepDelay > 0) {
                robot.delay(stepDelay);
            }
        }
        
        // 确保到达精确终点
        robot.mouseMove(endX, endY);
    }
    
    /**
     * 生成贝塞尔曲线的控制点
     * 控制点决定曲线的弯曲程度和方向
     */
    private Point generateControlPoint(int startX, int startY, int endX, int endY) {
        // 计算中点
        int midX = (startX + endX) / 2;
        int midY = (startY + endY) / 2;
        
        // 计算垂直方向的偏移
        double dx = endX - startX;
        double dy = endY - startY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        
        // 偏移量随机 (距离的 10%-30%)
        double offset = distance * (0.1 + random.nextDouble() * 0.2);
        
        // 随机选择偏移方向 (左或右)
        if (random.nextBoolean()) {
            offset = -offset;
        }
        
        // 计算垂直方向的单位向量
        double perpX = -dy / distance;
        double perpY = dx / distance;
        
        // 控制点 = 中点 + 垂直偏移
        int ctrlX = (int) (midX + perpX * offset);
        int ctrlY = (int) (midY + perpY * offset);
        
        return new Point(ctrlX, ctrlY);
    }
    
    /**
     * 二次贝塞尔曲线计算
     * B(t) = (1-t)²P0 + 2(1-t)tP1 + t²P2
     */
    private double quadraticBezier(double p0, double p1, double p2, double t) {
        double oneMinusT = 1 - t;
        return oneMinusT * oneMinusT * p0 + 2 * oneMinusT * t * p1 + t * t * p2;
    }
    
    /**
     * 缓动函数 - ease-out-quad
     * 开始快，结束慢，模拟人类移动鼠标的惯性
     */
    private double easeOutQuad(double t) {
        return t * (2 - t);
    }
    
    /**
     * 缓动函数 - ease-in-out-quad (备用)
     */
    @SuppressWarnings("unused")
    private double easeInOutQuad(double t) {
        return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }

    /**
     * 单击鼠标左键
     */
    public void click() {
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        log.info("鼠标左键单击");
    }

    /**
     * 移动并点击 (AI坐标 - 基于压缩图像)
     */
    public void clickAt(int geminiX, int geminiY) {
        moveTo(geminiX, geminiY);
        delay(50);
        click();
        log.info("在 AI 坐标 ({}, {}) 点击", geminiX, geminiY);
    }
    
    /**
     * 移动并点击 (屏幕坐标) - 核心方法
     * 推荐使用此方法，精确度更高
     */
    public void clickAtScreen(int screenX, int screenY) {
        moveToScreenSmooth(screenX, screenY);
        delay(50);
        click();
        log.info("在屏幕坐标 ({}, {}) 点击", screenX, screenY);
    }

    /**
     * 双击鼠标左键
     */
    public void doubleClick() {
        click();
        delay(80);
        click();
        log.info("鼠标左键双击");
    }

    /**
     * 移动并双击 (AI坐标)
     */
    public void doubleClickAt(int geminiX, int geminiY) {
        moveTo(geminiX, geminiY);
        delay(50);
        doubleClick();
    }
    
    /**
     * 移动并双击 (屏幕坐标)
     */
    public void doubleClickAtScreen(int screenX, int screenY) {
        moveToScreenSmooth(screenX, screenY);
        delay(50);
        doubleClick();
        log.info("在屏幕坐标 ({}, {}) 双击", screenX, screenY);
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
     * 移动并右键点击 (AI坐标)
     */
    public void rightClickAt(int geminiX, int geminiY) {
        moveTo(geminiX, geminiY);
        delay(50);
        rightClick();
    }
    
    /**
     * 移动并右键点击 (屏幕坐标)
     */
    public void rightClickAtScreen(int screenX, int screenY) {
        moveToScreenSmooth(screenX, screenY);
        delay(50);
        rightClick();
        log.info("在屏幕坐标 ({}, {}) 右键点击", screenX, screenY);
    }

    /**
     * 拖拽操作 (AI坐标)
     */
    public void drag(int fromX, int fromY, int toX, int toY) {
        moveTo(fromX, fromY);
        delay(100);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        delay(100);
        moveTo(toX, toY);
        delay(100);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        log.info("拖拽: ({},{}) -> ({},{}) [AI坐标]", fromX, fromY, toX, toY);
    }
    
    /**
     * 拖拽操作 (屏幕坐标) - 平滑拖拽
     */
    public void dragScreen(int fromX, int fromY, int toX, int toY) {
        moveToScreenSmooth(fromX, fromY);
        delay(100);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        delay(100);
        moveToScreenSmooth(toX, toY);
        delay(100);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        log.info("拖拽: ({},{}) -> ({},{}) [屏幕坐标]", fromX, fromY, toX, toY);
    }

    /**
     * 滚动鼠标滚轮
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
}

