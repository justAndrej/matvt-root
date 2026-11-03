package io.github.virresh.matvt.engine.root;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class InputModeRestorerTest {
    @Test public void unusedRecoveryExecutorDoesNotChangeAnotherAppsInputMode() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InputModeRestorer mode = new InputModeRestorer(() -> calls.incrementAndGet());
        assertFalse(mode.restore(false));
        assertEquals(0, calls.get());
    }

    @Test public void cleanupRestoresAfterPointerUseAndIsIdempotent() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InputModeRestorer mode = new InputModeRestorer(() -> calls.incrementAndGet());
        mode.pointerDown();
        assertTrue(mode.restore(false));
        assertFalse(mode.restore(false));
        assertEquals(1, calls.get());
    }

    @Test public void explicitDpadModeAlsoWorksAfterAnExternalAdbTap() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InputModeRestorer mode = new InputModeRestorer(() -> calls.incrementAndGet());
        assertTrue(mode.restore(true));
        assertEquals(1, calls.get());
        assertFalse(mode.restore(false));
    }

    @Test public void failureKeepsRestorationPendingForCleanupRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InputModeRestorer mode = new InputModeRestorer(() -> {
            if (calls.incrementAndGet() == 1) throw new IOException("WindowManager failed");
        });
        mode.pointerDown();
        assertThrows(IOException.class, () -> mode.restore(false));
        assertTrue(mode.restore(false));
        assertEquals(2, calls.get());
    }

    @Test public void newPointerSessionNeedsItsOwnRestoration() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InputModeRestorer mode = new InputModeRestorer(() -> calls.incrementAndGet());
        mode.pointerDown();
        mode.restore(true);
        mode.pointerDown();
        assertTrue(mode.restore(false));
        assertEquals(2, calls.get());
    }
}
