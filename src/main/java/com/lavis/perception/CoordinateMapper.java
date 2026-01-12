package com.lavis.perception;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.util.List;
import java.util.Optional;

/**
 * M2 决策模块 - 坐标映射器
 * 负责将 LLM 返回的元素 ID 或描述转换为精确的屏幕坐标
 * 
 * 功能:
 * 1. ID -> 精确中心坐标 (从 AXDumper 缓存查表)
 * 2. 描述 -> 模糊匹配 -> 坐标
 * 3. 压缩图像坐标 <-> 屏幕坐标转换
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoordinateMapper {

    private final AXDumper axDumper;
    private final ScreenCapturer screenCapturer;
    
    /**
     * 映射结果
     */
    public record MappingResult(
        boolean found,
        int x,
        int y,
        String elementId,
        String elementName,
        MappingSource source
    ) {
        public Point toPoint() {
            return new Point(x, y);
        }
        
        public static MappingResult notFound() {
            return new MappingResult(false, 0, 0, null, null, MappingSource.NOT_FOUND);
        }
        
        public static MappingResult fromElement(UIElement element) {
            Point center = element.getCenter();
            return new MappingResult(
                true, 
                center.x, 
                center.y, 
                element.getId(), 
                element.getName(),
                MappingSource.ELEMENT_ID
            );
        }
        
        public static MappingResult fromVision(int x, int y) {
            return new MappingResult(true, x, y, null, null, MappingSource.VISION_COORDINATE);
        }
    }
    
    /**
     * 映射来源
     */
    public enum MappingSource {
        ELEMENT_ID,         // 通过元素 ID 精确匹配
        ELEMENT_NAME,       // 通过元素名称模糊匹配
        ELEMENT_DESCRIPTION,// 通过描述匹配
        VISION_COORDINATE,  // 直接使用视觉坐标
        NOT_FOUND           // 未找到
    }
    
    /**
     * 核心方法: 解析目标并返回坐标
     * 
     * 支持多种输入格式:
     * - 元素 ID: "btn_5", "txt_0"
     * - 元素名称: "提交", "搜索框"
     * - 坐标字符串: "(150, 300)", "150,300"
     * - 描述性语言: "蓝色的提交按钮"
     * 
     * @param target LLM 返回的目标描述
     * @return 映射结果
     */
    public MappingResult resolveTarget(String target) {
        if (target == null || target.isBlank()) {
            log.warn("目标为空");
            return MappingResult.notFound();
        }
        
        String cleanTarget = target.trim();
        
        // 1. 尝试解析为元素 ID (如 btn_5, txt_0)
        if (isElementIdFormat(cleanTarget)) {
            UIElement element = axDumper.getElementById(cleanTarget);
            if (element != null) {
                log.info("通过元素 ID 定位: {} -> ({},{})", cleanTarget, 
                    element.getCenter().x, element.getCenter().y);
                return MappingResult.fromElement(element);
            }
        }
        
        // 2. 尝试解析为坐标 (如 "(150, 300)" 或 "150,300")
        Optional<Point> coord = parseCoordinate(cleanTarget);
        if (coord.isPresent()) {
            Point p = coord.get();
            log.info("通过坐标字符串定位: {} -> ({},{})", cleanTarget, p.x, p.y);
            return MappingResult.fromVision(p.x, p.y);
        }
        
        // 3. 尝试通过名称模糊匹配
        List<UIElement> byName = axDumper.findElementsByName(cleanTarget);
        if (!byName.isEmpty()) {
            UIElement best = byName.get(0);
            log.info("通过名称匹配定位: {} -> {} at ({},{})", 
                cleanTarget, best.getId(), best.getCenter().x, best.getCenter().y);
            return new MappingResult(
                true, 
                best.getCenter().x, 
                best.getCenter().y,
                best.getId(),
                best.getName(),
                MappingSource.ELEMENT_NAME
            );
        }
        
        // 4. 尝试通过描述匹配 (包含关键词)
        UIElement byDesc = findByDescription(cleanTarget);
        if (byDesc != null) {
            log.info("通过描述匹配定位: {} -> {} at ({},{})",
                cleanTarget, byDesc.getId(), byDesc.getCenter().x, byDesc.getCenter().y);
            return new MappingResult(
                true,
                byDesc.getCenter().x,
                byDesc.getCenter().y,
                byDesc.getId(),
                byDesc.getName(),
                MappingSource.ELEMENT_DESCRIPTION
            );
        }
        
        log.warn("无法定位目标: {}", cleanTarget);
        return MappingResult.notFound();
    }
    
    /**
     * 通过元素 ID 直接获取坐标
     */
    public MappingResult resolveById(String elementId) {
        UIElement element = axDumper.getElementById(elementId);
        if (element != null) {
            return MappingResult.fromElement(element);
        }
        return MappingResult.notFound();
    }
    
    /**
     * 将 AI 返回的压缩图像坐标转换为屏幕坐标
     */
    public Point convertFromImageCoordinate(int imageX, int imageY) {
        double ratio = screenCapturer.getCompressionRatio();
        int screenX = (int) (imageX / ratio);
        int screenY = (int) (imageY / ratio);
        log.debug("图像坐标 ({},{}) -> 屏幕坐标 ({},{}), 比例: {}", 
            imageX, imageY, screenX, screenY, ratio);
        return new Point(screenX, screenY);
    }
    
    /**
     * 将屏幕坐标转换为压缩图像坐标
     */
    public Point convertToImageCoordinate(int screenX, int screenY) {
        double ratio = screenCapturer.getCompressionRatio();
        int imageX = (int) (screenX * ratio);
        int imageY = (int) (screenY * ratio);
        return new Point(imageX, imageY);
    }
    
    /**
     * 检查是否为元素 ID 格式
     */
    private boolean isElementIdFormat(String str) {
        return str.matches("^(btn|txt|lnk|chk|lbl|img|elm)_\\d+$");
    }
    
    /**
     * 解析坐标字符串
     * 支持格式: "(x, y)", "x,y", "x y"
     */
    private Optional<Point> parseCoordinate(String str) {
        // 尝试匹配 (x, y) 或 x,y 或 x y
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "\\(?\\s*(\\d+)\\s*[,\\s]\\s*(\\d+)\\s*\\)?"
        );
        java.util.regex.Matcher matcher = pattern.matcher(str);
        
        if (matcher.find()) {
            try {
                int x = Integer.parseInt(matcher.group(1));
                int y = Integer.parseInt(matcher.group(2));
                return Optional.of(new Point(x, y));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
    
    /**
     * 通过描述查找元素 (关键词匹配)
     */
    private UIElement findByDescription(String description) {
        String lower = description.toLowerCase();
        List<UIElement> elements = axDumper.getLastScanResult();
        
        // 提取关键词
        String[] keywords = lower.split("\\s+");
        
        UIElement bestMatch = null;
        int bestScore = 0;
        
        for (UIElement element : elements) {
            int score = 0;
            String elemText = (element.getName() + " " + 
                              (element.getDescription() != null ? element.getDescription() : "") + " " +
                              element.getRole()).toLowerCase();
            
            for (String keyword : keywords) {
                if (elemText.contains(keyword)) {
                    score++;
                }
            }
            
            // 额外匹配角色关键词
            if (lower.contains("按钮") && element.isButton()) score += 2;
            if (lower.contains("button") && element.isButton()) score += 2;
            if (lower.contains("输入") && element.isInputField()) score += 2;
            if (lower.contains("input") && element.isInputField()) score += 2;
            if (lower.contains("链接") && element.isLink()) score += 2;
            if (lower.contains("link") && element.isLink()) score += 2;
            
            if (score > bestScore) {
                bestScore = score;
                bestMatch = element;
            }
        }
        
        // 至少要匹配一个关键词
        return bestScore > 0 ? bestMatch : null;
    }
    
    /**
     * 刷新 UI 元素缓存
     */
    public void refreshCache() {
        axDumper.quickScan();
    }
    
    /**
     * 获取缓存的元素数量
     */
    public int getCachedElementCount() {
        return axDumper.getLastScanResult().size();
    }
}

