package io.github.virresh.matvt.helper;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

public class Helper {

    public static final int DEFAULT_BOSS_KEY = 289;

    static final String PREFS_ID = "MATVT";
    static final String PREF_KEY_CB_OVERRIDE_STAT = "CB_OVERRIDE_STAT";
    static final String PREF_KEY_CB_OVERRIDE_VAL = "CB_OVERRIDE_VAL";
    static final String PREF_KEY_MOUSE_ICON = "MOUSE_ICON";
    static final String PREF_KEY_MOUSE_SIZE = "MOUSE_SIZE";
    private static final String PREF_DENSITY_ENABLED = "DENSITY_ENABLED";
    private static final String PREF_DENSITY_DPI = "DENSITY_DPI";
    private static final String PREF_DENSITY_KEY = "DENSITY_KEY";
    private static boolean capturingDensityKey;
    private static boolean mouseModeEnabled;

    public static boolean isDensityEnabled(Context ctx) {
        return getPreferences(ctx).getBoolean(PREF_DENSITY_ENABLED, false);
    }

    public static int getCompactDpi(Context ctx) {
        return getPreferences(ctx).getInt(PREF_DENSITY_DPI, DensitySettings.DEFAULT_DPI);
    }

    public static int defaultDensityKey() {
        return DensitySettings.DEFAULT_KEY;
    }

    public static int getDensityKey(Context ctx) {
        int key = getPreferences(ctx).getInt(PREF_DENSITY_KEY, 0);
        return key > 0 ? key : defaultDensityKey();
    }

    public static int factoryDpiHint() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? DisplayMetrics.DENSITY_DEVICE_STABLE : DisplayMetrics.DENSITY_DEFAULT;
    }

    public static void setDensityEnabled(Context ctx, boolean enabled) {
        getPreferences(ctx).edit().putBoolean(PREF_DENSITY_ENABLED, enabled).apply();
    }

    public static void saveDensitySettings(Context ctx, boolean enabled, int dpi, int key) {
        getPreferences(ctx).edit().putBoolean(PREF_DENSITY_ENABLED, enabled)
                .putInt(PREF_DENSITY_DPI, dpi).putInt(PREF_DENSITY_KEY, key).apply();
    }

    public static boolean isDensityPreference(String key) {
        return key == null || PREF_DENSITY_ENABLED.equals(key) || PREF_DENSITY_DPI.equals(key)
                || PREF_DENSITY_KEY.equals(key) || PREF_KEY_CB_OVERRIDE_STAT.equals(key)
                || PREF_KEY_CB_OVERRIDE_VAL.equals(key);
    }

    public static void setCapturingDensityKey(boolean capturing) { capturingDensityKey = capturing; }
    public static boolean isCapturingDensityKey() { return capturingDensityKey; }
    public static void setMouseModeEnabled(boolean enabled) { mouseModeEnabled = enabled; }
    public static boolean isMouseModeEnabled() { return mouseModeEnabled; }

    public static SharedPreferences getPreferences(Context ctx) {
        return ctx.getSharedPreferences(PREFS_ID, Context.MODE_PRIVATE);
    }

    public static boolean isCursorPreference(String key) {
        return key == null || PREF_KEY_MOUSE_ICON.equals(key) || PREF_KEY_MOUSE_SIZE.equals(key);
    }

    public static boolean isAccessibilityDisabled(Context ctx) {
        return !isAccServiceInstalled(ctx.getPackageName() + "/.services.MouseEventService", ctx);
    }

    public static boolean isAnotherServiceInstalled(Context ctx) {
        String fireTVSettings = "com.wolf.firetvsettings/.HomeService";
        String buttonMapper = "flar2.homebutton/.a.i";
        return isAccServiceInstalled(fireTVSettings, ctx) || isAccServiceInstalled(buttonMapper, ctx);
    }

    public static boolean isAccServiceInstalled(String serviceId, Context ctx) {
        AccessibilityManager am = (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> runningServices = null;
        if (am != null)  runningServices = am.getEnabledAccessibilityServiceList(AccessibilityEvent.TYPES_ALL_MASK);
        if (runningServices != null) {
            for (AccessibilityServiceInfo service : runningServices)
                if (serviceId.equals(service.getId())) return true;
        }
        return false;
    }

    public static boolean isOverlayDisabled(Context ctx) {
        return !Settings.canDrawOverlays(ctx);
    }

    public static boolean isOverriding(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_ID,
                Context.MODE_PRIVATE);
        return sp.getBoolean(PREF_KEY_CB_OVERRIDE_STAT, false);
    }

    @SuppressLint("ApplySharedPref")
    public static void setOverrideStatus(Context ctx, boolean val) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_ID, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putBoolean(PREF_KEY_CB_OVERRIDE_STAT, val);
        editor.commit();
    }

    public static int getOverrideValue(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_ID, Context.MODE_PRIVATE);
        return sp.getInt(PREF_KEY_CB_OVERRIDE_VAL, DEFAULT_BOSS_KEY);
    }

    public static int getActivationKey(Context ctx) {
        return isOverriding(ctx) ? getOverrideValue(ctx) : DEFAULT_BOSS_KEY;
    }

    @SuppressLint("ApplySharedPref")
    public static void setOverrideValue(Context ctx, int val) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_ID, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt(PREF_KEY_CB_OVERRIDE_VAL, val);
        editor.commit();
    }

    public static String getMouseIconPref(Context ctx) {
        return CursorAppearance.normalizeStyle(getPreferences(ctx).getString(
                PREF_KEY_MOUSE_ICON, CursorAppearance.DARK_STYLE));
    }

    public static void setMouseIconPref(Context ctx, String val) {
        getPreferences(ctx).edit().putString(PREF_KEY_MOUSE_ICON,
                CursorAppearance.normalizeStyle(val)).apply();
    }

    public static int getMouseSizePref(Context ctx) {
        return CursorAppearance.clampSizeStep(getPreferences(ctx).getInt(
                PREF_KEY_MOUSE_SIZE, CursorAppearance.DEFAULT_SIZE_STEP));
    }

    public static void setMouseSizePref(Context ctx, int val) {
        getPreferences(ctx).edit().putInt(PREF_KEY_MOUSE_SIZE,
                CursorAppearance.clampSizeStep(val)).apply();
    }

}
