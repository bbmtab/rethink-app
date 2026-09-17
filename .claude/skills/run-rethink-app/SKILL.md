---
name: run-rethink-app
description: Build, install, and drive RethinkDNS Android app (fdroidFullDebug) on Mi A1 A16. Build, launch, screenshot, navigate Plus tab, CA inspection, Manage Filters.
---

# Run Skill: RethinkDNS Android App

Builds, installs, launches, and drives the RethinkDNS Android app (fdroid/full flavor,
deGoogled, no Firebase) on a connected Android device via ADB.

## Prerequisites

```bash
# JDK 17 (project uses portable JDK at .gradle/portable-jdk17)
# Android SDK at .gradle/android-sdk (auto-downloaded via gradle.properties)
# Device: Mi A1 A16 (serial 3595381c0804), Android 16
# ADB: pre-installed
```

## Build

From project root (`L:/test-code/rethink-app`):

```bash
./gradlew :app:assembleFdroidFullDebug --no-daemon
```

Output: `app/build/outputs/apk/fdroidFull/debug/app-fdroid-full-<abi>-debug.apk`
(ABI-specific: arm64-v8a, armeabi-v7a, x86, x86_64, universal)
Build time: ~31s cached, ~5m41s first run.

## Run (agent path)

Use the driver script for all interactions:

```bash
export DEVICE_SERIAL=3595381c0804
export PROJECT_DIR=L:/test-code/rethink-app

./.claude/skills/run-rethink-app/driver.sh build
./.claude/skills/run-rethink-app/driver.sh install
./.claude/skills/run-rethink-app/driver.sh wake-unlock
./.claude/skills/run-rethink-app/driver.sh launch
./.claude/skills/run-rethink-app/driver.sh screenshot screenshot_app_home
./.claude/skills/run-rethink-app/driver.sh tap-plus
./.claude/skills/run-rethink-app/driver.sh screenshot screenshot_app_plus_tab
./.claude/skills/run-rethink-app/driver.sh tap-manage
./.claude/skills/run-rethink-app/driver.sh screenshot screenshot_app_manage_filters
```

The driver accepts: `build`, `install`, `launch`, `screenshot [name]`,
`dump-ui`, `tap-plus`, `tap-manage`, `tap-generate-ca`, `tap-install-ca`,
`wake-unlock`, `go-home`, `test`.

### Key interaction flows

**Plus tab → Manage Filters:**
```bash
./.claude/skills/run-rethink-app/driver.sh launch
./.claude/skills/run-rethink-app/driver.sh tap-plus       # tap Plus tab
./.claude/skills/run-rethink-app/driver.sh screenshot plus1

# Plus tab shows: HTTPS Inspection card (with CA status), Advanced Filtering card
# (show sources pending), Exclusions card.

./.claude/skills/run-rethink-app/driver.sh tap-manage     # tap "Manage Filters"
./.claude/skills/run-rethink-app/driver.sh screenshot manage_filters

# Manage Filters screen shows category headers (Ads, etc.) and per-source rows.

# Navigate back to Plus:
adb -s 3595381c0804 shell input keyevent KEYCODE_BACK
./.claude/skills/run-rethink-app/driver.sh screenshot plus_back
```

**CA Certificate Generation flow:**
```bash
./.claude/skills/run-rethink-app/driver.sh launch
./.claude/skills/run-rethink-app/driver.sh tap-plus      # tap Plus tab
./.claude/skills/run-rethink-app/driver.sh tap-generate-ca # tap GENERATE CA CERTIFICATE
./.claude/skills/run-rethink-app/driver.sh screenshot ca_ready

# After generating CA, the UI updates:
# - "Current CA ready" subtitle appears
# - "Install CA Certificate" button becomes enabled
# - "Save Certificate to Downloads" button becomes enabled
# - "GENERATE CA CERTIFICATE" button stays visible but CA is already created
```

### Verified UI coordinates (1080×1920 screen)

| Element | Bounds | Tap coords |
|---|---|---|
| Plus tab (bottom nav) | `[432,1746][648,1920]` | `540 1833` |
| Manage Filters button | `[709,1678][972,1756]` | `840 1717` |
| GENERATE CA CERTIFICATE btn | `[82,929][998,1033]` | `540 981` |
| Install CA Certificate btn | `[82,1103][998,1207]` | `540 1155` |

## Run (human path)

1. Open Android Studio
2. Select `fdroidFullDebug` variant
3. Click "Run" → app builds and installs automatically
4. On device, tap the app icon to launch
5. Bottom navigation: Home / Stats / Plus / Configure / About

## Test

```bash
./.claude/skills/run-rethink-app/driver.sh test
# or directly:
./gradlew testFdroidFullDebugUnitTest --no-daemon
```

Verified pass: `FilterSourceDownloadManagerTest` — 12 tests, 12 passed, 0 failures (8.9s).
Note: `EasyListRatioTest` and `RpnProxyManagerTest` have pre-existing failures related to
Java 17 vs SDK 36 (Robolectric warns "Android SDK 36 requires Java 21"). These are not
introduced by this skill.

Instrumented tests:
```bash
export ANDROID_SERIAL=3595381c0804
./gradlew connectedFdroidFullDebugAndroidTest --no-daemon
```
Result: 39/44 passed. 5 failures are pre-existing (test hardcoded package name
`com.celzero.bravedns` but app ID is `com.celzero.bravedns.plus`).

## Gotchas

1. **Launcher alias, not direct activity.** `HomeScreenActivity` is `android:exported="false"`
   (see `app/src/full/AndroidManifest.xml:17`). Launching via
   `am start -n com.celzero.bravedns.plus/.ui.HomeScreenActivity` produces
   `SecurityException: not exported from uid 10440`. Use the launcher alias instead:
   ```bash
   adb shell am start -n com.celzero.bravedns.plus/com.celzero.bravedns.ui.activity.LauncherAliasAppLock
   ```

2. **`screencap -p` syntax on Android 16.** On this Mi A1 A16 (Android 16),
   `adb shell screencap -p /sdcard/file.png` fails with "usage: screencap"
   (the `-p` flag means "PNG format" and writes to stdout, not to a file).
   The correct pattern for binary capture is:
   ```bash
   adb exec-out screencap -p > localfile.png
   ```

3. **adb path resolution on Windows.** `adb pull /sdcard/file.png` may resolve
   to a wrong local path like `I:/Program Files/Git/sdcard/file.png` because
   Git Bash mangles `/sdcard/` paths. Use `adb exec-out` for binary data and
   `adb shell cat` for text content.

4. **UI dump requires uiautomator.** The `uiautomator dump` command must succeed
   before reading the XML. Use `/data/local/tmp/` as the output path instead of
   `/sdcard/` to avoid path mangling issues:
   ```bash
   adb shell uiautomator dump /data/local/tmp/window_dump.xml
   adb shell cat /data/local/tmp/window_dump.xml
   ```
   The first run may show "Can't find service: uiautomator" — just retry; the
   service is available on the device.

5. **Screen must be awake and unlocked.** If the device is in Doze or locked,
   `adb shell input tap` silently fails. Wake and swipe first:
   ```bash
   adb shell input keyevent KEYCODE_WAKE
   adb shell input swipe 500 1800 500 200 500
   ```

6. **Gradle daemon file locks.** After running `./gradlew --stop`, the test
   results directory may have stale `binary/output.bin` files that cause
   `IOException: Unable to delete directory`. Clean the directory or use
   `--rerun-tasks` to force a fresh run.

7. **Bottom nav item bounds vary.** The bottom navigation has 5 items
   (Home/Stats/Plus/Configure/About) on a 1080px-wide screen, each ~216px wide.
   The Plus tab center is at `540 1833` — coordinates are relative to the
   device screen, not the app window. If using a different resolution device,
   recalculate based on the UI dump bounds.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `SecurityException: not exported` | Use `LauncherAliasAppLock` instead of `HomeScreenActivity` |
| `screencap: usage:` | Use `adb exec-out screencap -p > file.png` instead of `adb shell screencap -p /path` |
| `adb pull` wrong path | Use `adb exec-out` for binary, `adb shell cat` for text |
| APK path not found | Output has ABI suffix: `app-fdroid-full-arm64-v8a-debug.apk`. Install the matching ABI or universal variant. |
| `uiautomator dump` path mangled | Use `/data/local/tmp/` instead of `/sdcard/` for the output path |
| `Can't find service: uiautomator` | Retry `uiautomator dump` — the service initializes on-demand |
| `NoSuchFieldException` in tests | Pre-existing; related to Java 17 vs SDK 36. Not a blocker for app interaction. |
| App shows lock screen / keyguard | Run `adb shell input keyevent KEYCODE_POWER` then swipe to unlock |
| Bottom nav tap doesn't register | Ensure screen is awake and not in screensaver mode |
