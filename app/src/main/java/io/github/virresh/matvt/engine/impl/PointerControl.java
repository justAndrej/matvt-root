package io.github.virresh.matvt.engine.impl;

import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import io.github.virresh.matvt.view.MouseCursorView;
import io.github.virresh.matvt.view.OverlayView;

public class PointerControl {
    private static String LOG_TAG = "POINTER_CONTROL";

    // constants
    public static int LEFT = 0;
    public static int UP = 1;
    public static int RIGHT = 2;
    public static int DOWN = 3;

    // Left, Up, Right, Down
    public static int[] dirX = {-1, 0, 1,  0};
    public static int[] dirY = { 0, -1, 0, 1};

    // pointer location in screen coordinates
    private final PointF mPointerLocation = new PointF();

    // view to display the pointer
    private final OverlayView mPointerLayerView;

    // cursor view
    private final MouseCursorView mCursorView;

    // constructor
    PointerControl(@NonNull OverlayView pv, MouseCursorView mv){
        mPointerLayerView = pv;
        mCursorView = mv;
        reset();
    }

    /**
     * Reset pointer location by centering it
     */
    public void reset () {
        mCursorView.updateFromPreferences();
        mPointerLocation.x = mPointerLayerView.getWidth() / 2f;
        mPointerLocation.y = mPointerLayerView.getHeight() / 2f;
        Log.i(LOG_TAG, "View W, H: " + mPointerLayerView.getWidth() + " " + mPointerLayerView.getHeight());
        Log.i(LOG_TAG, "Location X, Y: " + mPointerLocation.x + " " + mPointerLocation.y);
        mCursorView.updatePosition(mPointerLocation);
    }

    public void disappear () {
        if (mCursorView.getVisibility() == View.VISIBLE) {
            mCursorView.setVisibility(View.INVISIBLE);
        }
    }

    public void reappear () {
        if (mCursorView.getVisibility() == View.INVISIBLE) {
            mCursorView.setVisibility(View.VISIBLE);
        }
    }

    public void move (int direction, int momentum) {
        int speed = Math.max(1, Math.min(momentum + 1, 20));
        int movementX = dirX[direction] * speed;
        int movementY = dirY[direction] * speed;
        int width = Math.max(1, mPointerLayerView.getWidth());
        int height = Math.max(1, mPointerLayerView.getHeight());

        mPointerLocation.x = (mPointerLocation.x + movementX + width) % width;
        mPointerLocation.y = (mPointerLocation.y + movementY + height) % height;

        mCursorView.updatePosition(mPointerLocation);
    }

    @NonNull
    PointF getPointerLocationOnScreen() {
        Rect bounds = getScreenBounds();
        return new PointF(Math.max(bounds.left, Math.min(bounds.right, bounds.left + mPointerLocation.x)),
                Math.max(bounds.top, Math.min(bounds.bottom, bounds.top + mPointerLocation.y)));
    }

    Rect getScreenBounds() {
        int[] location = new int[2];
        mPointerLayerView.getLocationOnScreen(location);
        // right/bottom are inclusive here: input must not receive x == width or y == height.
        return new Rect(location[0], location[1],
                location[0] + Math.max(0, mPointerLayerView.getWidth() - 1),
                location[1] + Math.max(0, mPointerLayerView.getHeight() - 1));
    }
}
