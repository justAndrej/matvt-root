package io.github.virresh.matvt.engine.impl;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Persistent root touch server. One gesture at a time; its terminal event is never dropped. */
final class RootInput implements Closeable {
    interface ProcessFactory {
        Process start() throws IOException;
    }

    interface Callback {
        void onResult(Result result);
    }

    static final class Result {
        final int exitCode;
        final String output;
        final long elapsedMs;

        Result(int exitCode, String output, long elapsedMs) {
            this.exitCode = exitCode;
            this.output = output;
            this.elapsedMs = elapsedMs;
        }

        boolean isSuccess() { return exitCode == 0; }
    }

    private static final class Gesture {
        final boolean touch;
        boolean downSent;
        boolean finishQueued;
        boolean cancelled;
        Result failure;

        Gesture(boolean touch) { this.touch = touch; }
    }

    private final ProcessFactory factory;
    private final long startupTimeoutMs;
    private final long commandTimeoutMs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MATVT-root-input");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;
    private boolean ready;
    private boolean preparing;
    private boolean heartbeatQueued;
    private Gesture gesture;
    // Accessed only by the worker, including disposal.
    private Session session;

    RootInput(String apkPath) {
        this(() -> new ProcessBuilder("su", "-c", serverCommand(apkPath))
                .redirectErrorStream(true).start(), 30000, 5000);
    }

    RootInput(ProcessFactory factory, long startupTimeoutMs, long commandTimeoutMs) {
        this.factory = factory;
        this.startupTimeoutMs = startupTimeoutMs;
        this.commandTimeoutMs = commandTimeoutMs;
    }

    static String serverCommand(String apkPath) {
        String quoted = "'" + apkPath.replace("'", "'\"'\"'") + "'";
        return "export CLASSPATH=" + quoted + "; exec /system/bin/app_process / "
                + "--nice-name=matvt-root-touch io.github.virresh.matvt.engine.root.RootTouchServer";
    }

    synchronized boolean prepare(Callback callback) {
        if (closed || preparing || gesture != null) return false;
        preparing = true;
        worker.execute(() -> {
            long start = System.nanoTime();
            Result result;
            try {
                if (closed) throw new IOException("Root startup cancelled");
                if (session == null) {
                    session = new Session(factory.start());
                    session.awaitReady();
                }
                session.command("PING");
                result = success(start);
            } catch (IOException | InterruptedException exception) {
                result = failed(exception, start);
            }
            synchronized (this) {
                preparing = false;
                ready = result.isSuccess() && !closed;
            }
            notify(callback, result);
        });
        return true;
    }

    synchronized boolean down(int x, int y, Callback callback) {
        if (closed || !ready || preparing || gesture != null) return false;
        Gesture current = new Gesture(true);
        gesture = current;
        worker.execute(() -> {
            synchronized (this) {
                if (closed || current.cancelled) {
                    notify(callback, success(System.nanoTime()));
                    return;
                }
                current.downSent = true;
            }
            Result result = command("DOWN " + Math.max(0, x) + " " + Math.max(0, y));
            if (!result.isSuccess()) {
                synchronized (this) {
                    current.failure = result;
                    if (gesture == current) gesture = null;
                }
            }
            notify(callback, result);
        });
        return true;
    }

    synchronized boolean finishTouch(boolean cancel, Callback callback) {
        Gesture current = gesture;
        if (closed || current == null || !current.touch) return false;
        queueFinish(current, cancel, callback);
        return true;
    }

    private void queueFinish(Gesture current, boolean cancel, Callback callback) {
        current.cancelled |= cancel;
        if (current.finishQueued) return;
        current.finishQueued = true;
        worker.execute(() -> {
            boolean cancelled;
            synchronized (this) { cancelled = closed || current.cancelled; }
            Result result = current.failure;
            if (result == null) {
                result = current.downSent ? command(cancelled ? "CANCEL" : "UP") : success(System.nanoTime());
            }
            synchronized (this) {
                if (gesture == current) gesture = null;
            }
            notify(callback, result);
        });
    }

    synchronized void heartbeat(Callback callback) {
        Gesture current = gesture;
        if (closed || !ready || heartbeatQueued || current == null || !current.touch || current.finishQueued) return;
        heartbeatQueued = true;
        worker.execute(() -> {
            boolean send;
            synchronized (this) { send = !closed && gesture == current && !current.finishQueued; }
            Result result = send ? command("PING") : success(System.nanoTime());
            synchronized (this) { heartbeatQueued = false; }
            if (send) notify(callback, result);
        });
    }

    synchronized boolean swipe(String command, Callback callback) {
        if (closed || !ready || preparing || gesture != null) return false;
        Gesture current = new Gesture(false);
        gesture = current;
        worker.execute(() -> {
            Result result = closed ? success(System.nanoTime()) : command(command);
            synchronized (this) {
                if (gesture == current) gesture = null;
            }
            notify(callback, result);
        });
        return true;
    }

    private Result command(String text) {
        long start = System.nanoTime();
        try {
            if (session == null) throw new IOException("Root server is not connected");
            session.command(text);
            return success(start);
        } catch (IOException | InterruptedException exception) {
            return failed(exception, start);
        }
    }

    private Result failed(Exception exception, long start) {
        synchronized (this) { ready = false; }
        disposeSession();
        if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        return new Result(-1, exception.toString(), elapsed(start));
    }

    private static Result success(long start) { return new Result(0, "", elapsed(start)); }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }

    private void notify(Callback callback, Result result) {
        if (!closed) callback.onResult(result);
    }

    private void disposeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        ready = false;
        if (gesture != null && gesture.touch) queueFinish(gesture, true, result -> { });
        // Never interrupt a worker between DOWN and its terminal event.
        worker.execute(() -> {
            if (session != null) command("QUIT");
            disposeSession();
        });
        worker.shutdown();
    }

    private final class Session implements Closeable {
        private static final String EOF = "\u0000EOF";
        private final Process process;
        private final OutputStream input;
        private final BlockingQueue<String> lines = new ArrayBlockingQueue<>(64);
        private final StringBuilder diagnostics = new StringBuilder();
        private long sequence;

        Session(Process process) {
            this.process = process;
            input = process.getOutputStream();
            Thread reader = new Thread(this::readOutput, "MATVT-root-output");
            reader.setDaemon(true);
            reader.start();
        }

        private void readOutput() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder line = new StringBuilder();
                int value;
                while ((value = reader.read()) != -1) {
                    if (value == '\n') {
                        offer(line.toString());
                        line.setLength(0);
                    } else if (line.length() < 4096) line.append((char) value);
                }
                if (line.length() > 0) offer(line.toString());
            } catch (IOException exception) {
                offer(exception.toString());
            } finally {
                offer(EOF);
            }
        }

        private void offer(String line) {
            while (!lines.offer(line)) lines.poll();
        }

        void awaitReady() throws IOException, InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupTimeoutMs);
            while (true) {
                if (closed) throw new IOException("Root startup cancelled");
                String line = nextLine(deadline, true);
                if (line == null) continue;
                if (line.startsWith("MATVT_READY ")) {
                    if (!line.equals("MATVT_READY 1 0")) throw new IOException("Unexpected server identity: " + line);
                    return;
                }
            }
        }

        void command(String command) throws IOException, InterruptedException {
            long id = ++sequence;
            input.write((id + " " + command + "\n").getBytes(StandardCharsets.UTF_8));
            input.flush();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(commandTimeoutMs);
            while (true) {
                String line = nextLine(deadline, false);
                if (("MATVT_OK " + id).equals(line)) return;
            }
        }

        private String nextLine(long deadline, boolean starting) throws IOException, InterruptedException {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Root server response timed out");
            String line = lines.poll(starting ? Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)) : remaining,
                    TimeUnit.NANOSECONDS);
            if (line == null) {
                if (starting) return null;
                throw new IOException("Root server response timed out");
            }
            if (line.equals(EOF)) throw new IOException("Root server exited: " + diagnostics);
            if (line.startsWith("MATVT_ERROR ")) throw new IOException(line);
            if (!line.startsWith("MATVT_") && diagnostics.length() < 4096) {
                diagnostics.append(line, 0, Math.min(line.length(), 4096 - diagnostics.length())).append('\n');
            }
            return line;
        }

        @Override
        public void close() {
            // EOF lets the helper cancel its contact before exit, including error paths.
            try { input.close(); } catch (IOException ignored) { }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (System.nanoTime() < deadline) {
                try {
                    process.exitValue();
                    return;
                } catch (IllegalThreadStateException ignored) {
                    try { Thread.sleep(20); } catch (InterruptedException exception) { break; }
                }
            }
            process.destroy();
        }
    }
}
