package io.github.virresh.matvt.helper;

/** Validation shared by the settings screen and key handler. Key codes are Android KeyEvent codes. */
public final class DensitySettings {
    public static final int DEFAULT_DPI = 100;
    public static final int DEFAULT_KEY = 290; // Sonim XP5900 Speaker key (Android KeyEvent code).
    public static final int MIN_DPI = 72;
    public static final int HOLD_MS = 800;

    private DensitySettings() {}

    public static boolean isValidDpi(int dpi, int factoryDpi) {
        return dpi >= MIN_DPI && dpi < factoryDpi;
    }

    public static boolean isAvailable(boolean mouseEnabled, boolean featureEnabled, int key,
                                      int mouseActivationKey, int audioMode) {
        // AudioManager.MODE_NORMAL == 0. Ringing, calls and VoIP keep their native controls.
        return mouseEnabled && featureEnabled && audioMode == 0 && isKeyAllowed(key, mouseActivationKey);
    }

    public static boolean isKeyAllowed(int key, int mouseActivationKey) {
        if (key <= 0 || key == mouseActivationKey) return false;
        switch (key) {
            case 3: case 4: case 5: case 6: // Home, Back, Call, End call
            case 17: case 18: // Star / hash: text input and Sonim keyguard
            case 19: case 20: case 21: case 22: case 23: // D-pad
            case 24: case 25: case 26: // Volume / power
            case 66: case 79: case 160: case 164: case 165: // Enter, headset, numpad Enter, mute, info
            case 183: case 184: case 185: case 186: case 187: // Scroll color keys / app switch
            case 223: case 224: // Sleep / wake
                return false;
            default:
                return true;
        }
    }
}
