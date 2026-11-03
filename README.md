# MATVT

A root-enabled virtual mouse for Android keypad phones, developed for the
**Sonim XP5900 / XP5plus**. Control a cursor, tap, hold and scroll using the
phone's physical buttons.

> **Root is required.** Grant superuser access to **MATVT** in Magisk or your
> compatible root manager. Overlay and accessibility permissions are also required.
> Granting root to ADB or another app does not grant it to MATVT.

**Active development is on [`phone-port`](https://github.com/justAndrej/matvt-root/tree/phone-port)
in this repository.** This is a fork of [virresh/matvt](https://github.com/virresh/matvt),
based on the upstream phone-port sources. The root implementation and phone fixes
described here belong to this fork.

- Displayed application name: **MATVT**.
- Android package: `io.github.virresh.matvt`.
- Primary validation device: **Sonim XP5900, Android 11, 240 × 320 display**.
- [Detailed implementation, research and verification notes](docs/sonim-xp5900.md).

## Features and fixes

| Area | What this fork adds |
| --- | --- |
| Root input | Real touch injection through one persistent local root executor, instead of accessibility gestures that were cancelled on the XP5900. No connected PC or network ADB is needed during normal use. |
| Continuous holds | Center/Enter sends touch DOWN when pressed and UP when released. A long press lasts as long as the key is held; the receiving app decides when to recognize it. |
| Mouse and scrolling | D-pad cursor movement with acceleration and edge wrapping; a separate scroll mode with directional swipes. Scroll repeats wait for the preceding swipe to finish. |
| Phone-sized cursor | Five heights: **16, 20, 24, 28 and 32 px**, with **24 px** as the default. Dark and light vector arrows have contrasting outlines. |
| Live appearance settings | Fixed the non-working size slider and delayed style changes. Both now update the overlay immediately, with an actual-size preview and numeric size label. The arrow tip stays on the click coordinate. |
| Keypad-friendly settings | Scrollable settings screen and explicit D-pad focus navigation, including the custom activation-key field and Save button. |
| Activation key | **PTT, Android key code 289**, by default; a custom activation key can be saved in settings. |
| Screen density | Optional **Speaker-key hold, code 290**, toggles a configurable compact density (default **100 dpi**) and the factory density (**160 dpi on the inspected XP5900**). |
| Density configuration | Feature toggle, DPI field, custom key code and key detection. Conflicting navigation, call and mouse-control keys are rejected. |
| Density cleanup | Factory scale is restored when mouse mode ends, the feature is disabled/edited, or the screen locks. A pending marker supports recovery after a hard crash on the next app/service launch. No second resident root process is used. |
| Lock-screen behavior | Screen-off/locking disables mouse mode and releases active input. Unlock keys pass through, including presses held across unlocking. The mouse stays off after wake. |
| Sonim unlock fix | After finishing touch input, MATVT explicitly returns Android to keyboard navigation. The device owner verified successful unlocking with both mouse-on and mouse-off-before-lock scenarios. |
| Failure handling | Ordered DOWN/UP/CANCEL events, bounded input work, timeouts and cleanup on service shutdown. Root failures return control to normal D-pad navigation. |

## Requirements

1. A **rooted phone with working `su`**, tested with Magisk on the XP5900.
2. Superuser permission for **MATVT** itself.
3. Permission to **display over other apps**.
4. The **Mouse Toggle Service** enabled in Android accessibility settings.

Other devices and vendor ROMs need their own compatibility checks. The build's
minimum SDK setting is not a claim that every Android version/device has been tested.

## Install and set up

1. [Build the `phone-port` branch](#build-from-source), then install from Android
   Studio or with:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. Grant the overlay permission. On a phone without a usable settings switch:

   ```sh
   adb shell appops set io.github.virresh.matvt SYSTEM_ALERT_WINDOW allow
   adb shell appops get io.github.virresh.matvt SYSTEM_ALERT_WINDOW
   ```

   The query should report `allow`. If the shell lacks permission, use:

   ```sh
   adb shell su -c 'appops set io.github.virresh.matvt SYSTEM_ALERT_WINDOW allow'
   ```

3. Enable **MATVT / Mouse Toggle Service** in accessibility settings. The app's
   **Setup permissions** button opens the relevant settings screens.

4. Hold PTT to activate the mouse. Allow MATVT's root request in Magisk.

ADB is optional for setup and installation; it is not the runtime input backend.
Upstream TV/phone release APKs are separate builds, not this root fork.

<details>
<summary>Enable accessibility through ADB when the vendor settings screen does not expose the service</summary>

After installing MATVT, this command adds its service while preserving other
enabled accessibility services:

```sh
adb shell '
service=io.github.virresh.matvt/io.github.virresh.matvt.services.MouseEventService
short=io.github.virresh.matvt/.services.MouseEventService
current=$(settings get secure enabled_accessibility_services)
case "$current" in null|"") current= ;; esac
case ":$current:" in
  *":$service:"*|*":$short:"*) ;;
  *)
    if [ -z "$current" ]; then current="$service"; else current="$current:$service"; fi
    settings put secure enabled_accessibility_services "$current"
    ;;
esac
settings put secure accessibility_enabled 1
'
```

</details>

## Controls

| Button / action | Result |
| --- | --- |
| Hold **PTT (289)** for about **800 ms** | Enable or disable mouse mode |
| Briefly press PTT while enabled | Switch between mouse and scroll modes |
| D-pad arrows in mouse mode | Move the cursor |
| D-pad arrows in scroll mode | Scroll with directional swipes |
| Press and release center / Enter | Tap at the cursor |
| Hold center / Enter | Keep a virtual finger down until release |
| Hold **Speaker (290)** for about **800 ms** | Toggle compact/factory density, if configured and enabled |
| Lock or turn off the screen | Cancel input, disable the mouse and restore managed density |

Confirmation accepts `DPAD_CENTER` (**23**), `ENTER` (**66**) and numpad Enter
(**160**). Existing custom activation-key preferences remain in effect; turn off
**Override Activation Key** to return to PTT 289.

The cursor hides after inactivity. Legacy remotes can also use the Info key to
toggle mouse mode and color keys for swipes. Holding center while moving the
D-pad is not drag-and-drop: pointer movement is paused during a held contact.

## Cursor settings

Use left/right on the focused size slider. The label shows the pixel height and
the preview works even when mouse mode is off. Size and style changes take effect
without restarting the service or toggling PTT.

Previously saved slider positions are mapped to the new 16–32 px range; select
the middle position for the recommended **24 px** size. Sizes are physical pixels,
so changing display density does not enlarge the cursor.

## Optional screen-density hotkey

This changes **logical DPI / interface scale**, like `wm density`; the display's
physical resolution stays the same.

1. In MATVT settings, choose **Compact DPI** — **100** by default. Values must be
   at least **72** and below the device's factory density.
2. Keep key code **290**, enter another Android key code, or use **Detect key**
   with mouse mode off.
3. Turn on **Enable density hotkey** and save. The feature is **off by default**.
4. While mouse or scroll mode is enabled, hold the selected key to switch scale.

On Sonim, disable **Settings → Accessibility → Voice Command**: the stock firmware
also assigns that action to a long Speaker press. MATVT reserves the whole key
press outside calls while this feature and mouse mode are enabled. It bypasses
the hotkey when Android reports ringing, in-call, communication/VoIP or an unknown
audio mode, so the phone can use its speaker control.

With the feature enabled, entering mouse mode takes ownership of display density,
including an existing manual override. On exit, it restores the **factory value**,
not that earlier override. After a hard-killed executor or power loss, a readable
compact scale may remain until the next app/service startup recovers the marker.
The feature uses the same root executor as touch input.

## Lock-screen fix

The XP5900 could fail to unlock on the first screen presentation after a synthetic
tap, even when the MATVT service was disabled during the tap. Simply disabling
mouse mode on screen-off was not enough.

MATVT now waits for the receiving app to process UP/CANCEL, then requests keyboard
navigation with `setInTouchMode(false)` when mouse mode ends or the executor closes.
The owner confirmed the fix on the phone, including locking with the mouse still
enabled. The lock screen remains in control of unlocking through **star (`*`)**.

After unlocking, hold PTT again to enable the mouse. See the
[lock-screen investigation and regression checks](docs/sonim-xp5900.md#lock-screen-handling)
for details.

## Build from source

For a server-side build and publication, use the manual
[GitHub Actions release workflow](docs/github-releases.md). Its form selects the
source branch (default `phone-port`), release tag and Android versionCode. APKs are
signed with the existing Studio key stored in GitHub Secrets.

```sh
git clone --branch phone-port https://github.com/justAndrej/matvt-root.git
cd matvt-root
```

Verified build configuration:

| Component | Version |
| --- | --- |
| JDK | **11** |
| Gradle wrapper | **7.6.6** |
| Android Gradle Plugin | **7.1.0** |
| Android compile / target SDK | **29** |
| Android Build Tools | **30.0.3** |

Open the project in Android Studio, select JDK 11 for Gradle and configure the
Android SDK location. Alternatively, set `sdk.dir` in the ignored `local.properties`
file and run with `JAVA_HOME` pointing to JDK 11:

```sh
bash ./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug
```

On macOS with a registered JDK 11 installation:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 11) \
  bash ./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug
```

- Installable debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- `:app:assembleRelease` produces `app/build/outputs/apk/release/app-release-unsigned.apk`;
  it requires signing before installation and may hit the inherited Play target-SDK
  lint check. The manual release workflow handles signing and that specific check.
- New local Android Studio files under `.idea/` are ignored; existing tracked
  project settings remain versioned.

## Verification and troubleshooting

Automated tests cover event ordering, continuous holds, cancellation, shutdown,
density recovery, key conflicts, lock-screen key handling and keyboard-mode restoration.
Emulator checks exercise real touch events, DPI changes and process termination.
Root clicking, continuous holds and the unlock fix have also been confirmed on
the XP5900. See the [verification notes](docs/sonim-xp5900.md#verification).

- **Root request fails:** grant root to MATVT, then hold PTT to retry. The app falls
  back to normal D-pad control on a root error.
- **A saved key differs from the default:** disable **Override Activation Key**
  for PTT 289; density has its own independently configurable key.
- **Density stayed compact after a crash:** open MATVT with mouse mode off or
  reconnect its accessibility service. Manual reset: `adb shell wm density reset`.
- **Useful Logcat tags:** `MATVT-Root`, `MATVT-InputMode`, `MATVT-Screen`,
  `MATVT-Density`. Keyboard cleanup logs `KEYBOARD_MODE_RESTORED`.
- **Standalone lint:** the inherited target SDK 29 triggers
  `ExpiredTargetSdkVersion`, a Google Play publishing check. APK assembly and unit
  tests run independently of that check.

When reporting a problem with this fork, include the phone/Android version,
tested commit, button codes and relevant logs, and identify the `phone-port` build.

## Credits

- [virresh/matvt](https://github.com/virresh/matvt) — the original project and phone port.
- [EVA Facial Mouse](https://github.com/cmauri/eva_facial_mouse) — original design inspiration.
- [sweenwolf](https://github.com/sweenwolf) and [greatjack1](https://github.com/greatjack1)
  — upstream remote/phone contributions; TechDoctorUK — upstream demonstrations.
- [All upstream contributors and credits](https://github.com/virresh/matvt#credits).
- [Upstream GPL-3.0 license](https://github.com/virresh/matvt/blob/master/LICENSE).
