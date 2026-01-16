package com.lavis;

import com.lavis.action.BezierMouseUtils;

import java.awt.Point;
import java.util.List;

public class MouseTest {
    public static void main(String[] args) throws Exception {
        System.out.println("Starting Mouse Test...");

        Point start = new Point(100, 100);
        Point end = new Point(800, 600);

        System.out.println("Testing Bezier Path Generation...");
        List<Point> path = BezierMouseUtils.generatePath(start, end, 100);
        System.out.println("Path generated with " + path.size() + " points.");

        for (int i = 0; i < Math.min(10, path.size()); i++) {
            System.out.println("Point " + i + ": " + path.get(i));
        }

        System.out.println("Simulating Driver usage (without real Robot to avoid interference if headless)...");
        // In a real run, we would instantiate RobotDriver, but here we just check
        // logic.

        System.out.println("Test Complete.");
    }
}
