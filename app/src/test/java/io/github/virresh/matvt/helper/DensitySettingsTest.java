package io.github.virresh.matvt.helper;

import org.junit.Test;

import static org.junit.Assert.*;

public class DensitySettingsTest {
    @Test public void compactDensityMustBeSupportedAndBelowFactory() {
        assertTrue(DensitySettings.isValidDpi(100, 160));
        assertTrue(DensitySettings.isValidDpi(72, 160));
        assertFalse(DensitySettings.isValidDpi(71, 160));
        assertFalse(DensitySettings.isValidDpi(160, 160));
    }

    @Test public void navigationCallAndMouseKeysCannotBeReassigned() {
        for (int key : new int[]{0, 3, 4, 5, 6, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,
                66, 79, 160, 164, 165, 183, 184, 185, 186, 187, 223, 224, 289}) {
            assertFalse("key=" + key, DensitySettings.isKeyAllowed(key, 289));
        }
        assertTrue(DensitySettings.isKeyAllowed(290, 289));
        assertFalse(DensitySettings.isKeyAllowed(290, 290));
    }

    @Test public void callRingingAndVoipNeverActivateDensity() {
        for (int audioMode : new int[]{-1, 1, 2, 3}) {
            assertFalse(DensitySettings.isAvailable(true, true, 300, 289, audioMode));
        }
        assertTrue(DensitySettings.isAvailable(true, true, 300, 289, 0));
        assertFalse(DensitySettings.isAvailable(false, true, 300, 289, 0));
        assertFalse(DensitySettings.isAvailable(true, false, 300, 289, 0));
    }
}
