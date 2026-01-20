package com.lavis.perception;

import com.lavis.websocket.WorkflowEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * M1 感知模块 - 屏幕截图器
 * 负责高频截取屏幕，支持 Retina 缩放压缩
 * 支持在截图上绘制鼠标位置和点击标记，便于 AI 反思
 * 支持截图时隐藏前端窗口（透视功能）
 */
@Slf4j
@Component
public class ScreenCapturer {

    private final Robot robot;
    private final double scaleX;
    private final double scaleY;
    
    // 工作流事件服务（用于通知前端隐藏/显示窗口）
    @Autowired(required = false)
    private WorkflowEventService workflowEventService;
    
    // 截图前隐藏窗口的等待时间（毫秒）
    private static final int WINDOW_HIDE_DELAY_MS = 100;
    
    // 目标压缩宽度 (从 2880px 压缩至 768px 以减少 token 消耗)
    // 768px 足够 AI 识别 UI 元素，同时大幅减少 API 成本
    private static final int TARGET_WIDTH = 768;
    
    // 鼠标标记样式
    private static final Color CURSOR_COLOR = new Color(255, 0, 0, 200);  // 红色半透明
    private static final Color CURSOR_OUTLINE = Color.WHITE;
    
    // 最后点击位置标记
    private static final Color CLICK_MARKER_COLOR = new Color(0, 255, 0, 180);  // 绿色半透明
    
    // 20x20 网格标注样式
    private static final int GRID_ROWS = 20;
    private static final int GRID_COLS = 20;
    private static final Color GRID_LINE_COLOR = new Color(255, 255, 0, 60);  // 黄色半透明网格线
    private static final Color GRID_MAJOR_COLOR = new Color(255, 165, 0, 100);  // 橙色主网格线 (每5格)
    private static final Color GRID_LABEL_BG = new Color(0, 0, 0, 150);  // 标签背景
    private static final Color GRID_LABEL_COLOR = new Color(255, 255, 200);  // 标签文字颜色
    
    // 记录最后一次点击的位置 (用于反思时显示)
    // 注意：存储的是【逻辑屏幕坐标】
    private volatile Point lastClickPosition = null;
    private volatile long lastClickTime = 0;

    public ScreenCapturer() throws AWTException {
        this.robot = new Robot();
        
        // 获取 Retina 屏幕缩放比例
        AffineTransform transform = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration()
                .getDefaultTransform();
        this.scaleX = transform.getScaleX();
        this.scaleY = transform.getScaleY();
        
        log.info("ScreenCapturer 初始化完成 - 屏幕缩放比例: {}x{}", scaleX, scaleY);
    }

    /**
     * 获取屏幕缩放比例X
     */
    public double getScaleX() {
        return scaleX;
    }

    /**
     * 获取屏幕缩放比例Y
     */
    public double getScaleY() {
        return scaleY;
    }

    /**
     * 截取全屏并返回原始图像
     * 注意：在 Retina 屏幕上，返回的图像尺寸是物理像素（如 2880x1800），
     * 而不是逻辑尺寸（如 1440x900）
     */
    public BufferedImage captureScreen() {
        return captureScreen(false);
    }
    
    /**
     * 截取全屏并返回原始图像（支持透视模式）
     * @param transparent 是否启用透视模式（隐藏前端窗口后再截图）
     */
    public BufferedImage captureScreen(boolean transparent) {
        try {
            // 如果启用透视模式，先通知前端隐藏窗口
            if (transparent && workflowEventService != null) {
                workflowEventService.requestHideWindow();
                Thread.sleep(WINDOW_HIDE_DELAY_MS);
            }
            
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            Rectangle screenRect = new Rectangle(screenSize);
            BufferedImage capture = robot.createScreenCapture(screenRect);
            
            log.debug("截取屏幕: 物理像素 {}x{}, 逻辑尺寸 {}x{}", 
                    capture.getWidth(), capture.getHeight(),
                    screenSize.width, screenSize.height);
            
            return capture;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("截图被中断", e);
        } finally {
            // 截图完成后，通知前端显示窗口
            if (transparent && workflowEventService != null) {
                workflowEventService.requestShowWindow();
            }
        }
    }

    /**
     * 截取屏幕并压缩到目标宽度
     */
    public BufferedImage captureAndCompress() {
        BufferedImage original = captureScreen();
        return compressImage(original, TARGET_WIDTH);
    }

    /**
     * 截取屏幕指定区域
     */
    public BufferedImage captureRegion(int x, int y, int width, int height) {
        Rectangle region = new Rectangle(x, y, width, height);
        return robot.createScreenCapture(region);
    }

    /**
     * 压缩图像到指定宽度，保持宽高比
     */
    public BufferedImage compressImage(BufferedImage original, int targetWidth) {
        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();
        
        double ratio = (double) targetWidth / originalWidth;
        int targetHeight = (int) (originalHeight * ratio);
        
        BufferedImage compressed = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = compressed.createGraphics();
        
        // 使用高质量缩放
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        g2d.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        g2d.dispose();
        
        log.debug("图像压缩: {}x{} -> {}x{}", originalWidth, originalHeight, targetWidth, targetHeight);
        return compressed;
    }

    /**
     * 将图像转换为 Base64 字符串 (用于发送给 AI)
     * 使用 JPEG 格式以减小文件大小
     */
    public String imageToBase64(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // 使用 JPEG 格式，文件更小，减少 token 消耗
        ImageIO.write(image, "jpg", baos);
        byte[] bytes = baos.toByteArray();
        log.debug("图像大小: {} KB", bytes.length / 1024);
        return Base64.getEncoder().encodeToString(bytes);
    }
    
    /**
     * 将图像转换为 PNG 字节数组
     * Gemini Computer Use API 要求使用 PNG 格式
     */
    public byte[] imageToPNG(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        byte[] bytes = baos.toByteArray();
        log.debug("PNG 图像大小: {} KB", bytes.length / 1024);
        return bytes;
    }
    
    /**
     * 截取屏幕并返回 PNG 字节数组 (用于 Gemini Computer Use)
     */
    public byte[] captureScreenAsPNG() throws IOException {
        BufferedImage compressed = captureAndCompress();
        return imageToPNG(compressed);
    }
    
    /**
     * 截取屏幕并返回 PNG 字节数组，包含鼠标和点击标记
     * (用于 Gemini Computer Use)
     */
    public byte[] captureScreenWithCursorAsPNG() throws IOException {
        BufferedImage original = captureScreen();
        Dimension logicalSize = getScreenSize();
        
        // 先压缩图像
        BufferedImage compressed = compressImage(original, TARGET_WIDTH);
        
        // 计算压缩比例
        double compressionRatio = (double) TARGET_WIDTH / logicalSize.width;
        
        // 获取鼠标位置
        Point mousePos = MouseInfo.getPointerInfo().getLocation();
        Point mousePosOnImage = new Point(
            (int)(mousePos.x * compressionRatio), 
            (int)(mousePos.y * compressionRatio)
        );
        
        // 绘制网格和标记
        drawGrid(compressed, logicalSize, compressionRatio);
        drawCursorMarkerOnCompressed(compressed, mousePosOnImage, mousePos);
        
        // 绘制点击位置
        if (lastClickPosition != null && (System.currentTimeMillis() - lastClickTime) < 5000) {
            Point clickPosOnImage = new Point(
                (int)(lastClickPosition.x * compressionRatio), 
                (int)(lastClickPosition.y * compressionRatio)
            );
            drawClickMarkerOnCompressed(compressed, clickPosOnImage, lastClickPosition);
        }
        
        return imageToPNG(compressed);
    }

    /**
     * 截取屏幕并返回 Base64 (一站式方法)
     */
    public String captureScreenAsBase64() throws IOException {
        BufferedImage compressed = captureAndCompress();
        return imageToBase64(compressed);
    }
    
    /**
     * 截取屏幕并返回 Base64，同时绘制：
     * 1. 20x20 网格标注 - 帮助 AI 精确定位（标注显示逻辑屏幕坐标）
     * 2. 鼠标位置标记 - 红色十字准星（显示逻辑屏幕坐标）
     * 3. 上次点击位置 - 绿色圆环（显示逻辑屏幕坐标）
     * 
     * 【坐标系统说明】
     * - 逻辑坐标：macOS 报告的屏幕坐标（如 1440x900），AI 直接使用这个坐标
     * - 物理坐标：Retina 屏幕的实际像素（如 2880x1800），仅截图内部使用
     * - 图像压缩：为了减少 token 消耗，图像被压缩到 768px 宽度，但坐标系统仍使用逻辑屏幕坐标
     */
    public String captureScreenWithCursorAsBase64() throws IOException {
        return captureScreenWithCursorAsBase64(true); // 默认启用透视模式
    }
    
    /**
     * 截取屏幕并返回 Base64（支持透视模式）
     * @param transparent 是否启用透视模式（隐藏前端窗口后再截图）
     */
    public String captureScreenWithCursorAsBase64(boolean transparent) throws IOException {
        BufferedImage original = captureScreen(transparent);
        Dimension logicalSize = getScreenSize();  // 逻辑屏幕尺寸
        
        // 先压缩图像（仅用于减少 token 消耗，不影响坐标系统）
        BufferedImage compressed = compressImage(original, TARGET_WIDTH);
        
        // 计算压缩比例（用于将逻辑坐标映射到压缩图像上的位置）
        double compressionRatio = (double) TARGET_WIDTH / logicalSize.width;
        
        // 获取鼠标位置（逻辑坐标）- 不再转换，直接使用
        Point mousePos = MouseInfo.getPointerInfo().getLocation();
        
        // 计算鼠标在压缩图像上的显示位置（用于绘制）
        Point mousePosOnImage = new Point(
            (int)(mousePos.x * compressionRatio), 
            (int)(mousePos.y * compressionRatio)
        );
        
        log.debug("鼠标位置: 逻辑({},{}) -> 图像位置({},{})", 
                mousePos.x, mousePos.y, mousePosOnImage.x, mousePosOnImage.y);
        
        // 在压缩后的图像上绘制 20x20 网格（标注显示逻辑坐标）
        drawGrid(compressed, logicalSize, compressionRatio);
        
        // 绘制鼠标位置标记（显示逻辑坐标）
        drawCursorMarkerOnCompressed(compressed, mousePosOnImage, mousePos);
        
        // 如果有最近的点击位置，也绘制出来（5秒内有效）
        if (lastClickPosition != null && (System.currentTimeMillis() - lastClickTime) < 5000) {
            Point clickPosOnImage = new Point(
                (int)(lastClickPosition.x * compressionRatio), 
                (int)(lastClickPosition.y * compressionRatio)
            );
            log.debug("点击位置: 逻辑({},{}) -> 图像位置({},{})", 
                    lastClickPosition.x, lastClickPosition.y, clickPosOnImage.x, clickPosOnImage.y);
            drawClickMarkerOnCompressed(compressed, clickPosOnImage, lastClickPosition);
        }
        
        return imageToBase64(compressed);
    }
    
    /**
     * 在压缩后的图像上绘制 20x20 网格
     * 网格标注显示逻辑屏幕坐标，帮助 AI 理解坐标系统
     * 
     * @param image 压缩后的图像
     * @param logicalSize 逻辑屏幕尺寸
     * @param compressionRatio 压缩比例（逻辑坐标到图像坐标）
     */
    private void drawGrid(BufferedImage image, Dimension logicalSize, double compressionRatio) {
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        int width = image.getWidth();
        int height = image.getHeight();
        float cellWidth = (float) width / GRID_COLS;
        float cellHeight = (float) height / GRID_ROWS;
        
        // 绘制垂直线
        for (int col = 0; col <= GRID_COLS; col++) {
            int x = (int) (col * cellWidth);
            // 每5格用粗线
            if (col % 5 == 0) {
                g2d.setColor(GRID_MAJOR_COLOR);
                g2d.setStroke(new BasicStroke(1.5f));
            } else {
                g2d.setColor(GRID_LINE_COLOR);
                g2d.setStroke(new BasicStroke(0.5f));
            }
            g2d.drawLine(x, 0, x, height);
        }
        
        // 绘制水平线
        for (int row = 0; row <= GRID_ROWS; row++) {
            int y = (int) (row * cellHeight);
            // 每5格用粗线
            if (row % 5 == 0) {
                g2d.setColor(GRID_MAJOR_COLOR);
                g2d.setStroke(new BasicStroke(1.5f));
            } else {
                g2d.setColor(GRID_LINE_COLOR);
                g2d.setStroke(new BasicStroke(0.5f));
            }
            g2d.drawLine(0, y, width, y);
        }
        
        // 绘制坐标标签（顶部和左侧）- 显示逻辑屏幕坐标
        g2d.setFont(new Font("Arial", Font.BOLD, 9));
        FontMetrics fm = g2d.getFontMetrics();
        
        // 顶部 X 坐标标签 (每隔5格显示逻辑坐标值)
        for (int col = 0; col <= GRID_COLS; col += 5) {
            int x = (int) (col * cellWidth);
            // 计算对应的逻辑坐标
            int logicalX = (int)(col * logicalSize.width / (double)GRID_COLS);
            String label = String.valueOf(logicalX);
            int labelWidth = fm.stringWidth(label);
            
            // 背景
            g2d.setColor(GRID_LABEL_BG);
            g2d.fillRoundRect(x - labelWidth/2 - 2, 2, labelWidth + 4, 12, 3, 3);
            // 文字
            g2d.setColor(GRID_LABEL_COLOR);
            g2d.drawString(label, x - labelWidth/2, 12);
        }
        
        // 左侧 Y 坐标标签 (每隔5格显示逻辑坐标值)
        for (int row = 0; row <= GRID_ROWS; row += 5) {
            int y = (int) (row * cellHeight);
            // 计算对应的逻辑坐标
            int logicalY = (int)(row * logicalSize.height / (double)GRID_ROWS);
            String label = String.valueOf(logicalY);
            int labelWidth = fm.stringWidth(label);
            
            // 背景
            g2d.setColor(GRID_LABEL_BG);
            g2d.fillRoundRect(2, y - 6, labelWidth + 4, 12, 3, 3);
            // 文字
            g2d.setColor(GRID_LABEL_COLOR);
            g2d.drawString(label, 4, y + 4);
        }
        
        // 在右下角显示网格说明（显示逻辑屏幕尺寸）
        String gridInfo = String.format("屏幕: %dx%d | 网格: %dx%d", 
                logicalSize.width, logicalSize.height, GRID_COLS, GRID_ROWS);
        int infoWidth = fm.stringWidth(gridInfo);
        g2d.setColor(GRID_LABEL_BG);
        g2d.fillRoundRect(width - infoWidth - 10, height - 16, infoWidth + 8, 14, 3, 3);
        g2d.setColor(GRID_LABEL_COLOR);
        g2d.drawString(gridInfo, width - infoWidth - 6, height - 5);
        
        g2d.dispose();
        log.debug("绘制 {}x{} 网格完成，逻辑屏幕: {}x{}", GRID_COLS, GRID_ROWS, logicalSize.width, logicalSize.height);
    }
    
    /**
     * 在压缩后的图像上绘制鼠标光标位置（红色十字准星）
     * 
     * @param image 压缩后的图像
     * @param positionOnImage 鼠标在压缩图像上的位置（用于绘制）
     * @param logicalPosition 鼠标的逻辑屏幕坐标（用于显示标签）
     */
    private void drawCursorMarkerOnCompressed(BufferedImage image, Point positionOnImage, Point logicalPosition) {
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        int x = positionOnImage.x;
        int y = positionOnImage.y;
        int size = 15;  // 压缩图上用较小的标记
        
        // 绘制白色外框
        g2d.setColor(CURSOR_OUTLINE);
        g2d.setStroke(new BasicStroke(3));
        g2d.drawLine(x - size, y, x + size, y);
        g2d.drawLine(x, y - size, x, y + size);
        
        // 绘制红色十字
        g2d.setColor(CURSOR_COLOR);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawLine(x - size, y, x + size, y);
        g2d.drawLine(x, y - size, x, y + size);
        
        // 中心点
        g2d.fillOval(x - 3, y - 3, 6, 6);
        
        // 坐标标签 - 显示逻辑屏幕坐标
        g2d.setFont(new Font("Arial", Font.BOLD, 11));
        String coordText = String.format("(%d,%d)", logicalPosition.x, logicalPosition.y);
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(coordText);
        
        // 标签位置（避免超出边界）
        int labelX = x + size + 5;
        int labelY = y - 5;
        if (labelX + textWidth > image.getWidth() - 5) {
            labelX = x - size - textWidth - 10;
        }
        
        g2d.setColor(new Color(200, 0, 0, 200));
        g2d.fillRoundRect(labelX - 2, labelY - 10, textWidth + 6, 14, 4, 4);
        g2d.setColor(Color.WHITE);
        g2d.drawString(coordText, labelX + 1, labelY);
        
        g2d.dispose();
    }
    
    /**
     * 在压缩后的图像上绘制点击标记（绿色圆环）
     * 
     * @param image 压缩后的图像
     * @param positionOnImage 点击位置在压缩图像上的位置（用于绘制）
     * @param logicalPosition 点击位置的逻辑屏幕坐标（用于显示标签）
     */
    private void drawClickMarkerOnCompressed(BufferedImage image, Point positionOnImage, Point logicalPosition) {
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        int x = positionOnImage.x;
        int y = positionOnImage.y;
        
        // 绘制扩散圆环
        for (int i = 0; i < 3; i++) {
            int r = 10 + i * 6;
            int alpha = 160 - i * 45;
            g2d.setColor(new Color(0, 255, 0, Math.max(alpha, 30)));
            g2d.setStroke(new BasicStroke(2));
            g2d.drawOval(x - r, y - r, r * 2, r * 2);
        }
        
        // 中心点
        g2d.setColor(CLICK_MARKER_COLOR);
        g2d.fillOval(x - 4, y - 4, 8, 8);
        
        // 标签 - 显示逻辑坐标
        g2d.setFont(new Font("Arial", Font.BOLD, 9));
        String label = String.format("上次点击 (%d,%d)", logicalPosition.x, logicalPosition.y);
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(label);
        
        g2d.setColor(new Color(0, 100, 0, 180));
        g2d.fillRoundRect(x - textWidth/2 - 3, y - 30, textWidth + 6, 12, 3, 3);
        g2d.setColor(Color.WHITE);
        g2d.drawString(label, x - textWidth/2, y - 21);
        
        g2d.dispose();
    }
    
    
    /**
     * 记录点击位置（供外部调用，用于反思时显示）
     * 【重要】传入的是逻辑屏幕坐标，直接存储
     * @param logicalX 逻辑屏幕坐标 X
     * @param logicalY 逻辑屏幕坐标 Y
     */
    public void recordClickPosition(int logicalX, int logicalY) {
        // 直接存储逻辑屏幕坐标
        this.lastClickPosition = new Point(logicalX, logicalY);
        this.lastClickTime = System.currentTimeMillis();
        log.debug("记录点击位置: 逻辑({},{})", logicalX, logicalY);
    }
    
    /**
     * 清除点击记录
     */
    public void clearClickRecord() {
        this.lastClickPosition = null;
        this.lastClickTime = 0;
    }
    
    /**
     * 获取最后点击位置
     */
    public Point getLastClickPosition() {
        return lastClickPosition;
    }

    /**
     * 获取屏幕尺寸
     */
    public Dimension getScreenSize() {
        return Toolkit.getDefaultToolkit().getScreenSize();
    }

    /**
     * 获取 AI 到逻辑屏幕的坐标转换比例
     * 逻辑坐标 = AI坐标 / 此比例
     * 
     * 【重要】这是 RobotDriver 用来将 AI 坐标转换为 Robot 使用的逻辑坐标的比例
     * Robot.mouseMove() 使用的是逻辑坐标，不是物理像素
     */
    public double getCompressionRatio() {
        Dimension screenSize = getScreenSize();  // 逻辑屏幕尺寸
        double ratio = (double) TARGET_WIDTH / screenSize.width;
        log.debug("压缩比例: {} (TARGET_WIDTH={}, 逻辑宽度={})", ratio, TARGET_WIDTH, screenSize.width);
        return ratio;
    }
    
    /**
     * 将 AI 坐标转换为逻辑屏幕坐标
     * @param aiX AI 坐标 X（0-768）
     * @param aiY AI 坐标 Y
     * @return 逻辑屏幕坐标
     */
    public Point aiToLogical(int aiX, int aiY) {
        Dimension logicalSize = getScreenSize();
        double ratio = (double) logicalSize.width / TARGET_WIDTH;
        return new Point((int)(aiX * ratio), (int)(aiY * ratio));
    }
    
    /**
     * 【M3-2 增强】将 AI 坐标转换为安全的逻辑屏幕坐标
     * 
     * 特性：
     * 1. 越界保护 - 确保坐标在屏幕范围内
     * 2. 安全边距 - 避免触发 Hot Corners、菜单栏等
     * 3. 详细日志 - 记录修正情况
     * 
     * @param aiX AI 坐标 X（0-768）
     * @param aiY AI 坐标 Y
     * @return 安全的逻辑屏幕坐标
     */
    public Point aiToLogicalSafe(int aiX, int aiY) {
        return aiToLogicalSafe(aiX, aiY, SafeZoneConfig.DEFAULT);
    }
    
    /**
     * 使用自定义安全区配置转换坐标
     */
    public Point aiToLogicalSafe(int aiX, int aiY, SafeZoneConfig config) {
        Dimension logicalSize = getScreenSize();
        double ratio = (double) logicalSize.width / TARGET_WIDTH;
        
        int logicalX = (int)(aiX * ratio);
        int logicalY = (int)(aiY * ratio);
        
        // 原始转换结果
        int originalX = logicalX;
        int originalY = logicalY;
        
        // 安全边界
        int minX = config.leftMargin;
        int maxX = logicalSize.width - config.rightMargin;
        int minY = config.topMargin;  // macOS 菜单栏约 25px
        int maxY = logicalSize.height - config.bottomMargin;  // Dock 可能在底部
        
        // 钳位
        logicalX = Math.max(minX, Math.min(logicalX, maxX));
        logicalY = Math.max(minY, Math.min(logicalY, maxY));
        
        // 如果发生修正，记录日志
        if (logicalX != originalX || logicalY != originalY) {
            log.warn("🛡️ 坐标安全修正: AI({},{}) -> 原始逻辑({},{}) -> 安全逻辑({},{}) [边界: {}-{}, {}-{}]",
                    aiX, aiY, originalX, originalY, logicalX, logicalY,
                    minX, maxX, minY, maxY);
        }
        
        return new Point(logicalX, logicalY);
    }
    
    /**
     * 检查 AI 坐标是否在安全范围内
     */
    public boolean isAiCoordSafe(int aiX, int aiY) {
        // AI 坐标基本范围检查
        if (aiX < 0 || aiX > TARGET_WIDTH) {
            return false;
        }
        
        // 计算对应的 AI 截图高度
        Dimension logicalSize = getScreenSize();
        int aiHeight = (int)(TARGET_WIDTH * logicalSize.height / (double)logicalSize.width);
        
        if (aiY < 0 || aiY > aiHeight) {
            return false;
        }
        
        // 检查是否在安全边距内
        SafeZoneConfig config = SafeZoneConfig.DEFAULT;
        double ratio = (double) logicalSize.width / TARGET_WIDTH;
        
        int logicalX = (int)(aiX * ratio);
        int logicalY = (int)(aiY * ratio);
        
        return logicalX >= config.leftMargin 
            && logicalX <= logicalSize.width - config.rightMargin
            && logicalY >= config.topMargin 
            && logicalY <= logicalSize.height - config.bottomMargin;
    }
    
    /**
     * 获取安全的 AI 坐标范围
     */
    public SafeAiRange getSafeAiRange() {
        Dimension logicalSize = getScreenSize();
        double ratio = (double) TARGET_WIDTH / logicalSize.width;
        SafeZoneConfig config = SafeZoneConfig.DEFAULT;
        
        int aiHeight = (int)(TARGET_WIDTH * logicalSize.height / (double)logicalSize.width);
        
        return new SafeAiRange(
            (int)(config.leftMargin * ratio),
            (int)((logicalSize.width - config.rightMargin) * ratio),
            (int)(config.topMargin * ratio),
            (int)((logicalSize.height - config.bottomMargin) * ratio),
            TARGET_WIDTH,
            aiHeight
        );
    }
    
    /**
     * 安全区域配置
     */
    public static class SafeZoneConfig {
        public final int topMargin;      // 顶部边距（避开菜单栏）
        public final int bottomMargin;   // 底部边距（避开 Dock）
        public final int leftMargin;     // 左边距（避开 Hot Corner）
        public final int rightMargin;    // 右边距（避开 Hot Corner）
        
        public SafeZoneConfig(int top, int bottom, int left, int right) {
            this.topMargin = top;
            this.bottomMargin = bottom;
            this.leftMargin = left;
            this.rightMargin = right;
        }
        
        // 默认配置：菜单栏约25px，Dock约70px，四角各留5px
        public static final SafeZoneConfig DEFAULT = new SafeZoneConfig(28, 75, 5, 5);
        
        // 宽松配置：只避开极端边界
        public static final SafeZoneConfig LOOSE = new SafeZoneConfig(2, 2, 2, 2);
        
        // 严格配置：更大的安全边距
        public static final SafeZoneConfig STRICT = new SafeZoneConfig(30, 100, 10, 10);
    }
    
    /**
     * 安全 AI 坐标范围
     */
    public record SafeAiRange(
        int minX,
        int maxX, 
        int minY,
        int maxY,
        int fullWidth,
        int fullHeight
    ) {
        @Override
        public String toString() {
            return String.format("SafeRange[X: %d-%d, Y: %d-%d, Full: %dx%d]",
                    minX, maxX, minY, maxY, fullWidth, fullHeight);
        }
    }
    
    /**
     * 将逻辑屏幕坐标转换为 AI 坐标
     * @param logicalX 逻辑屏幕坐标 X
     * @param logicalY 逻辑屏幕坐标 Y
     * @return AI 坐标（0-768 范围）
     */
    public Point logicalToAi(int logicalX, int logicalY) {
        Dimension logicalSize = getScreenSize();
        double ratio = (double) TARGET_WIDTH / logicalSize.width;
        return new Point((int)(logicalX * ratio), (int)(logicalY * ratio));
    }
    
    /**
     * 获取目标宽度
     */
    public int getTargetWidth() {
        return TARGET_WIDTH;
    }
}

