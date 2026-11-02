package io.github.virresh.matvt.engine.impl;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One persistent su shell, with at most one operation in flight and no input backlog. */
final class RootShell implements Closeable {
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

        boolean isSuccess() {
            return exitCode == 0;
        }
    }

    private final ProcessFactory processFactory;
    private final long rootTimeoutMs;
    private final long commandTimeoutMs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MATVT-root");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Object sessionLock = new Object();
    private volatile boolean closed;
    private Session session;

    RootShell() {
        this(() -> new ProcessBuilder("su").redirectErrorStream(true).start(), 30000, 5000);
    }

    RootShell(ProcessFactory processFactory, long rootTimeoutMs, long commandTimeoutMs) {
        this.processFactory = processFactory;
        this.rootTimeoutMs = rootTimeoutMs;
        this.commandTimeoutMs = commandTimeoutMs;
    }

    boolean prepare(Callback callback) {
        return submit(null, callback);
    }

    boolean execute(String command, Callback callback) {
        return submit(command, callback);
    }

    private synchronized boolean submit(String command, Callback callback) {
        if (closed || !busy.compareAndSet(false, true)) return false;
        worker.execute(() -> {
            long started = System.nanoTime();
            Result result;
            try {
                Session active = getSession();
                if (!active.ready) {
                    Result check = active.run("test \"$(id -u)\" = 0", rootTimeoutMs);
                    if (!check.isSuccess()) {
                        throw new IOException("su did not grant root: " + check.output);
                    }
                    active.ready = true;
                }
                result = command == null
                        ? new Result(0, "Root session ready", 0)
                        : active.run(command, commandTimeoutMs);
                if (!result.isSuccess()) discardSession();
            } catch (IOException | InterruptedException exception) {
                discardSession();
                result = new Result(-1, exception.toString(), 0);
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            } finally {
                busy.set(false);
            }
            if (!closed) {
                callback.onResult(new Result(result.exitCode, result.output,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            }
        });
        return true;
    }

    private Session getSession() throws IOException {
        synchronized (sessionLock) {
            if (closed) throw new IOException("Root session closed");
            if (session != null) return session;
        }
        // Starting su must not hold the lock used by close().
        Session created = new Session(processFactory.start());
        synchronized (sessionLock) {
            if (closed) {
                created.close();
                throw new IOException("Root session closed");
            }
            session = created;
            return created;
        }
    }

    private void discardSession() {
        synchronized (sessionLock) {
            if (session != null) {
                // Keep cleanup serialized so close() cannot return while a worker still owns it.
                session.close();
                session = null;
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        worker.shutdownNow();
        discardSession();
    }

    private static final class Session implements Closeable {
        private static final int OUTPUT_LIMIT = 4096;
        private static final String END_OF_STREAM = "\u0000EOF";
        private final Process process;
        private final OutputStream input;
        private final BlockingQueue<String> lines = new ArrayBlockingQueue<>(64);
        private final String markerPrefix = "__MATVT_" + UUID.randomUUID().toString() + "_";
        private long sequence;
        private boolean ready;

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
                        offerLine(line.toString());
                        line.setLength(0);
                    } else if (line.length() < OUTPUT_LIMIT) {
                        line.append((char) value);
                    }
                }
                if (line.length() > 0) offerLine(line.toString());
            } catch (IOException exception) {
                offerLine(exception.toString());
            } finally {
                offerLine(END_OF_STREAM);
            }
        }

        private void offerLine(String line) {
            // Bound memory even if su or input emits excessive diagnostics.
            while (!lines.offer(line)) lines.poll();
        }

        Result run(String command, long timeoutMs) throws IOException, InterruptedException {
            String marker = markerPrefix + (++sequence) + ":";
            String script = command + "\nprintf '\\n" + marker + "%s\\n' \"$?\"\n";
            input.write(script.getBytes(StandardCharsets.UTF_8));
            input.flush();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            StringBuilder output = new StringBuilder();
            while (true) {
                long remaining = deadline - System.nanoTime();
                String line = remaining > 0 ? lines.poll(remaining, TimeUnit.NANOSECONDS) : null;
                if (line == null) throw new IOException("Root command timed out after " + timeoutMs + " ms");
                if (line.equals(END_OF_STREAM)) throw new IOException("su exited: " + output);
                if (line.startsWith(marker)) {
                    try {
                        return new Result(Integer.parseInt(line.substring(marker.length()).trim()),
                                output.toString().trim(), 0);
                    } catch (NumberFormatException exception) {
                        throw new IOException("Invalid root command response", exception);
                    }
                }
                if (output.length() < OUTPUT_LIMIT) {
                    output.append(line, 0, Math.min(line.length(), OUTPUT_LIMIT - output.length()));
                    output.append('\n');
                }
            }
        }

        @Override
        public void close() {
            process.destroy();
            try {
                input.close();
            } catch (IOException ignored) {
                // The process may already have exited or been denied by Magisk.
            }
        }
    }
}
