package io.github.virresh.matvt.view;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.virresh.matvt.helper.CursorAppearance;

/** The same density-independent vector geometry is used in the overlay and previews. */
public final class CursorDrawable extends Drawable {
    private static final float VIEWPORT_WIDTH = 18f;
    private static final float VIEWPORT_HEIGHT = 24f;
    private static final float TIP_X = 1f;
    private static final float TIP_Y = 1f;

    private final Path arrow = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int sizeStep;
    private final String style;
    private final int height;
    private final int width;
    private final int fillColor;
    private final int outlineColor;
    private int alpha = 255;

    public CursorDrawable(int sizeStep, String style) {
        this.sizeStep = CursorAppearance.clampSizeStep(sizeStep);
        this.style = CursorAppearance.normalizeStyle(style);
        height = CursorAppearance.heightPx(this.sizeStep);
        width = Math.round(height * VIEWPORT_WIDTH / VIEWPORT_HEIGHT);
        boolean light = CursorAppearance.LIGHT_STYLE.equals(this.style);
        fillColor = light ? Color.WHITE : Color.BLACK;
        outlineColor = light ? Color.BLACK : Color.WHITE;

        arrow.moveTo(TIP_X, TIP_Y);
        arrow.lineTo(1f, 17f);
        arrow.lineTo(5.5f, 13.5f);
        arrow.lineTo(9.5f, 22.5f);
        arrow.lineTo(13f, 21f);
        arrow.lineTo(9f, 12.5f);
        arrow.lineTo(16f, 12.5f);
        arrow.close();

        paint.setStrokeWidth(1.4f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setBounds(0, 0, width, height);
    }

    public boolean matches(int sizeStep, String style) {
        return this.sizeStep == CursorAppearance.clampSizeStep(sizeStep)
                && this.style.equals(CursorAppearance.normalizeStyle(style));
    }

    public float getHotspotX() {
        return TIP_X * getBounds().width() / VIEWPORT_WIDTH;
    }

    public float getHotspotY() {
        return TIP_Y * getBounds().height() / VIEWPORT_HEIGHT;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        int save = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / VIEWPORT_WIDTH, bounds.height() / VIEWPORT_HEIGHT);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fillColor);
        paint.setAlpha(alpha);
        canvas.drawPath(arrow, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(outlineColor);
        paint.setAlpha(alpha);
        canvas.drawPath(arrow, paint);
        canvas.restoreToCount(save);
    }

    @Override
    public int getIntrinsicWidth() {
        return width;
    }

    @Override
    public int getIntrinsicHeight() {
        return height;
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
