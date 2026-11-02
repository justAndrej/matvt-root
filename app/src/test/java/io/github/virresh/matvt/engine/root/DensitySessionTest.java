package io.github.virresh.matvt.engine.root;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class DensitySessionTest {
    @Test public void armedFeatureRestoresEvenAPreexistingManualScaleOnMouseOff() throws Exception {
        Fixture f = new Fixture(100, false);
        assertEquals("DENSITY COMPACT 100", f.session.arm());
        assertEquals(100, f.current);
        assertTrue(f.pending);
        f.session.restore();
        assertEquals(160, f.current);
        assertFalse(f.pending);
    }

    @Test public void armedOriginalModeKeepsRecoveryOwnershipUntilMouseOff() throws Exception {
        Fixture f = new Fixture(160, false);
        assertEquals("DENSITY ORIGINAL 160", f.session.arm());
        f.session.toggle(100);
        f.session.toggle(100);
        assertEquals(160, f.current);
        assertTrue(f.pending);
        f.session.restore();
        assertFalse(f.pending);
    }

    @Test public void togglesBetweenCompactAndFactoryWithDurableOwnership() throws Exception {
        Fixture f = new Fixture(160, false);
        assertEquals("DENSITY COMPACT 100", f.session.toggle(100));
        assertTrue(f.pending);
        assertEquals(Arrays.asList("claim", "set 100"), f.operations);
        assertEquals("DENSITY ORIGINAL 160", f.session.toggle(100));
        assertEquals(160, f.current);
        assertFalse(f.pending);
        assertEquals(Arrays.asList("claim", "set 100", "reset", "clear"), f.operations);
    }

    @Test public void cleanupDoesNotTouchAnUnownedManualOverride() throws Exception {
        Fixture f = new Fixture(100, false);
        f.session.restore();
        assertEquals(100, f.current);
        assertTrue(f.operations.isEmpty());
        assertEquals("DENSITY ORIGINAL 160", f.session.toggle(100));
        assertEquals(160, f.current);
    }

    @Test public void pendingCrashMarkerIsRecoveredToFactory() throws Exception {
        Fixture f = new Fixture(100, true);
        f.session.restore();
        assertEquals(Arrays.asList("reset", "clear"), f.operations);
        assertEquals(160, f.current);
        assertFalse(f.pending);
    }

    @Test public void failedSetStillOwnsCleanupEvenIfSystemAppliedTheChange() throws Exception {
        Fixture f = new Fixture(160, false);
        f.failSet = true;
        assertThrows(IOException.class, () -> f.session.toggle(100));
        assertTrue(f.pending);
        assertEquals(100, f.current);
        f.session.restore();
        assertEquals(160, f.current);
        assertFalse(f.pending);
    }

    @Test public void failedResetKeepsMarkerForRetry() throws Exception {
        Fixture f = new Fixture(100, true);
        f.failReset = true;
        assertThrows(IOException.class, f.session::restore);
        assertTrue(f.pending);
        f.failReset = false;
        f.session.restore();
        assertFalse(f.pending);
    }

    @Test public void invalidDensityCannotChangeSystemOrClaimOwnership() {
        for (int dpi : new int[]{-1, 0, 71, 160, 200}) {
            Fixture f = new Fixture(160, false);
            assertThrows(IOException.class, () -> f.session.toggle(dpi));
            assertTrue(f.operations.isEmpty());
            assertEquals(160, f.current);
        }
    }

    @Test public void completedOldOwnerCannotResetANewerOwnersSetting() throws Exception {
        Fixture f = new Fixture(160, false);
        f.session.toggle(100);
        f.session.restore();
        f.current = 120;
        f.pending = true;
        f.operations.clear();
        f.session.restore();
        assertEquals(120, f.current);
        assertTrue(f.pending);
        assertTrue(f.operations.isEmpty());
    }

    private static final class Fixture implements DensitySession.Display, DensitySession.Journal {
        int current;
        boolean pending, failSet, failReset;
        final List<String> operations = new ArrayList<>();
        final DensitySession session;
        Fixture(int current, boolean pending) {
            this.current = current;
            this.pending = pending;
            session = new DensitySession(this, this);
        }
        @Override public int factoryDpi() { return 160; }
        @Override public int currentDpi() { return current; }
        @Override public void set(int dpi) throws IOException {
            operations.add("set " + dpi);
            current = dpi;
            if (failSet) throw new IOException("set failed after applying");
        }
        @Override public void reset() throws IOException {
            operations.add("reset");
            if (failReset) throw new IOException("reset failed");
            current = 160;
        }
        @Override public boolean pending() { return pending; }
        @Override public void claim() { operations.add("claim"); pending = true; }
        @Override public void clear() { operations.add("clear"); pending = false; }
    }
}
