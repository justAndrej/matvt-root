package io.github.virresh.matvt.engine.root;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/** Runs inside the touch executor; no separate process or density watchdog. */
final class DensityController implements Closeable {
    interface DisplayFactory {
        DensitySession.Display create() throws ReflectiveOperationException;
    }

    private final String markerPath;
    private final DisplayFactory displayFactory;
    private DensityJournal journal;
    private DensitySession session;

    DensityController(String markerPath) {
        this(markerPath, WindowDensity::new);
    }

    DensityController(String markerPath, DisplayFactory displayFactory) {
        this.markerPath = markerPath;
        this.displayFactory = displayFactory;
    }

    synchronized void recover(boolean onlyIfIdle) throws IOException, InterruptedException {
        if (session != null || markerPath == null || !new File(markerPath).exists()) return;
        DensityJournal acquired = onlyIfIdle ? DensityJournal.tryOpen(markerPath) : new DensityJournal(markerPath);
        if (acquired == null) return; // Another live executor owns this density; do not reset it.
        open(acquired);
        close();
    }

    synchronized String arm() throws IOException, InterruptedException {
        ensureSession();
        return session.arm();
    }

    synchronized String toggle(int dpi) throws IOException, InterruptedException {
        ensureSession();
        return session.toggle(dpi);
    }

    private void ensureSession() throws IOException, InterruptedException {
        if (session != null) return;
        if (markerPath == null) throw new IOException("Missing density recovery path");
        open(new DensityJournal(markerPath));
    }

    private void open(DensityJournal acquired) throws IOException {
        try {
            DensitySession created = new DensitySession(displayFactory.create(), acquired);
            created.restore(); // A stale marker is repaired before accepting new density changes.
            journal = acquired;
            session = created;
        } catch (IOException | ReflectiveOperationException | RuntimeException exception) {
            try { acquired.close(); } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
            throw new IOException("Cannot initialize display density", exception);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        DensitySession previous = session;
        DensityJournal previousJournal = journal;
        session = null;
        journal = null;
        try {
            if (previous != null) previous.restore();
        } finally {
            // On failure leave the marker, but release ownership so the next launch can retry.
            if (previousJournal != null) previousJournal.close();
        }
    }
}
