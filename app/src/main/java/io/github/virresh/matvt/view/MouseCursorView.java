package io.github.virresh.matvt.view;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.view.View;

import io.github.virresh.matvt.helper.Helper;

/**
 * Draw a Mouse Cursor on screen
 */
public class MouseCursorView extends View {
    private final PointF mPointerLocation = new PointF();
    private final SharedPreferences preferences;
    // SharedPreferences holds listeners weakly; retain this instance for the view's lifetime.
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (prefs, key) -> {
        if (Helper.isCursorPreference(key)) updateFromPreferences();
    };
    private CursorDrawable pointerDrawable;

    public MouseCursorView(Context context) {
        super(context);
        setWillNotDraw(false);
        preferences = Helper.getPreferences(context);
        updateFromPreferences();
    }

    public void updateFromPreferences() {
        int sizeStep = Helper.getMouseSizePref(getContext());
        String style = Helper.getMouseIconPref(getContext());
        if (pointerDrawable == null || !pointerDrawable.matches(sizeStep, style)) {
            pointerDrawable = new CursorDrawable(sizeStep, style);
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        updateFromPreferences();
    }

    @Override
    protected void onDetachedFromWindow() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        // Keep the arrow tip, rather than the image's top-left corner, on the root tap coordinate.
        canvas.translate(mPointerLocation.x - pointerDrawable.getHotspotX(),
                mPointerLocation.y - pointerDrawable.getHotspotY());
        pointerDrawable.draw(canvas);
        canvas.restoreToCount(save);
    }

    public void updatePosition(PointF p) {
        mPointerLocation.x = p.x;
        mPointerLocation.y = p.y;
        this.postInvalidate();
    }
}
