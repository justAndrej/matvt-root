package io.github.virresh.matvt.engine.impl;

import org.junit.Test;

import static org.junit.Assert.*;

public class RootGestureTest {
    @Test
    public void scrollAtEveryScreenEdgeStaysWithinBoundsAndMovesInRequestedDirection() {
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int x : new int[]{10, 120, 249}) {
            for (int y : new int[]{20, 145, 339}) {
                for (int[] direction : directions) {
                    int[] points = swipeArguments(RootGesture.scroll(x, y, 10, 20, 249, 339,
                            direction[0], direction[1]));
                    assertTrue(points[0] >= 10 && points[0] <= 249);
                    assertTrue(points[2] >= 10 && points[2] <= 249);
                    assertTrue(points[1] >= 20 && points[1] <= 339);
                    assertTrue(points[3] >= 20 && points[3] <= 339);
                    assertEquals(direction[0], Integer.signum(points[2] - points[0]));
                    assertEquals(direction[1], Integer.signum(points[3] - points[1]));
                    assertTrue(points[4] >= 100 && points[4] <= 2000);
                }
            }
        }
    }

    @Test
    public void swipeDurationIsBounded() {
        int[] points = swipeArguments(RootGesture.swipe(120, 145, 120, 200, Integer.MAX_VALUE));
        assertEquals(120, points[0]);
        assertEquals(145, points[1]);
        assertEquals(points[0], points[2]);
        assertEquals(200, points[3]);
        assertEquals(2000, points[4]);
    }

    @Test
    public void tinyViewportCannotProduceOutOfBoundsSwipe() {
        int[] points = swipeArguments(RootGesture.scroll(0, 0, 0, 0, 0, 0, 0, -1));
        for (int index = 0; index < 4; index++) assertEquals(0, points[index]);
    }

    private static int[] swipeArguments(String command) {
        String[] arguments = command.split(" ");
        assertEquals("SWIPE", arguments[0]);
        assertEquals(6, arguments.length);
        int[] points = new int[5];
        for (int index = 0; index < points.length; index++) points[index] = Integer.parseInt(arguments[index + 1]);
        return points;
    }
}
