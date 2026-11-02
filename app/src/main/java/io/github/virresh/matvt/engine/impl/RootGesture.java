package io.github.virresh.matvt.engine.impl;

/** Only numeric coordinates and bounded durations are passed to the root shell. */
final class RootGesture {
    private RootGesture() {}

    static String tap(int x, int y) {
        return "/system/bin/input touchscreen tap " + Math.max(0, x) + " " + Math.max(0, y);
    }

    static String longPress(int x, int y, int durationMs) {
        return swipe(x, y, x, y, durationMs);
    }

    static String swipe(int x1, int y1, int x2, int y2, int durationMs) {
        return "/system/bin/input touchscreen swipe "
                + Math.max(0, x1) + " " + Math.max(0, y1) + " "
                + Math.max(0, x2) + " " + Math.max(0, y2) + " "
                + Math.max(100, Math.min(2000, durationMs));
    }

    /** Keep a swipe nonzero even when the cursor is at an edge of the screen. */
    static String scroll(int x, int y, int left, int top, int right, int bottom,
                         int directionX, int directionY) {
        int dx = Math.min(120, Math.max(1, (right - left) / 3));
        int dy = Math.min(120, Math.max(1, (bottom - top) / 3));
        dx = Math.min(dx, Math.max(0, right - left));
        dy = Math.min(dy, Math.max(0, bottom - top));
        int startX = clamp(x, left + (directionX < 0 ? dx : 0), right - (directionX > 0 ? dx : 0));
        int startY = clamp(y, top + (directionY < 0 ? dy : 0), bottom - (directionY > 0 ? dy : 0));
        return swipe(startX, startY, startX + directionX * dx, startY + directionY * dy, 250);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
