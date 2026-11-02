package io.github.virresh.matvt.engine.impl;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;

import io.github.virresh.matvt.R;
import io.github.virresh.matvt.helper.Helper;
import io.github.virresh.matvt.view.MouseCursorView;
import io.github.virresh.matvt.view.OverlayView;

public class MouseEmulationEngine {
    private static final String LOG_TAG = "MATVT-Root";
    private static final int BOSS_HOLD_MS = 800;
    private static final int FRAME_MS = 30;
    private static final int NO_KEY = -1;

    private final PointerControl pointer;
    private final RootInput rootInput;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Integer> consumedKeys = new HashSet<>();
    private final Runnable hideCursor;
    private AccessibilityService service;
    private boolean enabled;
    private boolean enabling;
    private boolean scrollMode;
    private boolean destroyed;
    private int modeGeneration;
    private int motionGeneration;
    private int motionKey = NO_KEY;
    private int momentum;
    private Runnable motion;
    private int heldBossKey = NO_KEY;
    private boolean bossLongPress;
    private Runnable bossHold;
    private int clickKey = NO_KEY;
    private boolean touchAccepted;
    private Runnable touchHeartbeat;

    public MouseEmulationEngine(Context context, OverlayView overlay) {
        rootInput = new RootInput(context.getApplicationInfo().sourceDir);
        MouseCursorView cursor = new MouseCursorView(context);
        overlay.addFullScreenLayer(cursor);
        pointer = new PointerControl(overlay, cursor);
        hideCursor = pointer::disappear;
        pointer.disappear();
    }

    public void init(AccessibilityService service) {
        this.service = service;
    }

    public boolean perform(KeyEvent event) {
        if (destroyed) return false;
        boolean handled = handleKey(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && handled) {
            consumedKeys.add(event.getKeyCode());
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            // Do not leak an unmatched key-up if root failed or the mode changed mid-press.
            handled |= consumedKeys.remove(event.getKeyCode());
        }
        return handled;
    }

    private boolean handleKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == Helper.getActivationKey(service) || key == heldBossKey) {
            handleBossKey(event);
            return true;
        }
        if (key == KeyEvent.KEYCODE_INFO) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) toggleMouse();
            return true;
        }
        if (!enabled) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_BACK
                || key == KeyEvent.KEYCODE_HOME || key == KeyEvent.KEYCODE_APP_SWITCH
                || key == KeyEvent.KEYCODE_POWER)) {
            cancelClick();
        }

        if (isClickKey(key)) {
            handleClick(event);
            return true;
        }

        int direction = directionForKey(key);
        if (direction == NO_KEY) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && clickKey == NO_KEY) {
            startMotion(key, direction, scrollMode || isColorKey(key));
        } else if (event.getAction() == KeyEvent.ACTION_UP && key == motionKey) {
            stopMotion();
            scheduleHide();
        }
        return true;
    }

    private void handleBossKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (heldBossKey != NO_KEY || event.getRepeatCount() != 0) return;
            heldBossKey = event.getKeyCode();
            bossLongPress = false;
            bossHold = () -> {
                bossLongPress = true;
                toggleMouse();
            };
            handler.postDelayed(bossHold, BOSS_HOLD_MS);
        } else if (event.getAction() == KeyEvent.ACTION_UP && heldBossKey == event.getKeyCode()) {
            handler.removeCallbacks(bossHold);
            heldBossKey = NO_KEY;
            if (!event.isCanceled() && !bossLongPress) {
                if (event.getEventTime() - event.getDownTime() >= BOSS_HOLD_MS) {
                    toggleMouse();
                } else if (enabled) {
                    stopMotion();
                    cancelClick();
                    scrollMode = !scrollMode;
                    pointer.reset();
                    pointer.reappear();
                    scheduleHide();
                    showMessage(scrollMode ? R.string.mode_scroll : R.string.mode_mouse);
                }
            }
        }
    }

    private void toggleMouse() {
        if (enabled || enabling) {
            disableMouse();
            showMessage(R.string.mode_dpad);
            return;
        }
        enabling = true;
        int generation = ++modeGeneration;
        showMessage(R.string.root_requesting);
        boolean accepted = rootInput.prepare(result -> handler.post(() -> {
            if (destroyed || generation != modeGeneration) return;
            enabling = false;
            if (!result.isSuccess()) {
                rootFailed(result);
                return;
            }
            enabled = true;
            scrollMode = false;
            pointer.reset();
            pointer.reappear();
            scheduleHide();
            Log.i(LOG_TAG, "Root ready in " + result.elapsedMs + " ms");
            showMessage(R.string.mode_mouse);
        }));
        if (!accepted) {
            enabling = false;
            showMessage(R.string.root_busy);
        }
    }

    private void handleClick(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() != 0 || clickKey != NO_KEY) return;
            stopMotion();
            handler.removeCallbacks(hideCursor);
            pointer.reappear();
            clickKey = event.getKeyCode();
            PointF point = pointer.getPointerLocationOnScreen();
            touchAccepted = rootInput.down(Math.round(point.x), Math.round(point.y),
                    result -> handler.post(() -> reportTouch("down", result)));
            if (touchAccepted) {
                touchHeartbeat = new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || !enabled || clickKey == NO_KEY || !touchAccepted) return;
                        rootInput.heartbeat(result -> handler.post(() -> {
                            if (!result.isSuccess()) reportTouch("heartbeat", result);
                        }));
                        handler.postDelayed(this, 1000);
                    }
                };
                handler.postDelayed(touchHeartbeat, 1000);
            } else {
                showMessage(R.string.root_busy);
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP && event.getKeyCode() == clickKey) {
            if (touchHeartbeat != null) handler.removeCallbacks(touchHeartbeat);
            if (touchAccepted) {
                // This is accepted even if the server has not acknowledged DOWN yet.
                boolean cancelled = event.isCanceled();
                rootInput.finishTouch(cancelled,
                        result -> handler.post(() -> reportTouch(cancelled ? "cancel" : "up", result)));
            }
            touchAccepted = false;
            clickKey = NO_KEY;
            scheduleHide();
        }
    }

    private void reportTouch(String name, RootInput.Result result) {
        if (destroyed) return;
        if (!result.isSuccess() && !enabled && !enabling) {
            Log.e(LOG_TAG, "Root " + name + " cleanup: " + result.output);
            return;
        }
        reportGesture(name, result);
    }

    private void startMotion(int key, int direction, boolean scroll) {
        stopMotion();
        motionKey = key;
        momentum = 0;
        int generation = motionGeneration;
        handler.removeCallbacks(hideCursor);
        motion = new Runnable() {
            @Override
            public void run() {
                if (destroyed || !enabled || generation != motionGeneration) return;
                pointer.reappear();
                if (!scroll) {
                    pointer.move(direction, momentum++);
                    handler.postDelayed(this, FRAME_MS);
                    return;
                }
                PointF point = pointer.getPointerLocationOnScreen();
                Rect bounds = pointer.getScreenBounds();
                String command = RootGesture.scroll(Math.round(point.x), Math.round(point.y),
                        bounds.left, bounds.top, bounds.right, bounds.bottom,
                        -PointerControl.dirX[direction], -PointerControl.dirY[direction]);
                boolean accepted = rootInput.swipe(command, result -> handler.post(() -> {
                    if (destroyed) return;
                    if (!reportGesture("scroll", result)) return;
                    if (enabled && generation == motionGeneration) {
                        // Repeat only after completion, never enqueue swipes at the cursor frame rate.
                        handler.postDelayed(this, FRAME_MS);
                    }
                }));
                if (!accepted) handler.postDelayed(this, FRAME_MS);
            }
        };
        handler.post(motion);
    }

    private boolean reportGesture(String name, RootInput.Result result) {
        if (!result.isSuccess()) {
            rootFailed(result);
            return false;
        }
        Log.i(LOG_TAG, "Root " + name + ": exit=0, duration=" + result.elapsedMs + " ms");
        return true;
    }

    private void rootFailed(RootInput.Result result) {
        Log.e(LOG_TAG, "Root input failed: exit=" + result.exitCode + ", " + result.output);
        disableMouse();
        showMessage(R.string.root_unavailable);
    }

    private void stopMotion() {
        motionGeneration++;
        motionKey = NO_KEY;
        if (motion != null) handler.removeCallbacks(motion);
        motion = null;
    }

    private void cancelClick() {
        if (touchHeartbeat != null) handler.removeCallbacks(touchHeartbeat);
        // Also promote an already queued UP to CANCEL when leaving mouse mode.
        rootInput.finishTouch(true, result -> handler.post(() -> reportTouch("cancel", result)));
        touchAccepted = false;
        clickKey = NO_KEY;
    }

    private void disableMouse() {
        modeGeneration++;
        enabled = false;
        enabling = false;
        scrollMode = false;
        stopMotion();
        cancelClick();
        handler.removeCallbacks(hideCursor);
        pointer.disappear();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideCursor);
        handler.postDelayed(hideCursor, 30000);
    }

    private void showMessage(int resource) {
        Toast.makeText(service, resource, Toast.LENGTH_SHORT).show();
    }

    public void interrupt() {
        disableMouse();
        if (bossHold != null) handler.removeCallbacks(bossHold);
        heldBossKey = NO_KEY;
    }

    public void destroy() {
        destroyed = true;
        interrupt();
        handler.removeCallbacksAndMessages(null);
        rootInput.close();
    }

    private static boolean isClickKey(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private static boolean isColorKey(int key) {
        return key == KeyEvent.KEYCODE_PROG_RED || key == KeyEvent.KEYCODE_PROG_GREEN
                || key == KeyEvent.KEYCODE_PROG_YELLOW || key == KeyEvent.KEYCODE_PROG_BLUE;
    }

    private static int directionForKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_PROG_GREEN:
                return PointerControl.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_PROG_RED:
                return PointerControl.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_PROG_BLUE:
                return PointerControl.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_PROG_YELLOW:
                return PointerControl.RIGHT;
            default:
                return NO_KEY;
        }
    }
}
