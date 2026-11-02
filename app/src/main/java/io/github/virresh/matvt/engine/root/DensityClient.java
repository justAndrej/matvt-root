package io.github.virresh.matvt.engine.root;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Root touch server's private connection to its independently-lived density guard. */
final class DensityClient implements Closeable {
    private final String markerPath;
    private Connection guard;

    DensityClient(String markerPath) { this.markerPath = markerPath; }

    synchronized void recover() throws IOException, InterruptedException {
        if (markerPath != null && new File(markerPath).exists()) {
            ensureGuard(); // The guard recovers under an inter-process lock before READY.
            stopGuard();
        }
    }

    synchronized String toggle(int dpi) throws IOException, InterruptedException {
        ensureGuard();
        return guard.command("TOGGLE " + dpi);
    }

    synchronized String arm() throws IOException, InterruptedException {
        ensureGuard();
        return guard.command("ARM");
    }

    synchronized void heartbeat() throws IOException, InterruptedException {
        if (guard != null) guard.command("PING");
    }

    private void ensureGuard() throws IOException, InterruptedException {
        if (markerPath == null) throw new IOException("Missing density recovery path");
        if (guard != null) return;
        guard = new Connection(new ProcessBuilder("/system/bin/app_process", "/",
                "--nice-name=matvt-density-guard", DensityGuard.class.getName(), markerPath)
                .redirectErrorStream(true).start());
        guard.await("READY");
    }

    private void stopGuard() throws IOException, InterruptedException {
        if (guard == null) return;
        Connection previous = guard;
        guard = null;
        try { previous.command("QUIT"); }
        finally { previous.close(); }
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            try { stopGuard(); }
            catch (IOException ignored) { /* EOF restores in a live guard; recover a dead guard below. */ }
            recover();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Density restoration interrupted", exception);
        }
    }

    private static final class Connection implements Closeable {
        private final OutputStream input;
        private final BlockingQueue<String> replies = new ArrayBlockingQueue<>(16);

        Connection(Process process) {
            input = process.getOutputStream();
            Thread reader = new Thread(() -> {
                try (BufferedReader stream = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = stream.readLine()) != null) offer(line);
                } catch (IOException exception) { offer("ERROR " + exception); }
                finally { offer("ERROR Density guard exited"); }
            }, "MATVT-density-output");
            reader.setDaemon(true);
            reader.start();
        }

        private void offer(String text) { while (!replies.offer(text)) replies.poll(); }

        String command(String command) throws IOException, InterruptedException {
            input.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            input.flush();
            return await("OK");
        }

        String await(String prefix) throws IOException, InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
            while (true) {
                long remaining = deadline - System.nanoTime();
                String line = remaining > 0 ? replies.poll(remaining, TimeUnit.NANOSECONDS) : null;
                if (line == null) throw new IOException("Density guard timed out");
                if (line.startsWith("ERROR ")) throw new IOException(line);
                if (line.equals(prefix)) return "";
                if (line.startsWith(prefix + " ")) return line.substring(prefix.length() + 1);
            }
        }

        @Override public void close() throws IOException {
            // Never kill the guard: it must finish restoration after losing this pipe.
            input.close();
        }
    }
}
