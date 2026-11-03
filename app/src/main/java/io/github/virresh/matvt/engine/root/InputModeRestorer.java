package io.github.virresh.matvt.engine.root;

import java.io.IOException;

/** Restore only our pointer session on cleanup; a density-only recovery must not change input mode. */
final class InputModeRestorer {
    interface KeyboardMode {
        void enter() throws IOException;
    }

    private final KeyboardMode keyboardMode;
    private boolean pointerUsed;

    InputModeRestorer(KeyboardMode keyboardMode) {
        this.keyboardMode = keyboardMode;
    }

    synchronized void pointerDown() {
        pointerUsed = true;
    }

    synchronized boolean restore(boolean force) throws IOException {
        if (!force && !pointerUsed) return false;
        keyboardMode.enter();
        pointerUsed = false;
        return true;
    }
}
