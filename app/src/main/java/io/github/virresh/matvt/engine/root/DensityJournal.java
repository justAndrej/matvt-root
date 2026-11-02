package io.github.virresh.matvt.engine.root;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.concurrent.TimeUnit;

/** The guard owns this lock until restoration finishes, excluding old/new service races. */
final class DensityJournal implements DensitySession.Journal, Closeable {
    private final File marker;
    private final RandomAccessFile lockFile;
    private FileLock lock;

    DensityJournal(String path) throws IOException, InterruptedException {
        marker = new File(path);
        lockFile = new RandomAccessFile(path + ".lock", "rw");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        try {
            while ((lock = lockFile.getChannel().tryLock()) == null) {
                if (System.nanoTime() >= deadline) throw new IOException("Previous density owner is still active");
                Thread.sleep(50);
            }
        } catch (IOException | InterruptedException exception) {
            lockFile.close();
            throw exception;
        }
    }

    @Override public boolean pending() { return marker.exists(); }

    @Override public void claim() throws IOException {
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(1);
            output.getFD().sync();
        }
    }

    @Override public void clear() throws IOException {
        if (marker.exists() && !marker.delete()) throw new IOException("Cannot clear density recovery marker");
    }

    @Override public void close() throws IOException {
        try { if (lock != null) lock.release(); }
        finally { lockFile.close(); }
    }
}
