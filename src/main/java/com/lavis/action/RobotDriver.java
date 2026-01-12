package com.lavis.action;

import com.lavis.perception.ScreenCapturer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * M3 执行模块 - 机器人驱动器
 * 封装 mouseMove, click, type 操作
 * 关键点：实现逻辑坐标与物理坐标的换算
 */
@Slf4j
@Component
public class RobotDriver {

    private final Robot robot;
    private final ScreenCapturer screenCapturer;
    
    // 默认操作延迟 (毫秒)
    private static final int DEFAULT_DELAY = 100;
    private static final int TYPE_DELAY = 50;

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
     * 移动鼠标到指定位置 (AI坐标)
     */
    public void moveTo(int geminiX, int geminiY) {
        Point robotPoint = convertToRobotCoordinates(geminiX, geminiY);
        robot.mouseMove(robotPoint.x, robotPoint.y);
        log.info("鼠标移动到: ({}, {})", robotPoint.x, robotPoint.y);
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
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        log.info("鼠标左键单击");
    }

    /**
     * 移动并点击 (AI坐标)
     */
    public void clickAt(int geminiX, int geminiY) {
        moveTo(geminiX, geminiY);
        delay(50);
        click();
        log.info("在位置 ({}, {}) 点击", geminiX, geminiY);
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
     * 移动并双击 (AI坐标)
     */
    public void doubleClickAt(int geminiX, int geminiY) {
        moveTo(geminiX, geminiY);
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
     * 移动并右键点击 (AI坐标)
     */
    public void rightClickAt(int geminiX, int geminiY) {
        moveTo(geminiX, geminiY);
        delay(50);
        rightClick();
    }

    /**
     * 拖拽操作
     */
    public void drag(int fromX, int fromY, int toX, int toY) {
        moveTo(fromX, fromY);
        delay(100);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        delay(100);
        moveTo(toX, toY);
        delay(100);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        log.info("拖拽: ({},{}) -> ({},{})", fromX, fromY, toX, toY);
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

