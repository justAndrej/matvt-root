package io.github.virresh.matvt.engine.root;

import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** app_process entry point. Its private stdin/stdout pipes are the only control channel. */
public final class RootTouchServer {
    private static final long TOUCH_LEASE_MS = 4000;

    public static void main(String[] args) {
        PrintWriter output = new PrintWriter(System.out, true);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        TouchSession touch = null;
        DensityController density = new DensityController(args.length > 0 ? args[0] : null);
        boolean recoveryOnly = args.length == 2 && "--recover-density".equals(args[1]);
        int exitCode = 0;
        long request = 0;
        try {
            int uid = Process.myUid();
            // UID 2000 permits isolated adb/emulator verification. The app client requires UID 0.
            if (uid != 0 && uid != 2000) throw new IOException("Root or Android shell UID required");
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            density.recover(recoveryOnly);
            touch = new TouchSession(SystemClock::uptimeMillis, new AndroidInjector());
            TouchSession session = touch;
            watchdog.scheduleAtFixedRate(() -> {
                try {
                    if (!session.expire(TOUCH_LEASE_MS)) return;
                    output.println("MATVT_ERROR 0 Touch lease expired");
                } catch (IOException exception) {
                    output.println("MATVT_ERROR 0 " + diagnostic(exception));
                }
                try { density.close(); }
                catch (IOException exception) { output.println("MATVT_ERROR 0 Density cleanup: " + diagnostic(exception)); }
                System.exit(1);
            }, 500, 500, TimeUnit.MILLISECONDS);
            output.println("MATVT_READY 1 " + uid);

            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    request = 0;
                    if (line.length() > 256) throw new IOException("Command too long");
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length < 2) throw new IOException("Missing command");
                    request = Long.parseLong(parts[0]);
                    if (request <= 0) throw new IOException("Invalid request ID");
                    if (recoveryOnly && !parts[1].equals("PING") && !parts[1].equals("QUIT")) {
                        throw new IOException("Recovery-only executor cannot inject input or change density");
                    }
                    String payload = "";
                    switch (parts[1]) {
                        case "DOWN":
                            requireLength(parts, 4);
                            touch.down(Float.parseFloat(parts[2]), Float.parseFloat(parts[3]));
                            break;
                        case "UP":
                            requireLength(parts, 2);
                            touch.up();
                            break;
                        case "CANCEL":
                            requireLength(parts, 2);
                            touch.cancel();
                            break;
                        case "PING":
                            requireLength(parts, 2);
                            touch.heartbeat();
                            break;
                        case "SWIPE":
                            requireLength(parts, 7);
                            swipe(touch, parts);
                            break;
                        case "QUIT":
                            requireLength(parts, 2);
                            touch.cancel();
                            density.close();
                            output.println("MATVT_OK " + request);
                            return;
                        case "DENSITY_TOGGLE":
                            requireLength(parts, 3);
                            touch.cancel();
                            payload = density.toggle(Integer.parseInt(parts[2]));
                            break;
                        case "DENSITY_ARM":
                            requireLength(parts, 2);
                            touch.cancel();
                            payload = density.arm();
                            break;
                        case "DENSITY_RESET":
                            requireLength(parts, 2);
                            touch.cancel();
                            density.close();
                            break;
                        default:
                            throw new IOException("Unknown command");
                    }
                    output.println("MATVT_OK " + request + (payload.isEmpty() ? "" : " " + payload));
                }
            }
        } catch (Exception exception) {
            exitCode = 1;
            output.println("MATVT_ERROR " + request + " " + diagnostic(exception));
        } finally {
            watchdog.shutdownNow();
            if (touch != null) {
                try {
                    // Includes stdin EOF, malformed commands and failures after DOWN.
                    touch.cancel();
                } catch (IOException exception) {
                    output.println("MATVT_ERROR 0 Cleanup: " + diagnostic(exception));
                    exitCode = 1;
                }
            }
            try { density.close(); }
            catch (IOException exception) {
                output.println("MATVT_ERROR 0 Density cleanup: " + diagnostic(exception));
                exitCode = 1;
            }
            System.exit(exitCode);
        }
    }

    private static void swipe(TouchSession touch, String[] parts) throws IOException, InterruptedException {
        float x1 = Float.parseFloat(parts[2]);
        float y1 = Float.parseFloat(parts[3]);
        float x2 = Float.parseFloat(parts[4]);
        float y2 = Float.parseFloat(parts[5]);
        int duration = Integer.parseInt(parts[6]);
        if (duration < 100 || duration > 2000) throw new IOException("Invalid swipe duration");
        touch.down(x1, y1);
        long start = SystemClock.uptimeMillis();
        while (true) {
            float fraction = Math.min(1f, (SystemClock.uptimeMillis() - start) / (float) duration);
            touch.move(x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction);
            if (fraction >= 1f) break;
            Thread.sleep(16);
        }
        touch.up();
    }

    private static void requireLength(String[] parts, int expected) throws IOException {
        if (parts.length != expected) throw new IOException("Invalid argument count");
    }

    private static String diagnostic(Exception exception) {
        String text = exception.toString().replace('\n', ' ').replace('\r', ' ');
        return text.substring(0, Math.min(400, text.length()));
    }

    private static final class AndroidInjector implements TouchSession.Injector {
        private final Object manager;
        private final Method inject;
        private final Method setDisplayId;

        AndroidInjector() throws ReflectiveOperationException {
            Class<?> type;
            try {
                type = Class.forName("android.hardware.input.InputManagerGlobal");
            } catch (ClassNotFoundException ignored) {
                type = Class.forName("android.hardware.input.InputManager");
            }
            manager = type.getMethod("getInstance").invoke(null);
            inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
            setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
        }

        @Override
        public void inject(int action, long downTime, long eventTime, float x, float y) throws IOException {
            MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
            properties.id = 0;
            properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
            coords.x = x;
            coords.y = y;
            coords.pressure = action == TouchSession.UP || action == TouchSession.CANCEL ? 0f : 1f;
            coords.size = 1f;
            MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, 1,
                    new MotionEvent.PointerProperties[]{properties}, new MotionEvent.PointerCoords[]{coords},
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try {
                setDisplayId.invoke(event, 0);
                // WAIT_FOR_RESULT: preserve event order without waiting for application handlers.
                if (!Boolean.TRUE.equals(inject.invoke(manager, event, 1))) {
                    throw new IOException("Touch injection was rejected");
                }
            } catch (ReflectiveOperationException exception) {
                throw new IOException("Touch injection failed", exception);
            } finally {
                event.recycle();
            }
        }
    }
}
