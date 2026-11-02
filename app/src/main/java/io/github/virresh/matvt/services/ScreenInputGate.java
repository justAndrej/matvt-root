package io.github.virresh.matvt.services;

import java.util.HashMap;
import java.util.Map;

/** Keep a key press owned by the lock screen until its release, even after unlocking. */
final class ScreenInputGate {
    private static final int ACTION_DOWN = 0;
    private static final int ACTION_UP = 1;

    private final Map<Long, Long> activePresses = new HashMap<>();
    private final Map<Long, Long> bypassedPresses = new HashMap<>();

    void suspend() {
        bypassedPresses.putAll(activePresses);
        activePresses.clear();
    }

    boolean shouldPassThrough(boolean blocked, int deviceId, int keyCode,
                              long downTime, int action, int repeatCount) {
        if (blocked) suspend();
        long key = ((long) deviceId << 32) | (keyCode & 0xffffffffL);
        if (action == ACTION_DOWN) {
            if (blocked || matches(bypassedPresses, key, downTime)) {
                activePresses.remove(key);
                bypassedPresses.put(key, downTime);
            } else if (repeatCount == 0) {
                // A fresh press can recover from an old release that was never delivered.
                bypassedPresses.remove(key);
                activePresses.put(key, downTime);
            } else if (!matches(activePresses, key, downTime)) {
                // The initial DOWN may have happened before the service connected or while asleep.
                activePresses.remove(key);
                bypassedPresses.put(key, downTime);
            }
        }
        boolean passThrough = blocked || matches(bypassedPresses, key, downTime)
                || (action == ACTION_UP && !matches(activePresses, key, downTime));
        if (action == ACTION_UP) {
            if (matches(activePresses, key, downTime)) activePresses.remove(key);
            if (matches(bypassedPresses, key, downTime)) bypassedPresses.remove(key);
        }
        return passThrough;
    }

    private static boolean matches(Map<Long, Long> presses, long key, long downTime) {
        Long started = presses.get(key);
        return started != null && started == downTime;
    }
}
