# Sonim XP5900: root input

This branch uses the phone port of MATVT with local Magisk/root input. It requires
root authorization for **MATVT** (`io.github.virresh.matvt`), independently of the
authorization previously granted to GestureProbe or the ADB shell.

## Context

In the [prototype discussion](https://chatgpt.com/share/6ae86445-8b50-83ed-9f24-268d8d790735):

- `dispatchGesture()` was accepted but immediately cancelled on the XP5900,
  including tests without any key press.
- Advertising the `faketouch` feature did not fix gesture injection.
- `input tap 120 145` worked from a root shell.
- GestureProbe successfully invoked `su -c /system/bin/input tap 120 145` and
  TouchDetector registered the tap. Starting each operation took about 0.7 seconds
  in the supplied logs.
- PTT generates key code **289**. Both Android `DPAD_CENTER` (23) and `ENTER` (66)
  are accepted as the confirmation key in this implementation.

## Controls

- Hold **PTT** for 800 ms to enable/disable the mouse. On first activation, grant
  MATVT superuser access in Magisk. Other keys remain available while root is requested.
- Briefly press PTT while enabled to switch between mouse and scroll modes.
- D-pad arrows move the cursor in mouse mode and perform swipes in scroll mode.
- Press the D-pad center/Enter for a tap. Hold it for a synthetic long press.
- The activation key defaults to **289**, including the empty custom-key field.
  Existing explicit overrides remain in effect; uncheck **Override Activation Key**
  to use the new default.

## Cursor appearance

The XP5900/XP5plus has a **240 x 320 pixel, 2.8-inch display** according to the
[Sonim specifications](https://www.sonimtech.com/products/phones/xp5plus).
For comparison, the [Android 11 mdpi arrow](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/core/res/res/drawable-mdpi/pointer_arrow.png)
has a 22 x 28 pixel canvas. This fork uses a **24-pixel-high cursor by default**,
with five slider positions: **16, 20, 24, 28 and 32 pixels**. These are screen
pixels, independent of bitmap density or Android's display scaling setting.

In settings, use left/right on the focused size slider. The label shows its height
and the preview displays it at actual size, even with mouse mode off. Dark and
light styles have contrasting outlines and are drawn as vectors, without scaling
the old 206 x 206 PNGs. Both size and style update the attached overlay immediately;
there is no need to reconnect the accessibility service or toggle PTT.

Existing saved slider positions (0–4) are retained and mapped to the new sizes.
An unset size defaults to the middle position, 24 px. Legacy `default`/`Default`
style names map to Dark. Changing appearance leaves the cursor's screen position
unchanged: the arrow tip remains the root click coordinate at every size. At the
screen edges, the cursor body may be clipped rather than shifting that coordinate.

## Implementation and limitations

`RootShell` keeps one `su` process open and verifies UID 0 before enabling mouse
mode. Commands run on a background worker. Each gesture still starts Android's
`/system/bin/input` command; actual latency must be measured on the phone.

Taps use `input touchscreen tap`; long presses use a stationary `input touchscreen
swipe`; scrolling uses 250 ms swipes. Coordinates include the overlay's on-screen
offset and stay inside its bounds.

There is at most one root operation in flight. Scroll repetition starts only after
the preceding swipe completes. Releasing the direction or changing modes prevents
further repetition; an already-started swipe finishes normally. Additional taps
while a gesture is running are rejected with a short busy message rather than
executed later in a different app. Long press simulates a fixed-duration touch;
holding the button and moving the pointer is not a drag operation.

Root authorization has a 30-second timeout; input commands have a 5-second timeout.
On failure, MATVT restores normal D-pad input and logs the reason under
**`MATVT-Root`**. After correcting authorization, hold PTT to retry. Disabling the
accessibility service releases the root process and cursor overlay.

The root path does not use network ADB or require the `sonim_faketouch` module.

## Verification

Host-side checks:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 11) bash ./gradlew --no-daemon --console=plain \
  :app:testDebugUnitTest :app:assembleDebug
```

The unit tests cover session reuse, root denial, missing `su`, process exit,
command errors, timeouts, backpressure, shutdown and swipe bounds. They do not
prove touch injection on the physical device.

The standalone `:app:lintDebug` task currently reports the inherited
`ExpiredTargetSdkVersion` error for `targetSdkVersion 29` (a Google Play publishing
requirement), in addition to legacy warnings. APK assembly and unit tests run
independently of that publishing check.

On the XP5900, check in TouchDetector and then a normal app:

1. Grant overlay/accessibility permissions and enable the mouse with PTT.
2. Grant root to MATVT. Verify a tap at the cursor position, including near edges.
3. Check both short and long confirmation presses.
4. Switch to scroll mode, hold/release each direction, and verify scrolling stops
   after the currently running swipe.
5. Hold PTT to return to normal D-pad navigation. Check that auto-repeat does not
   toggle modes multiple times.
6. Deny MATVT root access and reconnect its accessibility service. Verify that
   failed activation leaves normal navigation working; grant access and retry.

Successful operations log their execution time as `Root tap`, `Root long press`
or `Root scroll`. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
