package com.lavis.perception;

import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@Component
public class ScreenCapturer {

    private final Robot robot;
    private final double scaleX;
    private final double scaleY;
    
    // 目标压缩宽度 (从 2880px 压缩至 768px 以减少 token 消耗)
    // 768px 足够 AI 识别 UI 元素，同时大幅减少 API 成本
    private static final int TARGET_WIDTH = 768;

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
     */
    public BufferedImage captureScreen() {
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Rectangle screenRect = new Rectangle(screenSize);
        BufferedImage capture = robot.createScreenCapture(screenRect);
        log.debug("截取屏幕: {}x{}", capture.getWidth(), capture.getHeight());
        return capture;
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
     * 截取屏幕并返回 Base64 (一站式方法)
     */
    public String captureScreenAsBase64() throws IOException {
        BufferedImage compressed = captureAndCompress();
        return imageToBase64(compressed);
    }

    /**
     * 获取屏幕尺寸
     */
    public Dimension getScreenSize() {
        return Toolkit.getDefaultToolkit().getScreenSize();
    }

    /**
     * 获取图像压缩比例 (用于坐标换算)
     */
    public double getCompressionRatio() {
        Dimension screenSize = getScreenSize();
        return (double) TARGET_WIDTH / screenSize.width;
    }
}

