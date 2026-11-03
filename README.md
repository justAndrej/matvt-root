# MATVT

A fork of [virresh/matvt](https://github.com/virresh/matvt), developing a root-enabled
virtual mouse for Android keypad phones, primarily the **Sonim XP5900 / XP5plus**.
The application is displayed as **MATVT**.

> **Active development is on the [`phone-port` branch](https://github.com/justAndrej/matvt-root/tree/phone-port)
> of this repository.** Use that branch for the current phone version, its features
> and fixes.

> **The phone version requires root access.** Grant superuser permission to
> **MATVT** in Magisk or a compatible root manager, along with overlay and
> accessibility permissions. Root permission for ADB alone is not sufficient.

## Start here

- [Phone version: README, installation and controls](https://github.com/justAndrej/matvt-root/blob/phone-port/README.md)
- [Source code — `phone-port`](https://github.com/justAndrej/matvt-root/tree/phone-port)
- [Sonim implementation and verification notes](https://github.com/justAndrej/matvt-root/blob/phone-port/docs/sonim-xp5900.md)
- [Manual APK build and GitHub Release setup](https://github.com/justAndrej/matvt-root/blob/phone-port/docs/github-releases.md)

## Manual builds and releases

After configuring the signing secrets, open **Actions → Build and publish APK →
Run workflow**. Leave **Use workflow from** on `master`, and choose the application
branch in **source_branch** (default `phone-port`). Supply a release tag and an
increasing Android versionCode; the workflow tests, builds, signs and publishes
the APK at the exact source commit.

The release APK uses the existing Android Studio signing key from GitHub Secrets.
See the [setup guide](https://github.com/justAndrej/matvt-root/blob/phone-port/docs/github-releases.md)
for the four secrets and release options.

## Branches

| Branch | Purpose |
| --- | --- |
| **`phone-port`** | Active development of the rooted phone version, with the changes listed below. |
| `master` | Retained upstream TV/experimental-engine base. Its source does not contain the current phone-branch implementation. |

## Phone-branch features and fixes

- **Local root touch injection** in one persistent executor, with no network ADB
  or connected computer required during use.
- **PTT activation, code 289**: hold to enable/disable the mouse; briefly press to
  switch mouse/scroll modes. Activation can be customized.
- **Real continuous holds**: center/Enter puts a finger down and releases it when
  the physical key is released. D-pad arrows move the cursor or scroll.
- **Phone-sized vector cursors**: 16–32 px, default 24 px, dark/light styles,
  immediate setting changes, a working size slider and actual-size preview.
- **Scrollable, D-pad-friendly settings**, including navigation to editable
  fields and Save buttons.
- **Optional Speaker-key density switching, code 290**: configurable compact DPI
  (default 100) versus factory DPI (160 on the inspected XP5900). Includes feature
  enablement, custom key codes, key detection and checks for control conflicts.
- **Scale restoration** on mouse-mode exit, locking and normal service shutdown;
  recovery of a stale override on the next app/service launch after a hard crash.
  Density uses the same root executor rather than a second resident process.
- **Lock-screen handling**: automatic mouse shutdown and native key pass-through
  while locked. Keyboard-mode restoration fixed the XP5900's first-unlock failure
  after synthetic touch input, confirmed by the device owner in both mouse-on and
  mouse-off-before-lock scenarios.
- **Input lifecycle fixes**: ordered DOWN/UP/CANCEL delivery, no accumulating swipe
  backlog, timeouts and return to normal D-pad navigation on root failure.

The phone version is developed and tested on **Sonim XP5900 / Android 11**.
The [phone README](https://github.com/justAndrej/matvt-root/blob/phone-port/README.md)
contains the current setup, build requirements, limitations and diagnostics.

## Get the phone sources

```sh
git clone --branch phone-port https://github.com/justAndrej/matvt-root.git
```

Open that checkout in Android Studio and follow the phone README's build/setup
instructions. Upstream release APKs are separate builds and do not include this
fork's current root-phone changes.

## Upstream and credits

- [Original MATVT project and documentation](https://github.com/virresh/matvt).
- [Upstream contributors and credits](https://github.com/virresh/matvt#credits),
  including EVA Facial Mouse, sweenwolf, greatjack1, aquirozc, ariehcore and
  TechDoctorUK's demonstrations.
- [GPL-3.0 license](LICENSE).
