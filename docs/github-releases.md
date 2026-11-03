# Manual GitHub APK releases

Workflow: **Build and publish APK** (`.github/workflows/release.yml`). It builds a
non-debuggable **release** APK and signs it with the existing Android Studio debug
keystore stored in GitHub Secrets. Reusing that key permits updates over builds
signed by the same Studio installation while keeping application data.

## One-time repository setup

Commit and push the workflow and `.github/release/` tooling to **master**, the
repository's default branch. GitHub needs the workflow there to expose **Run
workflow**. The same files are also provided on `phone-port`; keep those copies
in sync when changing the release pipeline.

In **Settings → Secrets and variables → Actions → New repository secret**, add:

| Secret | Value for the usual Android Studio debug keystore |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 contents of the existing `~/.android/debug.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | `android` |
| `ANDROID_KEY_ALIAS` | `androiddebugkey` |
| `ANDROID_KEY_PASSWORD` | `android` |

Use the actual credentials if your Studio build uses a custom signing setup.
You can inspect the **public** signing information and keystore path with
`bash ./gradlew :app:signingReport` using the project's Gradle JDK.

On macOS, copy the encoded keystore straight to the clipboard, then paste it into
`ANDROID_KEYSTORE_BASE64`:

```sh
base64 < "$HOME/.android/debug.keystore" | pbcopy
```

Store that value as a **secret**, not a repository variable or committed file.
Keep the same keystore for future releases: generating a new debug key on each
runner would produce incompatible APK signatures. The workflow only decodes the
key in a temporary directory for signing and removes that temporary file afterward.

GitHub supplies `GITHUB_TOKEN` automatically; no personal access token is needed.
The publication job requests `contents: write` to create tags and releases.

## Run from the GitHub interface

Open **Actions → Build and publish APK → Run workflow**.

There are two separate branch controls:

- **Use workflow from** is GitHub's branch dropdown. It selects the workflow and
  release-tooling revision. Normally leave it on **master**.
- **source_branch** selects the application sources. It defaults to **phone-port**;
  enter another branch name to build a feature branch. That source branch does not
  need to contain the workflow itself. This pipeline targets the phone branch's
  Gradle/Android setup and branches based on it.

Fill in the remaining inputs:

| Input | Example | Meaning |
| --- | --- | --- |
| `tag` | `v1.1.0` | Release/tag name; APK `versionName` becomes `1.1.0` |
| `version_code` | `102` | Android's integer versionCode; use a value above previous distributed builds and at least the source branch's default |
| `prerelease` | unchecked | Check for a test release such as `v1.1.0-rc.1` |

Then click **Run workflow**. Publication happens automatically after the build,
tests, signing and asset verification succeed.

## What the workflow does

1. Checks out the selected source branch and records its exact commit SHA.
2. Installs an isolated Android SDK with platform 29 and Build Tools 30.0.3. Java
   21 runs the SDK installer; Java **11** runs Gradle and APK signing tools.
3. Runs `:app:testDebugUnitTest` and `:app:assembleRelease` with the source branch's
   Gradle wrapper. A CI init script sets the requested APK version without editing
   the source tree and uses Google/Maven Central instead of the legacy JCenter
   entries in older branches.
4. Excludes only `ExpiredTargetSdkVersion`, the Google Play publishing check, from
   this sideloaded release build. All other fatal release lint checks stay enabled.
5. In a separate job, signs with the stored Studio key and verifies the manifest,
   APK alignment, signature and certificate fingerprint. Signing secrets are not
   supplied to the Gradle build job.
6. Creates the Git tag at the **built commit SHA**, not a later branch head.
7. Uploads and verifies assets in a draft, then publishes the release. A pre-release
   does not replace the latest stable release.

Release files:

- `MATVT-v1.1.0.apk` — signed, installable release APK.
- `build-info.json` — source branch/SHA, workflow SHA, version, build-run URL and
  signing-certificate/APK SHA-256 fingerprints.
- `SHA256SUMS` — checksums for the APK and build metadata.

The signed files are also available as a workflow artifact for 30 days. The
unsigned inter-job artifact expires after 7 days. The signing keystore is never
included in either artifact or the release.

## Re-runs and versioning

- Published releases are not overwritten, and existing tags are not moved.
- A failed publication can resume its own draft if the tag still points to the
  exact built commit. Prefer **Re-run failed jobs** so the successful build and
  its artifact are reused. If the source branch has advanced and you rebuild it,
  use a new tag rather than moving the old one.
- A manually created draft without the workflow marker is left alone.
- Release version overrides do not update `app/build.gradle` in Git. After
  installing a CI release, a local Studio build must use a compatible versionCode
  as well as the same key to update it; increase the local defaultConfig value if
  Android rejects it as a downgrade.
- The release build is not debuggable even though its signing certificate is the
  Studio debug certificate. This is a sideloading workflow, not a Google Play upload.

The APK still requires **root permission for MATVT**, plus overlay and
accessibility permissions. See the [phone README](../README.md#install-and-set-up).
