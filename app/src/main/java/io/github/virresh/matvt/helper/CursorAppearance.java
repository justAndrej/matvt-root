package io.github.virresh.matvt.helper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Cursor heights in physical screen pixels for the XP5900's 240 x 320 display. */
public final class CursorAppearance {
    public static final int DEFAULT_SIZE_STEP = 2;
    private static final int[] HEIGHTS_PX = {16, 20, 24, 28, 32};
    public static final int MAX_SIZE_STEP = HEIGHTS_PX.length - 1;

    // Keep the persisted names compatible with the original phone port.
    public static final String DARK_STYLE = "Default";
    public static final String LIGHT_STYLE = "Light";
    public static final List<String> STYLES = Collections.unmodifiableList(
            Arrays.asList(DARK_STYLE, LIGHT_STYLE));

    private CursorAppearance() {}

    public static int clampSizeStep(int step) {
        return Math.max(0, Math.min(MAX_SIZE_STEP, step));
    }

    public static int heightPx(int step) {
        return HEIGHTS_PX[clampSizeStep(step)];
    }

    public static String normalizeStyle(String style) {
        return LIGHT_STYLE.equalsIgnoreCase(style) ? LIGHT_STYLE : DARK_STYLE;
    }
}
