package io.github.virresh.matvt.engine.root;

import android.os.Process;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Separate process/session: restores density even if the root touch server is killed. */
public final class DensityGuard {
    public static void main(String[] args) {
        PrintWriter output = new PrintWriter(System.out, true);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        int exitCode = 0;
        try {
            if (args.length != 1 || (Process.myUid() != 0 && Process.myUid() != 2000)) {
                throw new IOException("Density guard requires root/shell and a recovery path");
            }
            Os.setsid(); // Do not share the parent's process group / su teardown.
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            try (DensityJournal journal = new DensityJournal(args[0])) {
                DensitySession density = new DensitySession(new WindowDensity(), journal);
                try {
                    density.restore(); // Recover an override left by a previous hard crash/reboot.
                    AtomicLong lastMessage = new AtomicLong(SystemClock.uptimeMillis());
                    watchdog.scheduleAtFixedRate(() -> {
                        try {
                            synchronized (density) {
                                if (SystemClock.uptimeMillis() - lastMessage.get() < 4000) return;
                                density.restore();
                            }
                            System.exit(0);
                        } catch (IOException exception) {
                            output.println("ERROR " + exception);
                            System.exit(1); // The recovery marker remains for a later retry.
                        }
                    }, 500, 500, TimeUnit.MILLISECONDS);
                    output.println("READY");
                    try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = input.readLine()) != null) {
                            lastMessage.set(SystemClock.uptimeMillis());
                            String[] parts = line.trim().split("\\s+");
                            if (parts.length == 1 && parts[0].equals("PING")) output.println("OK");
                            else if (parts.length == 1 && parts[0].equals("ARM")) output.println("OK " + density.arm());
                            else if (parts.length == 2 && parts[0].equals("TOGGLE")) {
                                String result = density.toggle(Integer.parseInt(parts[1]));
                                lastMessage.set(SystemClock.uptimeMillis());
                                output.println("OK " + result);
                            } else if (parts.length == 1 && parts[0].equals("QUIT")) {
                                density.restore();
                                output.println("OK");
                                return;
                            } else throw new IOException("Invalid density command");
                        }
                    }
                } finally {
                    watchdog.shutdownNow();
                    density.restore(); // EOF, exceptions and normal exit all restore before unlocking.
                }
            }
        } catch (Exception exception) {
            exitCode = 1;
            Throwable cause = exception;
            while (cause.getCause() != null) cause = cause.getCause();
            output.println("ERROR " + cause.toString().replace('\n', ' '));
        } finally {
            watchdog.shutdownNow();
            System.exit(exitCode);
        }
    }
}
