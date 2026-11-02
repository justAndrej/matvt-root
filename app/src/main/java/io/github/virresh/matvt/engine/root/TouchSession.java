package io.github.virresh.matvt.engine.root;

import java.io.IOException;

/** One contact, sharing its original downTime until UP or CANCEL. Android-independent for tests. */
public final class TouchSession {
    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int MOVE = 2;
    public static final int CANCEL = 3;

    public interface Clock {
        long now();
    }

    public interface Injector {
        void inject(int action, long downTime, long eventTime, float x, float y) throws IOException;
    }

    private final Clock clock;
    private final Injector injector;
    private boolean active;
    private long downTime;
    private long lastHeartbeat;
    private float x;
    private float y;

    public TouchSession(Clock clock, Injector injector) {
        this.clock = clock;
        this.injector = injector;
    }

    public synchronized void down(float x, float y) throws IOException {
        if (active) throw new IOException("A touch is already active");
        validate(x, y);
        this.x = x;
        this.y = y;
        downTime = clock.now();
        lastHeartbeat = downTime;
        // Keep it active even if injection fails ambiguously, so cleanup still attempts CANCEL.
        active = true;
        injector.inject(DOWN, downTime, downTime, x, y);
    }

    public synchronized void move(float x, float y) throws IOException {
        if (!active) throw new IOException("No active touch");
        validate(x, y);
        this.x = x;
        this.y = y;
        injector.inject(MOVE, downTime, clock.now(), x, y);
    }

    public synchronized void up() throws IOException {
        finish(UP);
    }

    public synchronized void cancel() throws IOException {
        finish(CANCEL);
    }

    private void finish(int action) throws IOException {
        if (!active) return;
        injector.inject(action, downTime, clock.now(), x, y);
        active = false;
    }

    public synchronized void heartbeat() {
        lastHeartbeat = clock.now();
    }

    public synchronized boolean expire(long timeoutMs) throws IOException {
        if (!active || clock.now() - lastHeartbeat < timeoutMs) return false;
        cancel();
        return true;
    }

    private static void validate(float x, float y) throws IOException {
        if (Float.isNaN(x) || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y)
                || x < 0 || y < 0 || x > 32767 || y > 32767) {
            throw new IOException("Invalid touch coordinates");
        }
    }
}
