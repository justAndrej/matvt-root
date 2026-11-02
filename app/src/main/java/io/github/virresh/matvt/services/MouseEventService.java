package io.github.virresh.matvt.services;

import android.accessibilityservice.AccessibilityService;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import io.github.virresh.matvt.engine.impl.MouseEmulationEngine;
import io.github.virresh.matvt.helper.Helper;
import io.github.virresh.matvt.view.OverlayView;

public class MouseEventService extends AccessibilityService {

    private MouseEmulationEngine mEngine;
    private OverlayView mOverlayView;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {}

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
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
        releaseEngine();
        if (Settings.canDrawOverlays(this)) init();
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
        releaseEngine();
        super.onDestroy();
    }
}
