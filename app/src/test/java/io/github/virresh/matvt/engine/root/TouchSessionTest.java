package io.github.virresh.matvt.engine.root;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class TouchSessionTest {
    @Test
    public void longHoldHasOneDownAndOneUpWithTheSameDownTime() throws Exception {
        Fixture fixture = new Fixture();
        fixture.touch.down(120, 145);
        for (int second = 1; second <= 10; second++) {
            fixture.now = 100 + second * 1000;
            fixture.touch.heartbeat();
            assertFalse(fixture.touch.expire(4000));
        }
        assertEquals(1, fixture.events.size());
        fixture.touch.up();
        assertArrayEquals(new long[]{TouchSession.DOWN, 100, 100, 120, 145}, fixture.events.get(0));
        assertArrayEquals(new long[]{TouchSession.UP, 100, 10100, 120, 145}, fixture.events.get(1));
    }

    @Test
    public void quickTapDoesNotAcquireAnArtificialLongPressDelay() throws Exception {
        Fixture fixture = new Fixture();
        fixture.touch.down(10, 20);
        fixture.now = 130;
        fixture.touch.up();
        assertEquals(30, fixture.events.get(1)[2] - fixture.events.get(0)[2]);
    }

    @Test
    public void cancelIsIdempotentAndDoesNotTurnIntoAClick() throws Exception {
        Fixture fixture = new Fixture();
        fixture.touch.down(10, 20);
        fixture.now = 600;
        fixture.touch.cancel();
        fixture.touch.cancel();
        fixture.touch.up();
        assertEquals(2, fixture.events.size());
        assertArrayEquals(new long[]{TouchSession.CANCEL, 100, 600, 10, 20}, fixture.events.get(1));
    }

    @Test
    public void lostHeartbeatCancelsTheContact() throws Exception {
        Fixture fixture = new Fixture();
        fixture.touch.down(10, 20);
        fixture.now = 4099;
        assertFalse(fixture.touch.expire(4000));
        fixture.now = 4100;
        assertTrue(fixture.touch.expire(4000));
        assertEquals(TouchSession.CANCEL, fixture.events.get(1)[0]);
        assertFalse(fixture.touch.expire(4000));
    }

    @Test
    public void movesAndReleaseKeepTheOriginalDownTime() throws Exception {
        Fixture fixture = new Fixture();
        fixture.touch.down(10, 20);
        fixture.now = 150;
        fixture.touch.move(30, 40);
        fixture.now = 200;
        fixture.touch.up();
        assertArrayEquals(new long[]{TouchSession.MOVE, 100, 150, 30, 40}, fixture.events.get(1));
        assertArrayEquals(new long[]{TouchSession.UP, 100, 200, 30, 40}, fixture.events.get(2));
    }

    @Test
    public void failedDownStillAllowsCleanup() throws Exception {
        Fixture fixture = new Fixture();
        fixture.failAction = TouchSession.DOWN;
        assertThrows(IOException.class, () -> fixture.touch.down(10, 20));
        fixture.touch.cancel();
        assertEquals(TouchSession.CANCEL, fixture.events.get(1)[0]);
    }

    @Test
    public void failedUpCanBeCancelled() throws Exception {
        Fixture fixture = new Fixture();
        fixture.touch.down(10, 20);
        fixture.failAction = TouchSession.UP;
        assertThrows(IOException.class, fixture.touch::up);
        fixture.touch.cancel();
        assertEquals(TouchSession.CANCEL, fixture.events.get(2)[0]);
    }

    @Test
    public void duplicateDownCannotReplaceOriginalContact() throws Exception {
        Fixture fixture = new Fixture();
        fixture.touch.down(10, 20);
        fixture.now = 200;
        assertThrows(IOException.class, () -> fixture.touch.down(50, 60));
        fixture.touch.cancel();
        assertArrayEquals(new long[]{TouchSession.CANCEL, 100, 200, 10, 20}, fixture.events.get(1));
    }

    private static final class Fixture {
        long now = 100;
        int failAction = -1;
        final List<long[]> events = new ArrayList<>();
        final TouchSession touch = new TouchSession(() -> now, (action, down, time, x, y) -> {
            events.add(new long[]{action, down, time, Math.round(x), Math.round(y)});
            if (action == failAction) throw new IOException("injection failure");
        });
    }
}
