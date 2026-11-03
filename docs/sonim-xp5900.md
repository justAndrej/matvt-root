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
- Press the D-pad center/Enter to put a virtual finger down at the cursor. Release
  the key to lift it. Holding the key keeps the same contact active for as long
  as the key is held; the receiving app determines its long-press behavior.
- The activation key defaults to **289**, including the empty custom-key field.
  Existing explicit overrides remain in effect; uncheck **Override Activation Key**
  to use the new default.
- Locking or turning off the screen disables mouse/scroll mode, cancels a held
  contact and closes the root helper. After waking/unlocking, normal D-pad input
  remains active; hold PTT again to enable the mouse.

## Lock-screen handling

The accessibility service listens for screen-off, screen-on and user-present
broadcasts, and checks `PowerManager.isInteractive()` and
`KeyguardManager.isKeyguardLocked()` before processing any key. The latter also
covers non-PIN keyguards. Window-state events detect locking without screen-off.

While the screen is off or keyguard is showing, all keys pass through to Android,
including the Sonim's star (`*`) unlock key, PTT and Home. Presses that began before
suspension or on the lock screen keep passing through until released, even if
unlocking happens mid-hold. A fresh press is required to activate MATVT afterward.
Suspension discards the old engine, overlay, activation timers and consumed-key
state, so a delayed root-ready callback cannot re-enable the mouse after locking.

### Keyboard-mode restoration — verified on XP5900

The XP5900 unlock issue was reproduced after both `input touchscreen tap` and
`input mouse tap`, with the MATVT accessibility service disabled. Moving the
cursor alone did not reproduce it. During one failed unlock, the dispatcher had
no active touch and the global `mInTouchMode` flag was already false, so the exact
Sonim keyguard defect is still unconfirmed.

The fix restores keyboard navigation **before the next lock-screen appearance**.
When mouse mode is turned off, `DPAD_MODE` is queued after the
contact's terminal event. It cancels any remaining contact, restores owned density,
then calls the public `Instrumentation.setInTouchMode(false)` SDK wrapper in the
existing root executor. On Android 11 it invokes `IWindowManager.setInTouchMode(false)`;
the framework handles the newer WindowManager signatures on later releases. No key
press is synthesized and the lock screen is not dismissed.

UP/CANCEL now use `INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH`, so the receiving app
finishes handling the release before keyboard mode is restored. DOWN/MOVE retain
`WAIT_FOR_RESULT`. QUIT, EOF and touch-timeout cleanup also attempt restoration
if this executor injected a pointer DOWN since its last restoration. A density-only
recovery invocation does not alter another input session's touch mode.

Diagnostics:

- App Logcat: `MATVT-Root` → `Keyboard navigation: KEYBOARD_MODE_RESTORED`.
- Root executor Logcat: `MATVT-InputMode` → `setInTouchMode(false) completed`.
- Exceptions from the call are reported as errors. The success log confirms that
  the SDK call returned, not that Sonim's keyguard is fixed. The SDK itself may
  swallow a WindowManager `RemoteException`, so on-device behavior is the final check.

On an emulator, a test view deliberately delayed UP/CANCEL handling by 350 ms.
The executor waited for that handler to finish, then successfully changed touch
mode to keyboard mode. QUIT/EOF restoration and density-only recovery isolation
were also checked. The device owner confirmed that this fixes unlocking on the
XP5900 in both cases: mouse mode still enabled at lock time, and mouse mode
already disabled before locking. This confirms the fix in those scenarios;
the precise underlying Sonim firmware defect has not been established.

For regression testing, begin after a reboot and keep the density hotkey disabled to
isolate this change. Tap in TouchDetector using MATVT, turn mouse mode off with
PTT, then lock/wake and try the first hold of **star (`*`)**. Repeat several times.
Separately check locking directly while mouse mode is still on: that exercises
the screen-off cleanup timing rather than an earlier manual switch to D-pad.

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

## Speaker-key screen density

This changes **logical display density**, like `wm density`, rather than the
physical 240 x 320 resolution. The inspected XP5900 reports **160 dpi** as its
factory value; the implementation reads the factory value from WindowManager.

In MATVT settings:

1. Leave mouse mode before using **Detect key**.
2. Set **Compact DPI** (default **100**, minimum **72**, strictly below factory).
3. The default is **290**, the XP5900 Speaker Android key code confirmed by the
   device owner. You can enter another code, or press **Detect key** and tap the
   desired button. Linux scan code 213 from the key layout is not this Android code.
4. Turn on **Enable density hotkey** and save. The feature is off by default.
5. In mouse or scroll mode, hold the selected key for **800 ms** to toggle compact
   and factory scale. Repeats and release do not toggle a second time.

### Phone-feature conflicts

The [Sonim global guide, page 24](https://sonim-tech.files.svdcdn.com/production/documents/xp5plus_downloads/Userguides/Sonim_XP5plus_UG_ROW_090722_Final.pdf)
assigns a long Speaker press to **Voice Commands**, and Speaker switches call
audio between earpiece, loudspeaker and accessories. Disable Sonim's
**Settings → Accessibility → Voice Command** to avoid vendor handling the hold
before Android's accessibility filter.

MATVT reserves the complete key press only with mouse mode and this feature on,
on an unlocked/interactive display, and in `AudioManager.MODE_NORMAL`. Ringing,
in-call, communication/VoIP and unknown audio modes bypass the density hotkey.
The mode is checked again when the hold fires. A detected call also restores an
active density lease. No phone-state runtime permission is added. Vendor-specific
call behavior still needs verification on the actual phone.

PTT/mouse activation, D-pad/Enter, hash/star, Home/Back, call/end-call, volume,
power and existing mouse/scroll keys cannot be assigned as the density hotkey.
When reserved outside calls, short presses are consumed too, to prevent the
native Speaker/voice action from starting alongside the density toggle.

### Restoration and recovery

With this feature enabled, entering mouse mode acquires a density lease. This
also adopts an existing manual override, without changing it immediately. Leaving
mouse mode, disabling/editing the feature, locking the screen or stopping the
service clears the override to the **factory value**, not to a previous override.
With the feature off and no pending MATVT recovery, unrelated manual density
settings are left alone.

Density is managed by `DensityController` **inside the existing root touch
executor**. There is no second resident root process or density watchdog. Normal
reset/QUIT and pipe EOF restore factory density directly.

A marker in the app's private files is written before the override and removed
only after a successful reset. If the executor is killed or the device loses
power, compact density may remain in effect. Opening/returning to MATVT with mouse
mode off, reconnecting the accessibility service, or starting the next mouse
executor retries the pending restoration. Recovery uses a short-lived invocation
of the same executor and then exits; no boot/package-update receiver is needed.
Root access must still be available for restoration.

The inter-process lock prevents recovery from resetting an active mouse session.
Opening settings while the mouse is using compact density leaves that working
mode alone. Old/new executor shutdown and startup are also serialized by this lock.

Manual recovery, if needed: `adb shell wm density reset` (or
`adb shell su -c 'wm density reset'` if the shell lacks permission).

The backend calls the same `IWindowManager` methods as `wm density` and
`wm density reset` for the current user. Scaling does not change cursor pixel
coordinates or cursor size. A density change cannot overlap a held touch, and a
reset is queued even when a density change is already in flight.

Density checks cover 100/factory toggles, explicit reset, EOF, an override remaining
after SIGKILL, recovery on the next startup, and recovery skipping a live owner.

## Implementation and limitations

`RootInput` starts a persistent Java process using `su -c` and `app_process`, loading
`RootTouchServer` from this app's APK. A versioned handshake verifies UID 0 before
enabling mouse mode. Commands and acknowledgements use private stdin/stdout pipes;
no per-gesture shell or `/system/bin/input` process is launched.

The server injects `MotionEvent`s through Android's `InputManager` (or
`InputManagerGlobal` on newer releases). DOWN, MOVE, UP and CANCEL belonging to a
contact share the same `downTime`, pointer ID, touchscreen source and display ID.
Short and long presses use the same DOWN/UP sequence, without a fixed-duration
swipe or a second tap on release. Key auto-repeat does not inject additional DOWNs.
Pointer movement stays disabled while the center key is held; dragging is not implemented.

There is at most one gesture in flight. A terminal UP is reserved even if DOWN is
still awaiting acknowledgement. Leaving mouse mode promotes a queued UP to CANCEL;
an unsent, cancelled DOWN is skipped. Shutdown sends CANCEL/QUIT before disposing
the process. Scrolling still uses 250 ms swipes, repeated only after completion;
an already-started swipe finishes normally. Additional presses while a previous
gesture is finishing are rejected with a busy message instead of replayed later.

While mouse mode is enabled, the service sends a heartbeat once per second. The
existing touch watchdog cancels an active contact and exits after approximately
four seconds without a heartbeat, covering a stalled/disconnected client. It also
attempts normal density cleanup before exiting. This protects held touches; it is
not a density watchdog or a maximum hold duration. EOF, QUIT, command failures, cancelled key events, mode
changes and service shutdown also trigger cancellation. Back/Home/app-switch/power
keys cancel a held contact before being passed through.

Root startup has a 30-second timeout; input acknowledgements have a 5-second
timeout, and density operations have a 10-second timeout. These timeouts do not limit a healthy hold. On failure, MATVT restores
normal D-pad input and logs the reason under **`MATVT-Root`**. After correcting
authorization, hold PTT to retry. Disabling the accessibility service releases the
root process and cursor overlay.

The root path does not use network ADB or require the `sonim_faketouch` module.

## Verification

Host-side checks:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 11) bash ./gradlew --no-daemon --console=plain \
  :app:testDebugUnitTest :app:assembleDebug
```

The unit tests cover contact lifetime and timestamps, fast release before DOWN
acknowledgement, pending-UP cancellation, bounded heartbeat traffic, root identity,
startup cancellation, failures, shutdown and swipe bounds. They do not prove touch
injection on the physical device.

The actual APK's server was also exercised in an isolated Android emulator via
`app_process`, with a separate touch-receiving activity: short tap, a six-second
hold with native long-click detection, explicit CANCEL, EOF cleanup, heartbeat
expiry, scrolling and QUIT. All contacts kept their original downTime and ended.
For this diagnostic route only, the server accepts Android's shell UID 2000;
the app's client still requires root UID 0 in its handshake.

The standalone `:app:lintDebug` task currently reports the inherited
`ExpiredTargetSdkVersion` error for `targetSdkVersion 29` (a Google Play publishing
requirement), in addition to legacy warnings. APK assembly and unit tests run
independently of that publishing check.

On the XP5900, check in TouchDetector and then a normal app:

1. Grant overlay/accessibility permissions and enable the mouse with PTT.
2. Grant root to MATVT. Verify a tap at the cursor position, including near edges.
3. Check a short press, then hold the center key for 5–10 seconds. Verify there is
   one DOWN, no intermediate UP, and one UP when you release the key.
4. Switch to scroll mode, hold/release each direction, and verify scrolling stops
   after the currently running swipe.
5. Hold PTT to return to normal D-pad navigation. Check that auto-repeat does not
   toggle modes multiple times.
6. Deny MATVT root access and reconnect its accessibility service. Verify that
    failed activation leaves normal navigation working; grant access and retry.
7. While holding the center key, switch off mouse mode or stop the accessibility
   service. The target app should receive CANCEL rather than an extra click.
8. Enable the mouse, lock and wake the screen, and unlock using star (`*`). Verify normal
   key handling, no cursor on the lock screen, and no automatic mouse reactivation.
   Repeat while holding the center key and while PTT activation is still pending.

Successful operations log their acknowledgement time as `Root down`, `Root up`,
`Root cancel` or `Root scroll`. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
