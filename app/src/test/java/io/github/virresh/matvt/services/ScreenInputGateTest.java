package io.github.virresh.matvt.services;

import org.junit.Test;

import static org.junit.Assert.*;

public class ScreenInputGateTest {
    private static final int DOWN = 0;
    private static final int UP = 1;
    private static final int POUND = 18;
    private static final int PTT = 289;

    @Test
    public void lockedScreenGetsAllKeysIncludingActivationAndHome() {
        ScreenInputGate gate = new ScreenInputGate();
        for (int key : new int[]{POUND, PTT, 3, 23, 66, 165}) {
            assertTrue(gate.shouldPassThrough(true, 1, key, 100, DOWN, 0));
            assertTrue(gate.shouldPassThrough(true, 1, key, 100, DOWN, 1));
            assertTrue(gate.shouldPassThrough(true, 1, key, 100, UP, 0));
        }
    }

    @Test
    public void hashHeldThroughUnlockStaysWithThePhoneUntilRelease() {
        ScreenInputGate gate = new ScreenInputGate();
        assertTrue(gate.shouldPassThrough(true, 1, POUND, 100, DOWN, 0));
        assertTrue(gate.shouldPassThrough(false, 1, POUND, 100, DOWN, 1));
        assertTrue(gate.shouldPassThrough(false, 1, POUND, 100, UP, 0));
        assertFalse(gate.shouldPassThrough(false, 1, POUND, 200, DOWN, 0));
        assertFalse(gate.shouldPassThrough(false, 1, POUND, 200, UP, 0));
    }

    @Test
    public void activationPressedBeforeScreenOffCannotResumeAfterWake() {
        ScreenInputGate gate = new ScreenInputGate();
        assertFalse(gate.shouldPassThrough(false, 1, PTT, 100, DOWN, 0));
        gate.suspend();
        gate.suspend(); // SCREEN_OFF followed by SCREEN_ON / USER_PRESENT is harmless.
        assertTrue(gate.shouldPassThrough(false, 1, PTT, 100, DOWN, 2));
        assertTrue(gate.shouldPassThrough(false, 1, PTT, 100, UP, 0));
        assertFalse(gate.shouldPassThrough(false, 1, PTT, 200, DOWN, 0));
    }

    @Test
    public void zeroRepeatDoesNotReclaimTheSameLockedPress() {
        ScreenInputGate gate = new ScreenInputGate();
        assertTrue(gate.shouldPassThrough(true, 1, PTT, 100, DOWN, 0));
        assertTrue(gate.shouldPassThrough(false, 1, PTT, 100, DOWN, 0));
        assertFalse(gate.shouldPassThrough(false, 1, PTT, 200, DOWN, 0));
    }

    @Test
    public void freshPressRecoversEvenWhenOldReleaseWasLost() {
        ScreenInputGate gate = new ScreenInputGate();
        gate.shouldPassThrough(true, 1, PTT, 100, DOWN, 0);
        assertFalse(gate.shouldPassThrough(false, 1, PTT, 200, DOWN, 0));
        assertFalse(gate.shouldPassThrough(false, 1, PTT, 200, DOWN, 1));
        assertFalse(gate.shouldPassThrough(false, 1, PTT, 200, UP, 0));
    }

    @Test
    public void orphanRepeatAndReleaseAreNotIntercepted() {
        ScreenInputGate gate = new ScreenInputGate();
        assertTrue(gate.shouldPassThrough(false, 1, PTT, 100, DOWN, 3));
        assertTrue(gate.shouldPassThrough(false, 1, PTT, 100, UP, 0));
        assertTrue(gate.shouldPassThrough(false, 1, 165, 200, UP, 0));
    }

    @Test
    public void differentInputDevicesDoNotSharePressedKeys() {
        ScreenInputGate gate = new ScreenInputGate();
        gate.shouldPassThrough(true, 1, PTT, 100, DOWN, 0);
        assertFalse(gate.shouldPassThrough(false, 2, PTT, 100, DOWN, 0));
        assertFalse(gate.shouldPassThrough(false, 2, PTT, 100, UP, 0));
        assertTrue(gate.shouldPassThrough(false, 1, PTT, 100, UP, 0));
    }

    @Test
    public void lateReleaseDoesNotEndANewerPress() {
        ScreenInputGate gate = new ScreenInputGate();
        gate.shouldPassThrough(true, 1, PTT, 100, DOWN, 0);
        assertFalse(gate.shouldPassThrough(false, 1, PTT, 200, DOWN, 0));
        assertTrue(gate.shouldPassThrough(false, 1, PTT, 100, UP, 0));
        assertFalse(gate.shouldPassThrough(false, 1, PTT, 200, DOWN, 1));
        assertFalse(gate.shouldPassThrough(false, 1, PTT, 200, UP, 0));
    }
}
