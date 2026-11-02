package io.github.virresh.matvt.engine.root;

import android.os.IBinder;

import java.io.IOException;
import java.lang.reflect.Method;

/** Same IWindowManager operations and current-user scope as Android's `wm density`. */
final class WindowDensity implements DensitySession.Display {
    private final Object service;
    private final Method initial, current, set, reset;

    WindowDensity() throws ReflectiveOperationException {
        // Avoid WindowManagerGlobal's application-context initialization in a standalone process.
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "window");
        service = Class.forName("android.view.IWindowManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class<?> api = Class.forName("android.view.IWindowManager");
        initial = api.getMethod("getInitialDisplayDensity", int.class);
        current = api.getMethod("getBaseDisplayDensity", int.class);
        set = api.getMethod("setForcedDisplayDensityForUser", int.class, int.class, int.class);
        reset = api.getMethod("clearForcedDisplayDensityForUser", int.class, int.class);
    }

    private Object invoke(Method method, Object... arguments) throws IOException {
        try { return method.invoke(service, arguments); }
        catch (ReflectiveOperationException exception) { throw new IOException("Display density operation failed", exception); }
    }

    @Override public int factoryDpi() throws IOException { return (Integer) invoke(initial, 0); }
    @Override public int currentDpi() throws IOException { return (Integer) invoke(current, 0); }
    @Override public void set(int dpi) throws IOException { invoke(set, 0, dpi, -2); }
    @Override public void reset() throws IOException { invoke(reset, 0, -2); }
}
