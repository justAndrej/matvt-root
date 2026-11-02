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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class RootShellTest {
    @Test
    public void reusesAuthorizedSessionForMultipleCommands() throws Exception {
        FakeProcess process = new FakeProcess(true);
        AtomicInteger starts = new AtomicInteger();
        try (RootShell shell = new RootShell(() -> {
            starts.incrementAndGet();
            return process;
        }, 1000, 1000)) {
            assertTrue(prepare(shell).isSuccess());
            assertTrue(execute(shell, "tap").isSuccess());
            assertTrue(execute(shell, "swipe").isSuccess());
            assertEquals(1, starts.get());
            assertEquals(3, process.commands.size()); // Authorization once, then two gestures.
        }
        assertTrue(process.destroyed);
    }

    @Test
    public void deniedRootDoesNotRunTheGesture() throws Exception {
        FakeProcess process = new FakeProcess(false);
        try (RootShell shell = new RootShell(() -> process, 1000, 1000)) {
            RootShell.Result result = execute(shell, "tap");
            assertFalse(result.isSuccess());
            assertTrue(result.output.contains("did not grant root"));
            assertEquals(1, process.commands.size());
            assertTrue(process.destroyed);
        }
    }

    @Test
    public void commandFailureKeepsExitCodeAndDiagnosticsThenReconnects() throws Exception {
        FakeProcess first = new FakeProcess(true);
        FakeProcess second = new FakeProcess(true);
        AtomicInteger starts = new AtomicInteger();
        try (RootShell shell = new RootShell(
                () -> starts.getAndIncrement() == 0 ? first : second, 1000, 1000)) {
            RootShell.Result result = execute(shell, "fail");
            assertEquals(7, result.exitCode);
            assertTrue(result.output.contains("injection failed"));
            assertTrue(first.destroyed);
            assertTrue(execute(shell, "tap").isSuccess());
            assertEquals(2, starts.get());
        }
    }

    @Test
    public void busyShellRejectsExtraInputAndTimeoutReleasesIt() throws Exception {
        FakeProcess first = new FakeProcess(true);
        FakeProcess second = new FakeProcess(true);
        AtomicInteger starts = new AtomicInteger();
        try (RootShell shell = new RootShell(
                () -> starts.getAndIncrement() == 0 ? first : second, 1000, 300)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<RootShell.Result> result = new AtomicReference<>();
            assertTrue(shell.execute("stall", value -> {
                result.set(value);
                done.countDown();
            }));
            assertTrue(first.stalled.await(2, TimeUnit.SECONDS));
            assertFalse(shell.execute("unexpected tap", value -> fail("Input was queued")));
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertFalse(result.get().isSuccess());
            assertTrue(result.get().output.contains("timed out"));
            assertTrue(first.destroyed);
            assertFalse(first.commands.contains("unexpected tap"));
            assertTrue(execute(shell, "tap").isSuccess());
        }
    }

    @Test
    public void closeStopsPendingWorkAndRejectsNewCommands() throws Exception {
        FakeProcess process = new FakeProcess(true);
        RootShell shell = new RootShell(() -> process, 1000, 5000);
        CountDownLatch callback = new CountDownLatch(1);
        assertTrue(shell.execute("stall", result -> callback.countDown()));
        assertTrue(process.stalled.await(2, TimeUnit.SECONDS));
        shell.close();
        assertTrue(process.destroyed);
        assertFalse(shell.execute("tap", result -> fail("Closed shell accepted work")));
        assertFalse(callback.await(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void closeWaitsForCleanupAlreadyStartedByWorker() throws Exception {
        FakeProcess process = new FakeProcess(true);
        process.allowDestroy = new CountDownLatch(1);
        RootShell shell = new RootShell(() -> process, 1000, 1000);
        CountDownLatch closed = new CountDownLatch(1);
        assertTrue(shell.execute("fail", result -> { }));
        assertTrue(process.destroyStarted.await(2, TimeUnit.SECONDS));
        Thread closer = new Thread(() -> {
            shell.close();
            closed.countDown();
        });
        closer.start();
        try {
            assertFalse("close returned before the process was destroyed", closed.await(100, TimeUnit.MILLISECONDS));
        } finally {
            process.allowDestroy.countDown();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
        }
        assertTrue(process.destroyed);
    }

    @Test
    public void missingSuIsReportedWithoutBlockingCaller() throws Exception {
        try (RootShell shell = new RootShell(() -> {
            throw new IOException("su not found");
        }, 1000, 1000)) {
            RootShell.Result result = prepare(shell);
            assertFalse(result.isSuccess());
            assertTrue(result.output.contains("su not found"));
        }
    }

    @Test
    public void unexpectedShellExitIsReported() throws Exception {
        FakeProcess process = new FakeProcess(true);
        try (RootShell shell = new RootShell(() -> process, 1000, 1000)) {
            RootShell.Result result = execute(shell, "exit");
            assertFalse(result.isSuccess());
            assertTrue(result.output.contains("su exited"));
        }
    }

    private static RootShell.Result prepare(RootShell shell) throws InterruptedException {
        return await(shell, null);
    }

    private static RootShell.Result execute(RootShell shell, String command) throws InterruptedException {
        return await(shell, command);
    }

    private static RootShell.Result await(RootShell shell, String command) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<RootShell.Result> result = new AtomicReference<>();
        RootShell.Callback callback = value -> {
            result.set(value);
            done.countDown();
        };
        assertTrue(command == null ? shell.prepare(callback) : shell.execute(command, callback));
        assertTrue("Root operation did not finish", done.await(3, TimeUnit.SECONDS));
        return result.get();
    }

    /** In-memory su substitute: no root permissions, device or host shell required. */
    private static final class FakeProcess extends Process {
        private final boolean grantRoot;
        private final PipedInputStream output = new PipedInputStream(8192);
        private final PipedOutputStream response = new PipedOutputStream(output);
        final List<String> commands = new ArrayList<>();
        final CountDownLatch stalled = new CountDownLatch(1);
        final CountDownLatch destroyStarted = new CountDownLatch(1);
        CountDownLatch allowDestroy = new CountDownLatch(0);
        volatile boolean destroyed;
        private final ByteArrayOutputStream input = new ByteArrayOutputStream() {
            @Override
            public synchronized void flush() throws IOException {
                String script = new String(toByteArray(), StandardCharsets.UTF_8);
                reset();
                int separator = script.indexOf('\n');
                String command = script.substring(0, separator);
                commands.add(command);
                if (command.equals("stall")) {
                    stalled.countDown();
                    return;
                }
                if (command.equals("exit")) {
                    response.close();
                    return;
                }
                String marker = script.substring(separator + "\nprintf '\\n".length(), script.indexOf("%s"));
                int status = command.startsWith("test ") ? (grantRoot ? 0 : 1)
                        : command.equals("fail") ? 7 : 0;
                String diagnostic = command.equals("fail") ? "injection failed\n" : "";
                response.write((diagnostic + "\n" + marker + status + "\n").getBytes(StandardCharsets.UTF_8));
                response.flush();
            }
        };

        FakeProcess(boolean grantRoot) throws IOException {
            this.grantRoot = grantRoot;
        }

        @Override public OutputStream getOutputStream() { return input; }
        @Override public InputStream getInputStream() { return output; }
        @Override public InputStream getErrorStream() { return output; }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }

        @Override
        public void destroy() {
            destroyStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    allowDestroy.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            destroyed = true;
            try {
                response.close();
            } catch (IOException ignored) { }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
