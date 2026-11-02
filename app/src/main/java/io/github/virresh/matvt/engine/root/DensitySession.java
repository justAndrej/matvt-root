package io.github.virresh.matvt.engine.root;

import java.io.IOException;

import io.github.virresh.matvt.helper.DensitySettings;

/** Owns only MATVT density overrides; restoration always clears to the factory density. */
public final class DensitySession {
    public interface Display {
        int factoryDpi() throws IOException;
        int currentDpi() throws IOException;
        void set(int dpi) throws IOException;
        void reset() throws IOException;
    }

    public interface Journal {
        boolean pending();
        void claim() throws IOException;
        void clear() throws IOException;
    }

    private final Display display;
    private final Journal journal;
    private boolean owned;
    private boolean managed;

    public DensitySession(Display display, Journal journal) {
        this.display = display;
        this.journal = journal;
        owned = journal.pending();
    }

    public synchronized String toggle(int dpi) throws IOException {
        int original = display.factoryDpi();
        if (!DensitySettings.isValidDpi(dpi, original)) {
            throw new IOException("Compact density must be between 72 and " + (original - 1));
        }
        if (display.currentDpi() == dpi) {
            display.reset(); // An explicit toggle can also clear a matching manual override.
            if (!managed) {
                journal.clear();
                owned = false;
            }
            return "DENSITY ORIGINAL " + original;
        }
        journal.claim(); // Durable intent precedes the system-wide change.
        owned = true;
        display.set(dpi);
        return "DENSITY COMPACT " + dpi;
    }

    public synchronized String arm() throws IOException {
        int original = display.factoryDpi();
        int current = display.currentDpi();
        journal.claim();
        owned = true;
        managed = true;
        return "DENSITY " + (current == original ? "ORIGINAL " : "COMPACT ") + current;
    }

    public synchronized void restore() throws IOException {
        if (!owned) return;
        display.reset();
        journal.clear();
        owned = false;
        managed = false;
    }
}
