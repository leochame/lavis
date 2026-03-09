package com.lavis.perception;

import com.lavis.websocket.WorkflowEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.UUID;

/**
 * 感知模块 - 屏幕截图器
 * * 【坐标系统】
 * 使用 Gemini 标准坐标系统：
 * - 范围: 0-999 归一化坐标（1000x1000 网格，共 1000 items值）
 * - 格式: [y_min, x_min, y_max, x_max] (注意 Y 在 X 前面)
 * - 转换: geminiToLogical() / logicalToGemini()
 * * 负责高频截取屏幕，支持 Retina 缩放压缩
 * 支持在截图上绘制鼠标位置和点击标记
 */
@Slf4j
@Component
public class ScreenCapturer {

    private final Robot robot;
    private final double scaleX;
    private final double scaleY;

    @Autowired(required = false)
    private WorkflowEventService workflowEventService;

    // 截图前隐藏窗口的etc待时间（毫seconds）
    private static final int WINDOW_HIDE_DELAY_MS = 100;

    // 截图压缩宽度 (仅用于减少 token 消耗，不影响坐标系统)
    private static final int COMPRESS_WIDTH = 768;

    // ========================================
    // Context Engineering: 感知去重configuration
    // ========================================

    @Value("${lavis.perception.dedup.enabled:true}")
    private boolean dedupEnabled;

    @Value("${lavis.perception.dedup.threshold:10}")
    private int dedupThreshold;  // 汉明距离阈值，默认 10 (约 15% 变化)

    // 感知哈希缓存
    private volatile long lastImageHash = 0;
    private volatile String lastImageId = null;
    private volatile String lastImageBase64 = null;

    // ========================================
    // Gemini 坐标系统常量
    // ========================================

    /**
     * Gemini 坐标归一化范围 (0-999)
     * 根据 Gemini API 文档，坐标范围是 0-999（1000x1000 网格，共 1000 items值）
     */
    public static final int COORD_MAX = 999;

    // 鼠标标记样式
    private static final Color CURSOR_COLOR = new Color(255, 0, 0, 200);
    private static final Color CURSOR_OUTLINE = Color.WHITE;
    private static final Color CLICK_MARKER_COLOR = new Color(0, 255, 0, 180);

    // 网格样式
    private static final int GRID_DIVISIONS = 10;  // 10x10 网格 (每格代表 100 items坐标单位)
    private static final Color GRID_LINE_COLOR = new Color(255, 255, 0, 60);
    private static final Color GRID_MAJOR_COLOR = new Color(255, 165, 0, 100);
    private static final Color GRID_LABEL_BG = new Color(0, 0, 0, 150);
    private static final Color GRID_LABEL_COLOR = new Color(255, 255, 200);

    // 最后一times点击位置 (逻辑屏幕坐标)
    private volatile Point lastClickPosition = null;
    private volatile long lastClickTime = 0;

    public ScreenCapturer() throws AWTException {
        this.robot = new Robot();

        AffineTransform transform = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration()
                .getDefaultTransform();
        this.scaleX = transform.getScaleX();
        this.scaleY = transform.getScaleY();

        log.info("ScreenCapturer initialize - Retina 缩放: {}x{}, 坐标系: Gemini 0-{}",
                scaleX, scaleY, COORD_MAX);
    }

    // ========================================
    // Gemini 边界框支持
    // ========================================

    /**
     * Gemini 边界框
     * 格式: [y_min, x_min, y_max, x_max] (Y 在 X 前面!)
     */
    public record BoundingBox(int yMin, int xMin, int yMax, int xMax) {

        public static BoundingBox fromArray(int[] coords) {
            if (coords == null || coords.length != 4) {
                throw new IllegalArgumentException("need 4 items坐标: [y_min, x_min, y_max, x_max]");
            }
            return new BoundingBox(coords[0], coords[1], coords[2], coords[3]);
        }

        /** 中心点 X (Gemini 坐标) */
        public int centerX() { return (xMin + xMax) / 2; }

        /** 中心点 Y (Gemini 坐标) */
        public int centerY() { return (yMin + yMax) / 2; }

        /** 宽度 (Gemini 坐标) */
        public int width() { return xMax - xMin; }

        /** 高度 (Gemini 坐标) */
        public int height() { return yMax - yMin; }

        @Override
        public String toString() {
            return String.format("BBox[y:%d-%d, x:%d-%d, center:(%d,%d)]",
                    yMin, yMax, xMin, xMax, centerX(), centerY());
        }
    }

    /**
     * 解析 Gemini 返回的边界框characters符串
     * 支持: "[123, 456, 789, 1000]" 或 "123, 456, 789, 1000"
     */
    public BoundingBox parseBoundingBox(String bboxStr) {
        if (bboxStr == null || bboxStr.isBlank()) {
            throw new IllegalArgumentException("边界框characters符串为空");
        }

        String cleaned = bboxStr.replaceAll("[\\[\\]\\s]", "");
        String[] parts = cleaned.split(",");

        if (parts.length != 4) {
            throw new IllegalArgumentException("invalid的边界框格式: " + bboxStr);
        }

        int[] coords = new int[4];
        for (int i = 0; i < 4; i++) {
            coords[i] = Integer.parseInt(parts[i].trim());
            // 钳位到有效范围
            coords[i] = Math.max(0, Math.min(COORD_MAX, coords[i]));
        }

        BoundingBox bbox = BoundingBox.fromArray(coords);
        log.debug("📍 解析边界框: {} -> {}", bboxStr, bbox);
        return bbox;
    }

    // ========================================
    // 坐标转换 (Gemini 0-999 <-> 逻辑屏幕)
    // ========================================

    /**
     * Gemini 坐标 (0-999) → 逻辑屏幕坐标
     * * 【精度说明】
     *   - Gemini API 提供 1000 items坐标值 (0-999)，无法精确表示所有像素位置
     *   - 精度损失：每items Gemini 坐标单位 ≈ (screen.width-1)/999 像素
     *   - 示例：1920px 屏幕 → 每单位 ≈ 1.922px，最大误差约 ±0.96px
     *   - 示例：2560px 屏幕 → 每单位 ≈ 2.56px，最大误差约 ±1.28px
     *   - 这是 API 限制，无法避免。使用 Math.round() 四舍五入最小化误差
     * * 【精度改进】使用 Math.round() 进lines四舍五入，而不是直接截断，提高坐标转换精度
     * * 【修复】增加边界钳位，确保坐标不超出 [0, width-1]
     * * 【修正】根据 Gemini API 文档，坐标范围是 0-999，映射到屏幕坐标 [0, width-1]
     */
    public Point toLogical(int geminiX, int geminiY) {
        Dimension screen = getScreenSize();
        // will  0-999 映射到 0 到 screen.width-1
        // 使用 (screen.width - 1) 确保 999 映射到屏幕右边缘
        // 处理边界情况：if屏幕宽度为 1，直接返回 0
        // 精度：每items Gemini 单位 = (screen.width-1)/999 像素，使用四舍五入最小化误差
        double xDouble = screen.width > 1 ? (double) geminiX / COORD_MAX * (screen.width - 1) : 0;
        double yDouble = screen.height > 1 ? (double) geminiY / COORD_MAX * (screen.height - 1) : 0;
        // 四舍五入后钳位到 [0, width-1] 防止溢出
        int x = (int) Math.min(screen.width - 1, Math.max(0, Math.round(xDouble)));
        int y = (int) Math.min(screen.height - 1, Math.max(0, Math.round(yDouble)));

        if (log.isDebugEnabled()) {
            log.debug("坐标转换: Gemini({},{}) -> 逻辑({},{}) [屏幕 {}x{}] (原始计算值: {:.2f}, {:.2f})",
                    geminiX, geminiY, x, y, screen.width, screen.height, 
                    String.format("%.2f", xDouble), String.format("%.2f", yDouble));
        }

        return new Point(x, y);
    }

    /**
     * 边界框中心点 → 逻辑屏幕坐标
     */
    public Point bboxCenterToLogical(BoundingBox bbox) {
        return toLogical(bbox.centerX(), bbox.centerY());
    }

    /**
     * 边界框 → 逻辑屏幕矩形
     */
    public Rectangle bboxToLogicalRect(BoundingBox bbox) {
        Point topLeft = toLogical(bbox.xMin, bbox.yMin);
        Point bottomRight = toLogical(bbox.xMax, bbox.yMax);
        return new Rectangle(topLeft.x, topLeft.y,
                bottomRight.x - topLeft.x, bottomRight.y - topLeft.y);
    }

    /**
     * 逻辑屏幕坐标 → Gemini 坐标 (0-999)
     * * 【精度说明】同 toLogical()，精度损失是 API 限制，使用四舍五入最小化误差
     * * 【精度改进】使用 Math.round() 进lines四舍五入，提高坐标转换精度
     * * 【修正】根据 Gemini API 文档，坐标范围是 0-999
     */
    public Point toGemini(int logicalX, int logicalY) {
        Dimension screen = getScreenSize();
        // will 逻辑坐标映射到 0-999 范围
        // 使用 (screen.width - 1) 确保屏幕右边缘映射到 999
        // 处理边界情况：if屏幕宽度为 1，直接返回 0
        // 精度：使用四舍五入最小化误差
        double xDouble = screen.width > 1 ? (double) logicalX / (screen.width - 1) * COORD_MAX : 0;
        double yDouble = screen.height > 1 ? (double) logicalY / (screen.height - 1) * COORD_MAX : 0;
        int x = (int) Math.round(xDouble);
        int y = (int) Math.round(yDouble);

        // 钳位到 0-999
        x = Math.max(0, Math.min(COORD_MAX, x));
        y = Math.max(0, Math.min(COORD_MAX, y));

        return new Point(x, y);
    }

    /**
     * 精度分析：计算坐标转换的精度损失
     * @return 精度分析infocharacters符串
     */
    public String getPrecisionAnalysis() {
        Dimension screen = getScreenSize();
        double pixelsPerUnitX = screen.width > 1 ? (double) (screen.width - 1) / COORD_MAX : 0;
        double pixelsPerUnitY = screen.height > 1 ? (double) (screen.height - 1) / COORD_MAX : 0;
        double maxErrorX = pixelsPerUnitX / 2.0;  // 最大误差约为半items单位
        double maxErrorY = pixelsPerUnitY / 2.0;
        
        return String.format(
            "坐标精度分析 [屏幕 %dx%d]:\n" +
            "  - Gemini 坐标范围: 0-%d (共 %d items值)\n" +
            "  - X 轴: 每单位 ≈ %.3f 像素，最大误差约 ±%.3f 像素\n" +
            "  - Y 轴: 每单位 ≈ %.3f 像素，最大误差约 ±%.3f 像素\n" +
            "  - 说明: 精度损失是 Gemini API 限制（仅提供 1000 items坐标值），使用四舍五入最小化误差",
            screen.width, screen.height,
            COORD_MAX, COORD_MAX + 1,
            pixelsPerUnitX, maxErrorX,
            pixelsPerUnitY, maxErrorY
        );
    }

    /**
     * Gemini 坐标转换为安全的逻辑坐标（带边界检查）
     */
    public Point toLogicalSafe(int geminiX, int geminiY) {
        return toLogicalSafe(geminiX, geminiY, SafeZone.DEFAULT);
    }

    public Point toLogicalSafe(int geminiX, int geminiY, SafeZone zone) {
        Point logical = toLogical(geminiX, geminiY);
        Dimension screen = getScreenSize();

        int safeX = Math.max(zone.left, Math.min(logical.x, screen.width - zone.right));
        int safeY = Math.max(zone.top, Math.min(logical.y, screen.height - zone.bottom));

        if (safeX != logical.x || safeY != logical.y) {
            log.warn("🛡️ 坐标安全修正: Gemini({},{}) -> 逻辑({},{}) -> 安全({},{})",
                    geminiX, geminiY, logical.x, logical.y, safeX, safeY);
        }

        return new Point(safeX, safeY);
    }

    /**
     * 安全区域configuration
     */
    public static class SafeZone {
        public final int top;     // 避开菜单栏
        public final int bottom;  // 避开 Dock
        public final int left;    // 避开 Hot Corner
        public final int right;   // 避开 Hot Corner

        public SafeZone(int top, int bottom, int left, int right) {
            this.top = top;
            this.bottom = bottom;
            this.left = left;
            this.right = right;
        }

        public static final SafeZone DEFAULT = new SafeZone(28, 75, 5, 5);
        public static final SafeZone LOOSE = new SafeZone(2, 2, 2, 2);
        public static final SafeZone STRICT = new SafeZone(30, 100, 10, 10);
    }

    // ========================================
    // 屏幕截图
    // ========================================

    public BufferedImage captureScreen() {
        return captureScreen(false);
    }

    public BufferedImage captureScreen(boolean transparent) {
        try {
            if (transparent && workflowEventService != null) {
                workflowEventService.requestHideWindow();
                Thread.sleep(WINDOW_HIDE_DELAY_MS);
            }

            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            Rectangle screenRect = new Rectangle(screenSize);
            BufferedImage capture = robot.createScreenCapture(screenRect);

            log.debug("截图: {}x{} (物理) / {}x{} (逻辑)",
                    capture.getWidth(), capture.getHeight(),
                    screenSize.width, screenSize.height);

            return capture;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("截图被中断", e);
        } finally {
            if (transparent && workflowEventService != null) {
                workflowEventService.requestShowWindow();
            }
        }
    }

    public BufferedImage captureAndCompress() {
        return compressImage(captureScreen(), COMPRESS_WIDTH);
    }

    public BufferedImage compressImage(BufferedImage original, int targetWidth) {
        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();
        double ratio = (double) targetWidth / originalWidth;
        int targetHeight = (int) (originalHeight * ratio);

        BufferedImage compressed = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = compressed.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        g2d.dispose();

        return compressed;
    }

    public String imageToBase64(BufferedImage image) throws IOException {
        // 【内存安全】使用 try-with-resources 确保流关闭
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
        ImageIO.write(image, "jpg", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

    public byte[] imageToPNG(BufferedImage image) throws IOException {
        // 【内存安全】使用 try-with-resources 确保流关闭
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
        }
    }

    public String captureScreenAsBase64() throws IOException {
        return imageToBase64(captureAndCompress());
    }

    public byte[] captureScreenAsPNG() throws IOException {
        return imageToPNG(captureAndCompress());
    }

    /**
     * 截取屏幕并返回带标注的 Base64
     * 网格标签显示 Gemini 坐标 (0-999)
     */
    public String captureScreenWithCursorAsBase64() throws IOException {
        return captureScreenWithCursorAsBase64(true);
    }

    public String captureScreenWithCursorAsBase64(boolean transparent) throws IOException {
        BufferedImage original = captureScreen(transparent);
        BufferedImage compressed = compressImage(original, COMPRESS_WIDTH);

        // 绘制网格（标签显示 Gemini 坐标 0-999）
        drawGeminiGrid(compressed);

        // 绘制鼠标位置（will 物理坐标转为逻辑坐标，避免 Retina 2x 偏差）
        Point mouseLogical = getMouseLogicalLocation();
        Point mouseGemini = toGemini(mouseLogical.x, mouseLogical.y);
        Point mouseOnImage = logicalToImageCoord(mouseLogical, compressed);
        drawCursorMarker(compressed, mouseOnImage, mouseGemini);

        // 绘制上times点击位置
        if (lastClickPosition != null && (System.currentTimeMillis() - lastClickTime) < 5000) {
            Point clickGemini = toGemini(lastClickPosition.x, lastClickPosition.y);
            Point clickOnImage = logicalToImageCoord(lastClickPosition, compressed);
            drawClickMarker(compressed, clickOnImage, clickGemini);
        }

        return imageToBase64(compressed);
    }

    public byte[] captureScreenWithCursorAsPNG() throws IOException {
        BufferedImage original = captureScreen();
        BufferedImage compressed = compressImage(original, COMPRESS_WIDTH);

        drawGeminiGrid(compressed);

        // will 物理鼠标坐标转换为逻辑坐标
        Point mouseLogical = getMouseLogicalLocation();
        Point mouseGemini = toGemini(mouseLogical.x, mouseLogical.y);
        Point mouseOnImage = logicalToImageCoord(mouseLogical, compressed);
        drawCursorMarker(compressed, mouseOnImage, mouseGemini);

        if (lastClickPosition != null && (System.currentTimeMillis() - lastClickTime) < 5000) {
            Point clickGemini = toGemini(lastClickPosition.x, lastClickPosition.y);
            Point clickOnImage = logicalToImageCoord(lastClickPosition, compressed);
            drawClickMarker(compressed, clickOnImage, clickGemini);
        }

        return imageToPNG(compressed);
    }

    /**
     * 逻辑屏幕坐标 → 压缩图像坐标
     * * 【精度改进】使用四舍五入提高精度
     */
    private Point logicalToImageCoord(Point logical, BufferedImage image) {
        Dimension screen = getScreenSize();
        double ratio = (double) image.getWidth() / screen.width;
        // 使用四舍五入提高精度
        return new Point((int) Math.round(logical.x * ratio), (int) Math.round(logical.y * ratio));
    }

    /**
     * 获取鼠标的逻辑坐标
     * * 【修复】在 macOS (Java 9+) 上，MouseInfo 返回的has been 经是逻辑坐标。
     * 无需再除以 scale 因子，else会导致坐标在 Retina 屏上只有实际位置的一半。
     */
    private Point getMouseLogicalLocation() {
        return MouseInfo.getPointerInfo().getLocation();
    }

    /**
     * 绘制 Gemini 坐标网格 (标签显示 0-999)
     */
    private void drawGeminiGrid(BufferedImage image) {
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int width = image.getWidth();
        int height = image.getHeight();
        float cellW = (float) width / GRID_DIVISIONS;
        float cellH = (float) height / GRID_DIVISIONS;

        // 绘制网格线
        for (int i = 0; i <= GRID_DIVISIONS; i++) {
            boolean major = (i % 5 == 0);
            g2d.setColor(major ? GRID_MAJOR_COLOR : GRID_LINE_COLOR);
            g2d.setStroke(new BasicStroke(major ? 1.5f : 0.5f));

            int x = (int)(i * cellW);
            int y = (int)(i * cellH);
            g2d.drawLine(x, 0, x, height);
            g2d.drawLine(0, y, width, y);
        }

        // 绘制坐标标签 (Gemini 坐标 0-999)
        g2d.setFont(new Font("Arial", Font.BOLD, 9));
        FontMetrics fm = g2d.getFontMetrics();

        // 顶部 X 轴标签
        for (int i = 0; i <= GRID_DIVISIONS; i += 2) {
            int x = (int)(i * cellW);
            int geminiX = i * (COORD_MAX / GRID_DIVISIONS);
            String label = String.valueOf(geminiX);
            int labelW = fm.stringWidth(label);

            g2d.setColor(GRID_LABEL_BG);
            g2d.fillRoundRect(x - labelW/2 - 2, 2, labelW + 4, 12, 3, 3);
            g2d.setColor(GRID_LABEL_COLOR);
            g2d.drawString(label, x - labelW/2, 12);
        }

        // 左侧 Y 轴标签
        for (int i = 0; i <= GRID_DIVISIONS; i += 2) {
            int y = (int)(i * cellH);
            int geminiY = i * (COORD_MAX / GRID_DIVISIONS);
            String label = String.valueOf(geminiY);
            int labelW = fm.stringWidth(label);

            g2d.setColor(GRID_LABEL_BG);
            g2d.fillRoundRect(2, y - 6, labelW + 4, 12, 3, 3);
            g2d.setColor(GRID_LABEL_COLOR);
            g2d.drawString(label, 4, y + 4);
        }

        // 右下角说明
        String info = String.format("Gemini坐标: 0-%d", COORD_MAX);
        int infoW = fm.stringWidth(info);
        g2d.setColor(GRID_LABEL_BG);
        g2d.fillRoundRect(width - infoW - 10, height - 16, infoW + 8, 14, 3, 3);
        g2d.setColor(GRID_LABEL_COLOR);
        g2d.drawString(info, width - infoW - 6, height - 5);

        g2d.dispose();
    }

    /**
     * 绘制鼠标标记
     */
    private void drawCursorMarker(BufferedImage image, Point posOnImage, Point geminiCoord) {
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int x = posOnImage.x;
        int y = posOnImage.y;
        int size = 15;

        // 白色外框
        g2d.setColor(CURSOR_OUTLINE);
        g2d.setStroke(new BasicStroke(3));
        g2d.drawLine(x - size, y, x + size, y);
        g2d.drawLine(x, y - size, x, y + size);

        // 红色十characters
        g2d.setColor(CURSOR_COLOR);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawLine(x - size, y, x + size, y);
        g2d.drawLine(x, y - size, x, y + size);
        g2d.fillOval(x - 3, y - 3, 6, 6);

        // 坐标标签 (显示 Gemini 坐标)
        g2d.setFont(new Font("Arial", Font.BOLD, 11));
        String text = String.format("(%d,%d)", geminiCoord.x, geminiCoord.y);
        FontMetrics fm = g2d.getFontMetrics();
        int textW = fm.stringWidth(text);

        int labelX = x + size + 5;
        if (labelX + textW > image.getWidth() - 5) {
            labelX = x - size - textW - 10;
        }

        g2d.setColor(new Color(200, 0, 0, 200));
        g2d.fillRoundRect(labelX - 2, y - 15, textW + 6, 14, 4, 4);
        g2d.setColor(Color.WHITE);
        g2d.drawString(text, labelX + 1, y - 4);

        g2d.dispose();
    }

    /**
     * 绘制点击标记
     */
    private void drawClickMarker(BufferedImage image, Point posOnImage, Point geminiCoord) {
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int x = posOnImage.x;
        int y = posOnImage.y;

        // 扩散圆环
        for (int i = 0; i < 3; i++) {
            int r = 10 + i * 6;
            int alpha = 160 - i * 45;
            g2d.setColor(new Color(0, 255, 0, Math.max(alpha, 30)));
            g2d.setStroke(new BasicStroke(2));
            g2d.drawOval(x - r, y - r, r * 2, r * 2);
        }

        g2d.setColor(CLICK_MARKER_COLOR);
        g2d.fillOval(x - 4, y - 4, 8, 8);

        // 标签
        g2d.setFont(new Font("Arial", Font.BOLD, 9));
        String label = String.format("上times点击 (%d,%d)", geminiCoord.x, geminiCoord.y);
        FontMetrics fm = g2d.getFontMetrics();
        int textW = fm.stringWidth(label);

        g2d.setColor(new Color(0, 100, 0, 180));
        g2d.fillRoundRect(x - textW/2 - 3, y - 30, textW + 6, 12, 3, 3);
        g2d.setColor(Color.WHITE);
        g2d.drawString(label, x - textW/2, y - 21);

        g2d.dispose();
    }

    // ========================================
    // 点击记录
    // ========================================

    /**
     * 记录点击位置 (逻辑屏幕坐标)
     */
    public void recordClickPosition(int logicalX, int logicalY) {
        this.lastClickPosition = new Point(logicalX, logicalY);
        this.lastClickTime = System.currentTimeMillis();
        Point gemini = toGemini(logicalX, logicalY);
        log.debug("记录点击: 逻辑({},{}) = Gemini({},{})", logicalX, logicalY, gemini.x, gemini.y);
    }

    public void clearClickRecord() {
        this.lastClickPosition = null;
        this.lastClickTime = 0;
    }

    public Point getLastClickPosition() {
        return lastClickPosition;
    }

    // ========================================
    // 工具方法
    // ========================================

    public Dimension getScreenSize() {
        return Toolkit.getDefaultToolkit().getScreenSize();
    }

    public double getScaleX() {
        return scaleX;
    }

    public double getScaleY() {
        return scaleY;
    }

    public BufferedImage captureRegion(int x, int y, int width, int height) {
        return robot.createScreenCapture(new Rectangle(x, y, width, height));
    }

    // ========================================
    // Context Engineering: 感知去重 (Perceptual Deduplication)
    // ========================================

    /**
     * 截图捕获结果
     *
     * @param imageId   图片唯一标识
     * @param base64    Base64 编码的图片数据（if复用则为 null）
     * @param isReused  是否复用了上一sheets图片
     * @param hash      感知哈希值
     */
    public record ImageCapture(
            String imageId,
            String base64,
            boolean isReused,
            long hash
    ) {
        public boolean hasNewImage() {
            return !isReused && base64 != null;
        }
    }

    /**
     * 带感知去重的屏幕捕获
     *
     * ifwhen前屏幕与上一times截图的差异低于阈值，则复用上一sheets图片。
     * 这can 显著减少 Token 消耗，特别是在etc待 UI 响应时。
     *
     * @return ImageCapture 包含 imageId 和 base64（复用时 base64 为 null）
     */
    public ImageCapture captureWithDedup() throws IOException {
        return captureWithDedup(true);
    }

    /**
     * 带感知去重的屏幕捕获
     *
     * @param transparent 是否在截图前隐藏窗口
     * @return ImageCapture 包含 imageId 和 base64
     */
    public ImageCapture captureWithDedup(boolean transparent) throws IOException {
        return captureWithDedup(transparent, false);
    }

    /**
     * 带感知去重的屏幕捕获
     *
     * @param transparent 是否在截图前隐藏窗口
     * @param forceCapture 是否强制捕获新截图（忽略去重，用于工具执lines后确保获取最新状态）
     * @return ImageCapture 包含 imageId 和 base64
     */
    public ImageCapture captureWithDedup(boolean transparent, boolean forceCapture) throws IOException {
        BufferedImage original = captureScreen(transparent);
        BufferedImage compressed = compressImage(original, COMPRESS_WIDTH);

        // 计算感知哈希
        long currentHash = computeDHash(compressed);

        // 检查是否can 复用（if强制捕获，跳过去重检查）
        if (!forceCapture && dedupEnabled && lastImageHash != 0 && lastImageBase64 != null) {
            int distance = hammingDistance(currentHash, lastImageHash);

            if (distance <= dedupThreshold) {
                // 安全检查：if缓存存在，才复用；else强制重新生成
                if (lastImageBase64 != null && lastImageId != null) {
                log.debug("Screen unchanged (hamming distance: {}), reusing image: {}",
                        distance, lastImageId);
                return new ImageCapture(lastImageId, null, true, currentHash);
                } else {
                    log.warn("Cache missing during dedup, forcing new capture");
                    // 缓存丢失，强制重新生成
            }
            } else {
            log.debug("Screen changed (hamming distance: {}), capturing new image", distance);
            }
        } else if (forceCapture) {
            log.debug("Force capture requested, ignoring dedup");
        }

        // 绘制标注
        drawGeminiGrid(compressed);

        Point mouseLogical = getMouseLogicalLocation();
        Point mouseGemini = toGemini(mouseLogical.x, mouseLogical.y);
        Point mouseOnImage = logicalToImageCoord(mouseLogical, compressed);
        drawCursorMarker(compressed, mouseOnImage, mouseGemini);

        if (lastClickPosition != null && (System.currentTimeMillis() - lastClickTime) < 5000) {
            Point clickGemini = toGemini(lastClickPosition.x, lastClickPosition.y);
            Point clickOnImage = logicalToImageCoord(lastClickPosition, compressed);
            drawClickMarker(compressed, clickOnImage, clickGemini);
        }

        // 生成新图片
        String newImageId = "img_" + UUID.randomUUID().toString().substring(0, 8);
        String newBase64 = imageToBase64(compressed);

        // 更新缓存
        lastImageHash = currentHash;
        lastImageId = newImageId;
        lastImageBase64 = newBase64;

        log.debug("New image captured: {}, hash: {}", newImageId, Long.toHexString(currentHash));
        return new ImageCapture(newImageId, newBase64, false, currentHash);
    }

    /**
     * 计算差异哈希 (dHash)
     *
     * dHash 算法：
     * 1. 缩小图片到 9x8 (产生 8x8=64 位哈希)
     * 2. 转为灰度
     * 3. 比较相邻像素：左 > 右 则为 1，else为 0
     *
     * 优点：
     * - 对缩放、轻微颜色变化不敏感
     * - 计算快速，无需外部依赖
     * - 64 位哈希便于存储和比较
     */
    private long computeDHash(BufferedImage image) {
        // 缩小到 9x8
        BufferedImage small = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, 9, 8, null);
        g.dispose();

        // 计算哈希
        long hash = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = small.getRGB(x, y) & 0xFF;
                int right = small.getRGB(x + 1, y) & 0xFF;

                if (left > right) {
                    hash |= (1L << (y * 8 + x));
                }
            }
        }

        return hash;
    }

    /**
     * 计算两items哈希值的汉明距离
     *
     * 汉明距离 = 两items哈希值中不同位的数量
     * 距离越小，图片越相似
     */
    private int hammingDistance(long hash1, long hash2) {
        return Long.bitCount(hash1 ^ hash2);
    }

    /**
     * 清除去重缓存
     * 在need强制刷新时调用
     */
    public void clearDedupCache() {
        lastImageHash = 0;
        lastImageId = null;
        lastImageBase64 = null;
        log.debug("Dedup cache cleared");
    }

    /**
     * 获取上一sheets图片的 ID
     */
    public String getLastImageId() {
        return lastImageId;
    }

    /**
     * 获取上一sheets图片的 Base64
     */
    public String getLastImageBase64() {
        return lastImageBase64;
    }

    /**
     * 获取去重统计info
     */
    public DedupStats getDedupStats() {
        return new DedupStats(dedupEnabled, dedupThreshold, lastImageId != null);
    }

    /**
     * 去重统计info
     */
    public record DedupStats(
            boolean enabled,
            int threshold,
            boolean hasCachedImage
    ) {}
}