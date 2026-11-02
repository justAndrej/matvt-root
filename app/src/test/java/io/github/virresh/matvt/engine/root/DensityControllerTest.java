package io.github.virresh.matvt.engine.root;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

public class DensityControllerTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void appRecoveryDoesNotResetALiveMouseSession() throws Exception {
        File marker = new File(files.getRoot(), "density.pending");
        Display display = new Display();
        try (DensityController owner = new DensityController(marker.getPath(), () -> display);
             DensityController recovery = new DensityController(marker.getPath(), () -> display)) {
            owner.arm();
            owner.toggle(100);
            recovery.recover(true);
            assertEquals(100, display.current);
            assertEquals(0, display.resets);
            assertTrue(marker.exists());
        }
        assertEquals(160, display.current);
        assertFalse(marker.exists());
    }

    @Test public void nextStartupRecoversAMarkerLeftWithoutCleanup() throws Exception {
        File marker = new File(files.getRoot(), "density.pending");
        Display display = new Display();
        abandonSession(marker, display);
        assertEquals(100, display.current);
        assertTrue(marker.exists());
        try (DensityController recovery = new DensityController(marker.getPath(), () -> display)) {
            recovery.recover(true);
        }
        assertEquals(160, display.current);
        assertFalse(marker.exists());
    }

    @Test public void failedCleanupReleasesOwnershipForNextStartup() throws Exception {
        File marker = new File(files.getRoot(), "density.pending");
        Display display = new Display();
        DensityController oldOwner = new DensityController(marker.getPath(), () -> display);
        oldOwner.arm();
        oldOwner.toggle(100);
        display.failReset = true;
        assertThrows(IOException.class, oldOwner::close);
        assertTrue(marker.exists());
        display.failReset = false;
        try (DensityController next = new DensityController(marker.getPath(), () -> display)) {
            next.recover(true);
            assertEquals(160, display.current);
            next.arm();
            next.toggle(120);
            oldOwner.close();
            assertEquals(120, display.current);
            assertTrue(marker.exists());
        }
    }

    @Test public void startupWithoutMarkerPreservesManualDensity() throws Exception {
        File marker = new File(files.getRoot(), "density.pending");
        Display display = new Display();
        display.current = 120;
        try (DensityController recovery = new DensityController(marker.getPath(), () -> display)) {
            recovery.recover(true);
        }
        assertEquals(120, display.current);
        assertEquals(0, display.resets);
    }

    @Test public void failedInitializationDoesNotLeaveTheRecoveryLockHeld() throws Exception {
        File marker = new File(files.getRoot(), "density.pending");
        Display display = new Display();
        abandonSession(marker, display);
        try (DensityController broken = new DensityController(marker.getPath(), () -> {
            throw new ReflectiveOperationException("WindowManager unavailable");
        })) {
            assertThrows(IOException.class, () -> broken.recover(true));
        }
        try (DensityController retry = new DensityController(marker.getPath(), () -> display)) {
            retry.recover(true);
        }
        assertEquals(160, display.current);
        assertFalse(marker.exists());
    }

    private static void abandonSession(File marker, Display display) throws Exception {
        try (DensityJournal journal = new DensityJournal(marker.getPath())) {
            DensitySession session = new DensitySession(display, journal);
            session.arm();
            session.toggle(100);
            // Release the process lock without restoration, as after a killed executor.
        }
    }

    private static final class Display implements DensitySession.Display {
        int current = 160;
        int resets;
        boolean failReset;
        @Override public int factoryDpi() { return 160; }
        @Override public int currentDpi() { return current; }
        @Override public void set(int dpi) { current = dpi; }
        @Override public void reset() throws IOException {
            if (failReset) throw new IOException("reset failed");
            resets++;
            current = 160;
        }
    }
}
