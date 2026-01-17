package com.lavis.action;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Bezier Curve Generator for Human-like Mouse Movement
 * 
 * 【M3-1 增强版】实现真正的拟人化鼠标移动：
 * 1. 贝塞尔曲线轨迹 - 模拟人手的弧形运动
 * 2. 速度曲线 (Easing) - 起始加速，接近目标减速
 * 3. 微抖动 (Jitter) - 模拟手部细微颤抖
 * 4. 随机延迟配置 - 规避反脚本检测
 */
public class BezierMouseUtils {

    private static final Random random = new Random();
    
    // 抖动配置
    private static final double JITTER_AMPLITUDE = 1.5;  // 抖动幅度（像素）
    private static final double JITTER_FREQUENCY = 0.3;  // 抖动频率
    
    // 速度曲线类型
    public enum EasingType {
        LINEAR,          // 线性（机械感）
        EASE_IN_OUT,     // 平滑起停（最自然）
        EASE_OUT,        // 快起慢停
        EASE_IN,         // 慢起快停
        HUMAN_LIKE       // 拟人化（带微小波动）
    }

    /**
     * 生成拟人化鼠标移动路径
     * 
     * @param start 起点
     * @param end   终点
     * @param steps 步数（越多越平滑，但越慢）
     * @return 路径点列表
     */
    public static List<Point> generatePath(Point start, Point end, int steps) {
        return generatePath(start, end, steps, EasingType.HUMAN_LIKE, true);
    }
    
    /**
     * 生成可配置的鼠标移动路径
     * 
     * @param start      起点
     * @param end        终点
     * @param steps      步数
     * @param easing     速度曲线类型
     * @param addJitter  是否添加微抖动
     * @return 路径点列表
     */
    public static List<Point> generatePath(Point start, Point end, int steps, 
                                           EasingType easing, boolean addJitter) {
        List<Point> path = new ArrayList<>();

        // 距离太近，直接返回终点
        if (start.distance(end) < 2) {
            path.add(end);
            return path;
        }
        
        // 确保至少有一定步数
        steps = Math.max(steps, 5);

        // 生成贝塞尔曲线控制点
        Point[] controls = generateControlPoints(start, end);
        Point c1 = controls[0];
        Point c2 = controls[1];

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            
            // 应用速度曲线
            double easedT = applyEasing(t, easing);

            // 贝塞尔曲线插值
            double x = cubicBezier(start.x, c1.x, c2.x, end.x, easedT);
            double y = cubicBezier(start.y, c1.y, c2.y, end.y, easedT);
            
            // 添加微抖动（除了最后几步，确保精确到达）
            if (addJitter && i < steps - 2) {
                double jitterX = Math.sin(i * JITTER_FREQUENCY * Math.PI) * JITTER_AMPLITUDE * (random.nextDouble() - 0.5);
                double jitterY = Math.cos(i * JITTER_FREQUENCY * Math.PI) * JITTER_AMPLITUDE * (random.nextDouble() - 0.5);
                x += jitterX;
                y += jitterY;
            }

            Point point = new Point((int) x, (int) y);
            
            // 避免重复点
            if (path.isEmpty() || !path.get(path.size() - 1).equals(point)) {
                path.add(point);
            }
        }

        // 确保最后一个点精确到达终点
        if (!path.get(path.size() - 1).equals(end)) {
            path.add(end);
        }

        return path;
    }
    
    /**
     * 生成拖拽路径（比普通移动更稳定，抖动更小）
     * 
     * @param start 起点
     * @param end   终点
     * @param steps 步数
     * @return 路径点列表
     */
    public static List<Point> generateDragPath(Point start, Point end, int steps) {
        // 拖拽时使用更平滑的曲线，减少抖动
        List<Point> path = new ArrayList<>();
        
        if (start.distance(end) < 2) {
            path.add(end);
            return path;
        }
        
        // 拖拽需要更多步数，更平滑
        steps = Math.max(steps, 20);
        
        // 拖拽使用更温和的控制点
        Point[] controls = generateDragControlPoints(start, end);
        Point c1 = controls[0];
        Point c2 = controls[1];
        
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            
            // 拖拽使用更平滑的 ease-in-out
            double easedT = easeInOutQuad(t);
            
            int x = (int) cubicBezier(start.x, c1.x, c2.x, end.x, easedT);
            int y = (int) cubicBezier(start.y, c1.y, c2.y, end.y, easedT);
            
            // 拖拽时只添加极微小的抖动
            if (i > 2 && i < steps - 2) {
                x += (int) ((random.nextDouble() - 0.5) * 0.5);
                y += (int) ((random.nextDouble() - 0.5) * 0.5);
            }
            
            Point point = new Point(x, y);
            if (path.isEmpty() || !path.get(path.size() - 1).equals(point)) {
                path.add(point);
            }
        }
        
        if (!path.get(path.size() - 1).equals(end)) {
            path.add(end);
        }
        
        return path;
    }
    
    /**
     * 应用速度曲线
     */
    private static double applyEasing(double t, EasingType type) {
        return switch (type) {
            case LINEAR -> t;
            case EASE_IN -> easeInQuad(t);
            case EASE_OUT -> easeOutQuad(t);
            case EASE_IN_OUT -> easeInOutQuad(t);
            case HUMAN_LIKE -> humanLikeEasing(t);
        };
    }
    
    /**
     * 拟人化速度曲线 - 带有微小的速度波动
     */
    private static double humanLikeEasing(double t) {
        // 基础的 ease-in-out
        double base = easeInOutCubic(t);
        
        // 添加微小的速度波动（模拟人手的不稳定）
        double wobble = Math.sin(t * Math.PI * 4) * 0.02 * (1 - Math.abs(2 * t - 1));
        
        return Math.max(0, Math.min(1, base + wobble));
    }
    
    // === 各种 Easing 函数 ===
    
    private static double easeInQuad(double t) {
        return t * t;
    }
    
    private static double easeOutQuad(double t) {
        return 1 - (1 - t) * (1 - t);
    }
    
    private static double easeInOutQuad(double t) {
        return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }
    
    private static double easeInOutCubic(double t) {
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    /**
     * 三次贝塞尔曲线计算
     */
    private static double cubicBezier(double p0, double p1, double p2, double p3, double t) {
        double u = 1 - t;
        double tt = t * t;
        double uu = u * u;
        double uuu = uu * u;
        double ttt = tt * t;

        return (uuu * p0) + (3 * uu * t * p1) + (3 * u * tt * p2) + (ttt * p3);
    }

    /**
     * 生成普通移动的控制点
     */
    private static Point[] generateControlPoints(Point start, Point end) {
        double distance = start.distance(end);

        // 根据距离调整偏移量
        double offsetScale = Math.min(distance / 2, 100);

        // 起点到终点的向量
        double dx = end.x - start.x;
        double dy = end.y - start.y;

        // 垂直向量
        double px = -dy;
        double py = dx;

        // 归一化
        double length = Math.sqrt(px * px + py * py);
        if (length > 0) {
            px /= length;
            py /= length;
        }

        // 随机弧形方向（左或右）
        double direction = random.nextBoolean() ? 1.0 : -1.0;

        // 控制点1：靠近起点，弧度较大
        double intensity1 = (0.2 + random.nextDouble() * 0.4) * offsetScale * direction;
        int x1 = (int) (start.x + dx * 0.25 + px * intensity1);
        int y1 = (int) (start.y + dy * 0.25 + py * intensity1);

        // 控制点2：靠近终点，弧度逐渐收敛
        double intensity2 = (0.1 + random.nextDouble() * 0.3) * offsetScale * direction;
        int x2 = (int) (start.x + dx * 0.75 + px * intensity2);
        int y2 = (int) (start.y + dy * 0.75 + py * intensity2);

        return new Point[] { new Point(x1, y1), new Point(x2, y2) };
    }
    
    /**
     * 生成拖拽的控制点（更接近直线，偏移更小）
     */
    private static Point[] generateDragControlPoints(Point start, Point end) {
        double distance = start.distance(end);
        
        // 拖拽时偏移更小
        double offsetScale = Math.min(distance / 4, 30);
        
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        
        double px = -dy;
        double py = dx;
        
        double length = Math.sqrt(px * px + py * py);
        if (length > 0) {
            px /= length;
            py /= length;
        }
        
        double direction = random.nextBoolean() ? 1.0 : -1.0;
        
        // 拖拽的控制点更接近直线
        double intensity = (0.1 + random.nextDouble() * 0.2) * offsetScale * direction;
        
        int x1 = (int) (start.x + dx * 0.33 + px * intensity);
        int y1 = (int) (start.y + dy * 0.33 + py * intensity);
        
        int x2 = (int) (start.x + dx * 0.67 + px * intensity * 0.8);
        int y2 = (int) (start.y + dy * 0.67 + py * intensity * 0.8);
        
        return new Point[] { new Point(x1, y1), new Point(x2, y2) };
    }
    
    /**
     * 计算推荐的步数（基于距离）
     * 
     * @param distance 移动距离（像素）
     * @param speed    速度因子（1.0 = 正常，2.0 = 快速，0.5 = 慢速）
     * @return 推荐步数
     */
    public static int calculateRecommendedSteps(double distance, double speed) {
        //每 15 像素一步
        int baseSteps = (int) (distance / 15);

        // 应用速度因子
        int steps = (int) (baseSteps / speed);
        
        // 限制范围
        return Math.max(3, Math.min(steps, 200));
    }
    
    /**
     * 生成随机延迟（毫秒），用于每步之间的等待
     * 模拟人类鼠标移动的速度变化
     * 
     * @param stepIndex    当前步索引
     * @param totalSteps   总步数
     * @param baseDelayMs  基础延迟（毫秒）
     * @return 建议的延迟时间
     */
    public static int generateStepDelay(int stepIndex, int totalSteps, int baseDelayMs) {
        double progress = (double) stepIndex / totalSteps;
        
        // 起始和结束时稍慢，中间较快
        double speedFactor;
        if (progress < 0.2) {
            // 起始加速阶段
            speedFactor = 0.5 + progress * 2.5;
        } else if (progress > 0.8) {
            // 结束减速阶段
            speedFactor = 0.5 + (1 - progress) * 2.5;
        } else {
            // 中间匀速阶段
            speedFactor = 1.0;
        }
        
        // 添加随机波动 (±30%)
        double randomFactor = 0.7 + random.nextDouble() * 0.6;
        
        return (int) (baseDelayMs / speedFactor * randomFactor);
    }
}
