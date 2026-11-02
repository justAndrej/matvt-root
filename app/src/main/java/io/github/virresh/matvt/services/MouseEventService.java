package io.github.virresh.matvt.services;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import io.github.virresh.matvt.engine.impl.MouseEmulationEngine;
import io.github.virresh.matvt.helper.Helper;
import io.github.virresh.matvt.view.OverlayView;

public class MouseEventService extends AccessibilityService {

    private static final String LOG_TAG = "MATVT-Screen";
    private MouseEmulationEngine mEngine;
    private OverlayView mOverlayView;
    private KeyguardManager mKeyguardManager;
    private PowerManager mPowerManager;
    private boolean mReceiverRegistered;
    private final ScreenInputGate mInputGate = new ScreenInputGate();
    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!Intent.ACTION_SCREEN_OFF.equals(action) && !Intent.ACTION_SCREEN_ON.equals(action)
                    && !Intent.ACTION_USER_PRESENT.equals(action)) {
                return;
            }
            // Waking/unlocking must never restore mouse mode or an old activation-key timer.
            suspendMouseInput(action);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mKeyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        mPowerManager = (PowerManager) getSystemService(POWER_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        // These are protected system broadcasts; no manifest receiver or extra permission is needed.
        registerReceiver(mScreenReceiver, filter);
        mReceiverRegistered = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if ((type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) && isInputBlocked()) {
            suspendMouseInput("keyguard/window change");
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        boolean blocked = isInputBlocked();
        if (blocked) suspendMouseInput("screen off or keyguard");
        if (mInputGate.shouldPassThrough(blocked, event.getDeviceId(), event.getKeyCode(),
                event.getDownTime(), event.getAction(), event.getRepeatCount())) {
            return false;
        }
        if (Helper.isAnotherServiceInstalled(this) &&
                event.getKeyCode() == KeyEvent.KEYCODE_HOME) return true;
        if (Helper.isOverlayDisabled(this)) {
            releaseEngine();
            return false;
        }
        if (mEngine == null) init();
        return mEngine.perform(event);
    }

    @Override
    public void onInterrupt() {
        if (mEngine != null) mEngine.interrupt();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        mInputGate.suspend();
        releaseEngine();
        if (!isInputBlocked() && Settings.canDrawOverlays(this)) {
            init();
        }
    }

    private boolean isInputBlocked() {
        // isKeyguardLocked also covers non-PIN lock screens, unlike isDeviceLocked.
        return mPowerManager == null || mKeyguardManager == null
                || !mPowerManager.isInteractive() || mKeyguardManager.isKeyguardLocked();
    }

    private void suspendMouseInput(String reason) {
        mInputGate.suspend();
        if (mEngine != null) Log.i(LOG_TAG, "Mouse input suspended: " + reason);
        // destroy() cancels any contact, timers and pending root activation, then closes the helper.
        releaseEngine();
    }

    private void init() {
        mOverlayView = new OverlayView(this);
        mEngine = new MouseEmulationEngine(this, mOverlayView);
        mEngine.init(this);
    }

    private void releaseEngine() {
        if (mEngine != null) {
            mEngine.destroy();
            mEngine = null;
        }
        if (mOverlayView != null) {
            mOverlayView.dispose();
            mOverlayView = null;
        }
    }

    @Override
    public void onDestroy() {
        if (mReceiverRegistered) {
            unregisterReceiver(mScreenReceiver);
            mReceiverRegistered = false;
        }
        releaseEngine();
        super.onDestroy();
    }
}
