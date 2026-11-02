package io.github.virresh.matvt.engine.impl;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class RootInputTest {
    @Test
    public void releaseIsQueuedWhileDownIsStillInFlight() throws Exception {
        FakeProcess process = new FakeProcess();
        try (RootInput input = connected(process)) {
            process.blockPrefix = "DOWN";
            assertTrue(input.down(120, 145, result -> { }));
            await(process.blocked);
            Completion released = new Completion();
            assertTrue(input.finishTouch(false, released));
            assertFalse(input.down(50, 50, result -> fail("Queued another gesture")));
            assertFalse(input.swipe("SWIPE 0 0 0 10 250", result -> fail("Overlapping swipe")));
            assertFalse(process.commands.contains("UP"));
            process.unblock();
            assertTrue(released.result().isSuccess());
            assertEquals("DOWN 120 145", process.commands.get(1));
            assertEquals("UP", process.commands.get(2));
        }
    }

    @Test
    public void modeChangePromotesPendingUpToCancel() throws Exception {
        FakeProcess process = new FakeProcess();
        try (RootInput input = connected(process)) {
            process.blockPrefix = "DOWN";
            input.down(10, 20, result -> { });
            await(process.blocked);
            Completion terminal = new Completion();
            input.finishTouch(false, terminal);
            input.finishTouch(true, result -> { });
            process.unblock();
            assertTrue(terminal.result().isSuccess());
            assertTrue(process.commands.contains("CANCEL"));
            assertFalse(process.commands.contains("UP"));
        }
    }

    @Test
    public void closeWaitsForDownThenCancelsBeforeQuitting() throws Exception {
        FakeProcess process = new FakeProcess();
        RootInput input = connected(process);
        process.blockPrefix = "DOWN";
        input.down(10, 20, result -> { });
        await(process.blocked);
        input.close();
        assertFalse(input.down(1, 1, result -> fail("Closed input accepted work")));
        assertFalse(process.exited);
        process.unblock();
        await(process.ended);
        assertEquals("CANCEL", process.commands.get(2));
        assertEquals("QUIT", process.commands.get(3));
    }

    @Test
    public void cancelledQueuedDownDoesNotInjectAtAll() throws Exception {
        FakeProcess process = new FakeProcess();
        CountDownLatch prepared = new CountDownLatch(1);
        CountDownLatch continueWorker = new CountDownLatch(1);
        try (RootInput input = new RootInput(() -> process, 1000, 1000)) {
            input.prepare(result -> {
                prepared.countDown();
                try { continueWorker.await(); } catch (InterruptedException ignored) { }
            });
            await(prepared);
            input.down(10, 20, result -> { });
            Completion terminal = new Completion();
            input.finishTouch(true, terminal);
            continueWorker.countDown();
            assertTrue(terminal.result().isSuccess());
            assertEquals(Collections.singletonList("PING"), new ArrayList<>(process.commands));
        } finally {
            continueWorker.countDown();
        }
    }

    @Test
    public void repeatedHeartbeatDoesNotCreateABacklogAheadOfRelease() throws Exception {
        FakeProcess process = new FakeProcess();
        try (RootInput input = connected(process)) {
            Completion pressed = new Completion();
            input.down(10, 20, pressed);
            assertTrue(pressed.result().isSuccess());
            process.blockPrefix = "PING";
            input.heartbeat(result -> { });
            await(process.blocked);
            for (int i = 0; i < 100; i++) input.heartbeat(result -> { });
            Completion terminal = new Completion();
            input.finishTouch(false, terminal);
            process.unblock();
            assertTrue(terminal.result().isSuccess());
            assertEquals(4, process.commands.size()); // startup PING, DOWN, heartbeat PING, UP
            assertEquals("UP", process.commands.get(3));
        }
    }

    @Test
    public void multipleTapsReuseOneServer() throws Exception {
        FakeProcess process = new FakeProcess();
        AtomicInteger starts = new AtomicInteger();
        try (RootInput input = new RootInput(() -> {
            starts.incrementAndGet();
            return process;
        }, 1000, 1000)) {
            prepare(input);
            for (int i = 0; i < 3; i++) {
                Completion terminal = new Completion();
                assertTrue(input.down(10, 20, result -> { }));
                assertTrue(input.finishTouch(false, terminal));
                assertTrue(terminal.result().isSuccess());
            }
            assertEquals(1, starts.get());
        }
    }

    @Test
    public void timeoutClosesStdinAndRequiresExplicitPreparationAgain() throws Exception {
        FakeProcess process = new FakeProcess();
        try (RootInput input = new RootInput(() -> process, 1000, 150)) {
            prepare(input);
            process.blockPrefix = "DOWN";
            Completion pressed = new Completion();
            input.down(10, 20, pressed);
            assertFalse(pressed.result().isSuccess());
            await(process.ended);
            assertTrue(process.exited);
            assertFalse(input.down(10, 20, result -> fail("Reconnected without permission")));
        }
    }

    @Test
    public void nonRootServerIsRejected() throws Exception {
        FakeProcess process = new FakeProcess("MATVT_READY 1 2000");
        try (RootInput input = new RootInput(() -> process, 1000, 1000)) {
            Completion prepared = new Completion();
            input.prepare(prepared);
            assertFalse(prepared.result().isSuccess());
            assertTrue(process.commands.isEmpty());
            assertFalse(input.down(10, 20, result -> fail("Accepted unprivileged input")));
        }
    }

    @Test
    public void missingSuIsReported() throws Exception {
        try (RootInput input = new RootInput(() -> { throw new IOException("su not found"); }, 1000, 1000)) {
            Completion prepared = new Completion();
            input.prepare(prepared);
            assertTrue(prepared.result().output.contains("su not found"));
        }
    }

    @Test
    public void startupCanBeCancelledWithoutWaitingForPermissionTimeout() throws Exception {
        FakeProcess process = new FakeProcess(null);
        CountDownLatch starting = new CountDownLatch(1);
        RootInput input = new RootInput(() -> {
            starting.countDown();
            return process;
        }, 30000, 1000);
        input.prepare(result -> { });
        await(starting);
        input.close();
        await(process.ended);
        assertTrue(process.commands.isEmpty());
    }

    @Test
    public void startupFailureKeepsProcessDiagnostics() throws Exception {
        FakeProcess process = new FakeProcess("Could not find the root server class");
        process.exit();
        try (RootInput input = new RootInput(() -> process, 1000, 1000)) {
            Completion prepared = new Completion();
            input.prepare(prepared);
            assertTrue(prepared.result().output.contains("Could not find the root server class"));
        }
    }

    @Test
    public void unsolicitedLeaseFailureIsNotMistakenForASuccessfulHeartbeat() throws Exception {
        FakeProcess process = new FakeProcess();
        try (RootInput input = connected(process)) {
            Completion pressed = new Completion();
            input.down(10, 20, pressed);
            assertTrue(pressed.result().isSuccess());
            process.respond("MATVT_ERROR 0 Touch lease expired");
            Completion heartbeat = new Completion();
            input.heartbeat(heartbeat);
            assertTrue(heartbeat.result().output.contains("Touch lease expired"));
            await(process.ended);
            assertFalse(input.down(10, 20, result -> fail("Accepted a touch after losing the server")));
        }
    }

    @Test
    public void deadServerDoesNotReplayReleaseInANewSession() throws Exception {
        FakeProcess first = new FakeProcess();
        FakeProcess second = new FakeProcess();
        AtomicInteger starts = new AtomicInteger();
        try (RootInput input = new RootInput(() -> starts.getAndIncrement() == 0 ? first : second, 1000, 1000)) {
            prepare(input);
            Completion pressed = new Completion();
            input.down(10, 20, pressed);
            assertTrue(pressed.result().isSuccess());
            first.exit();
            Completion terminal = new Completion();
            input.finishTouch(false, terminal);
            assertFalse(terminal.result().isSuccess());
            prepare(input);
            assertEquals(Collections.singletonList("PING"), new ArrayList<>(second.commands));
        }
    }

    private static RootInput connected(FakeProcess process) throws Exception {
        RootInput input = new RootInput(() -> process, 1000, 2000);
        prepare(input);
        return input;
    }

    @Test public void idleHeartbeatChecksExecutorWithoutATouch() throws Exception {
        FakeProcess process = new FakeProcess();
        try (RootInput input = connected(process)) {
            Completion ping = new Completion();
            input.heartbeat(ping);
            assertTrue(ping.result().isSuccess());
            assertEquals(2, process.commands.size());
        }
    }

    @Test public void densityResponsesReachTheCallerWithoutLosingModeOrValue() throws Exception {
        FakeProcess process = new FakeProcess();
        try (RootInput input = connected(process)) {
            Completion armed = new Completion();
            assertTrue(input.armDensity(armed));
            assertEquals("DENSITY ORIGINAL 160", armed.result().output);
            Completion toggled = new Completion();
            assertTrue(input.toggleDensity(100, toggled));
            assertEquals("DENSITY COMPACT 100", toggled.result().output);
        }
    }

    @Test public void resetCannotBeDroppedWhileDensityChangeIsInFlight() throws Exception {
        FakeProcess process = new FakeProcess();
        try (RootInput input = connected(process)) {
            process.blockPrefix = "DENSITY_TOGGLE";
            assertTrue(input.toggleDensity(100, result -> { }));
            await(process.blocked);
            Completion reset = new Completion();
            input.resetDensity(reset);
            assertFalse(input.down(10, 20, result -> fail("Touch during scale transition")));
            process.unblock();
            assertTrue(reset.result().isSuccess());
            assertEquals("DENSITY_TOGGLE 100", process.commands.get(1));
            assertEquals("DENSITY_RESET", process.commands.get(2));
        }
    }

    @Test public void queuedToggleIsSkippedAfterMouseWasDisabled() throws Exception {
        FakeProcess process = new FakeProcess();
        CountDownLatch prepared = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (RootInput input = new RootInput(() -> process, 1000, 1000)) {
            input.prepare(result -> {
                prepared.countDown();
                try { resume.await(); } catch (InterruptedException ignored) { }
            });
            await(prepared);
            input.toggleDensity(100, result -> { });
            Completion reset = new Completion();
            input.resetDensity(reset);
            resume.countDown();
            assertTrue(reset.result().isSuccess());
            assertEquals(2, process.commands.size());
            assertEquals("DENSITY_RESET", process.commands.get(1));
        } finally { resume.countDown(); }
    }

    private static void prepare(RootInput input) throws Exception {
        Completion ready = new Completion();
        assertTrue(input.prepare(ready));
        RootInput.Result result = ready.result();
        assertTrue(result.output, result.isSuccess());
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("Operation did not finish", latch.await(3, TimeUnit.SECONDS));
    }

    private static final class Completion implements RootInput.Callback {
        private final CountDownLatch done = new CountDownLatch(1);
        private RootInput.Result value;
        @Override public void onResult(RootInput.Result result) { value = result; done.countDown(); }
        RootInput.Result result() throws InterruptedException { await(done); return value; }
    }

    private static final class FakeProcess extends Process {
        private final PipedInputStream output = new PipedInputStream(8192);
        private final PipedOutputStream response = new PipedOutputStream(output);
        final List<String> commands = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);
        volatile String blockPrefix;
        volatile String heldId;
        volatile boolean exited;
        private final ByteArrayOutputStream input = new ByteArrayOutputStream() {
            @Override public synchronized void flush() throws IOException {
                if (exited) throw new IOException("Server exited");
                String[] parts = new String(toByteArray(), StandardCharsets.UTF_8).trim().split(" ", 2);
                reset();
                String command = parts[1];
                commands.add(command);
                if (blockPrefix != null && command.startsWith(blockPrefix)) {
                    heldId = parts[0];
                    blocked.countDown();
                    return;
                }
                String payload = command.equals("DENSITY_ARM") ? " DENSITY ORIGINAL 160"
                        : command.startsWith("DENSITY_TOGGLE ") ? " DENSITY COMPACT " + command.substring(15) : "";
                respond("MATVT_OK " + parts[0] + payload);
                if (command.equals("QUIT")) exit();
            }
            @Override public void close() throws IOException { exit(); }
        };

        FakeProcess() throws IOException { this("MATVT_READY 1 0"); }
        FakeProcess(String greeting) throws IOException { if (greeting != null) respond(greeting); }

        synchronized void respond(String text) throws IOException {
            response.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            response.flush();
        }

        void unblock() throws IOException {
            blockPrefix = null;
            respond("MATVT_OK " + heldId);
        }

        synchronized void exit() throws IOException {
            if (exited) return;
            exited = true;
            response.close();
            ended.countDown();
        }

        @Override public OutputStream getOutputStream() { return input; }
        @Override public InputStream getInputStream() { return output; }
        @Override public InputStream getErrorStream() { return output; }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { if (!exited) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { try { exit(); } catch (IOException ignored) { } }
    }
}
