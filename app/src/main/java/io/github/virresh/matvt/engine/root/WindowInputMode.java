package io.github.virresh.matvt.engine.root;

import android.app.Instrumentation;

import java.io.IOException;

/** Explicitly return the display to keyboard/D-pad navigation without injecting another key. */
final class WindowInputMode {
    static void enterKeyboardMode() throws IOException {
        try {
            // Public SDK wrapper for IWindowManager.setInTouchMode(false) on Android 11.
            // The framework handles newer signatures without private-API reflection here.
            new Instrumentation().setInTouchMode(false);
        } catch (RuntimeException exception) {
            throw new IOException("Cannot request keyboard mode: " + exception, exception);
        }
    }
}
