package io.github.virresh.matvt.engine.impl;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.virresh.matvt.helper.Helper;

/** Retry pending density cleanup on app/service startup without enabling mouse mode. */
public final class DensityRecovery {
    private static final AtomicBoolean running = new AtomicBoolean();

    private DensityRecovery() {}

    public static String markerPath(Context context) {
        return new File(context.getFilesDir(), "matvt-density.pending").getAbsolutePath();
    }

    public static void recoverIfNeeded(Context context, Runnable complete) {
        String marker = markerPath(context);
        if (Helper.isMouseModeEnabled() || !new File(marker).exists() || !running.compareAndSet(false, true)) {
            complete.run();
            return;
        }
        String apk = context.getApplicationInfo().sourceDir;
        RootInput input = new RootInput(() -> new ProcessBuilder("su", "-c",
                RootInput.serverCommand(apk, marker) + " --recover-density")
                .redirectErrorStream(true).start(), 8000, 5000);
        input.prepare(result -> {
            if (!result.isSuccess()) Log.e("MATVT-Density", "Recovery pending: " + result.output);
            input.close();
            running.set(false);
            complete.run();
        });
    }
}
