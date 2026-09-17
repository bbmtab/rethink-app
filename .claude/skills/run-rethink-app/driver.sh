#!/usr/bin/env bash
# driver.sh — Build, launch, and drive the RethinkDNS Android app (fdroidFullDebug variant)
#
# Usage: ./driver.sh <command> [args...]
#
# Commands:
#   build            Build the fdroid full debug APK
#   install          Install the APK on the connected device
#   launch           Launch the app via launcher alias
#   screenshot       Take a screenshot of the current app screen
#   dump-ui          Dump the UI hierarchy to /data/local/tmp/window_dump.xml and print text
#   tap-plus         Tap the Plus tab in bottom navigation
#   tap-manage       Tap "Manage Filters" button on Plus tab
#   tap-generate-ca  Tap "GENERATE CA CERTIFICATE" button on Plus tab
#   tap-install-ca   Tap "Install CA Certificate" button on Plus tab
#   go-home          Press back/home to return to home screen
#   wake-unlock      Wake and unlock the device screen
#   test             Run unit tests
#
# Environment:
#   DEVICE_SERIAL    (optional) ADB device serial, default: first device
#                    Set to 3595381c0804 for the Mi A1 A16 test device.
#   PROJECT_DIR      (optional) Project directory, default: script location's parent
#
# All paths in SKILL.md are relative to <unit>/ (project root).

set -euo pipefail

# ---- Configuration ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${PROJECT_DIR:-$(cd "$SCRIPT_DIR/../../.." && pwd)}"

# Device serial — defaults to first connected device if not set
DEVICE_SERIAL="${DEVICE_SERIAL:-}"

# ADB wrapper that includes the serial flag
adb() {
    if [ -n "$DEVICE_SERIAL" ]; then
        command adb -s "$DEVICE_SERIAL" "$@"
    else
        command adb "$@"
    fi
}

# Screenshot output directory
SCREENSHOT_DIR="${PROJECT_DIR}/.claude/skills/run-rethink-app/screenshots"
mkdir -p "$SCREENSHOT_DIR"

# ---- Commands ----

cmd_build() {
    echo "=== Building fdroidFullDebug APK ==="
    cd "$PROJECT_DIR"
    ./gradlew :app:assembleFdroidFullDebug --no-daemon
    echo "=== Build complete ==="
    # APK is ABI-specific: app-fdroid-full-<abi>-debug.apk (arm64-v8a, armeabi-v7a, etc.)
    ls -la app/build/outputs/apk/fdroidFull/debug/app-fdroid-full-*-debug.apk
}

cmd_install() {
    echo "=== Installing APK ==="
    # APK is ABI-specific; prefer arm64-v8a for the Mi A1 A16, fall back to universal
    local apk_dir="app/build/outputs/apk/fdroidFull/debug"
    local apk="$apk_dir/app-fdroid-full-arm64-v8a-debug.apk"
    if [ ! -f "$apk" ]; then
        apk="$apk_dir/app-fdroid-full-universal-debug.apk"
    fi
    if [ ! -f "$apk" ]; then
        echo "APK not found in $apk_dir — run 'build' first."
        exit 1
    fi
    adb install -r "$apk"
    echo "=== Install complete ==="
}

cmd_launch() {
    echo "=== Launching app ==="
    wake_unlock
    # Use the launcher alias (exported=true, intent-filter=MAIN/LAUNCHER)
    # Direct launch of HomeScreenActivity fails with SecurityException (not exported)
    adb shell am start -n "com.celzero.bravedns.plus/com.celzero.bravedns.ui.activity.LauncherAliasAppLock"
    sleep 3
    echo "=== App launched ==="
}

cmd_screenshot() {
    local name="${1:-screenshot}"
    local outfile="$SCREENSHOT_DIR/${name}.png"
    echo "=== Screenshot → $outfile ==="
    # NOTE: On Android 16, `screencap -p /path/file.png` fails (the -p flag
    # means "PNG format" and writes to stdout). Use `adb exec-out screencap -p > file`
    # instead. This was verified on the Mi A1 A16 (Android 16).
    adb exec-out screencap -p > "$outfile"
    echo "Saved: $outfile ($(wc -c < "$outfile") bytes)"
}

cmd_dump_ui() {
    # Use /data/local/tmp/ instead of /sdcard/ to avoid Windows path mangling
    local outfile="${1:-/data/local/tmp/window_dump.xml}"
    echo "=== Dumping UI to $outfile ==="
    adb shell uiautomator dump "$outfile" 2>&1 || true
    # On Android 16, the dump succeeds; print text + content-desc elements
    echo "--- Text elements ---"
    adb shell cat "$outfile" 2>&1 | grep -oE 'text="[^"]*"' | sort -u | grep -v '^text=""'
    echo "--- Content-desc elements ---"
    adb shell cat "$outfile" 2>&1 | grep -oE 'content-desc="[^"]*"' | sort -u | grep -v '^content-desc=""'
}

cmd_tap_plus() {
    echo "=== Tapping Plus tab (bounds [432,1746][648,1920], center 540,1833) ==="
    adb shell input tap 540 1833
    sleep 2
}

cmd_tap_manage() {
    echo "=== Tapping Manage Filters button (bounds ~[709,1678][972,1756], center 840,1717) ==="
    adb shell input tap 840 1717
    sleep 3
}

cmd_tap_generate_ca() {
    echo "=== Tapping GENERATE CA CERTIFICATE (bounds ~[82,929][998,1033], center 540,981) ==="
    adb shell input tap 540 981
    sleep 2
}

cmd_tap_install_ca() {
    echo "=== Tapping Install CA Certificate button ==="
    # Find the button dynamically from current UI dump
    adb shell input tap 998 1155
    sleep 3
}

cmd_wake_unlock() {
    wake_unlock
}

cmd_go_home() {
    echo "=== Returning to home screen ==="
    adb shell input keyevent KEYCODE_BACK
    sleep 1
    adb shell input keyevent KEYCODE_HOME
    sleep 2
}

cmd_test() {
    echo "=== Running unit tests ==="
    cd "$PROJECT_DIR"
    ./gradlew testFdroidFullDebugUnitTest --no-daemon
}

wake_unlock() {
    echo "Waking device..."
    adb shell input keyevent KEYCODE_WAKE
    sleep 1
    # Swipe up to unlock if keyguard is showing
    adb shell input swipe 500 1800 500 200 500
    sleep 1
}

# ---- Main ----

case "${1:-}" in
    build)           cmd_build ;;
    install)         cmd_install ;;
    launch)          cmd_launch ;;
    screenshot)        cmd_screenshot "${2:-screenshot}" ;;
    dump-ui)         cmd_dump_ui "${2:-/sdcard/window_dump.xml}" ;;
    tap-plus)        cmd_tap_plus ;;
    tap-manage)      cmd_tap_manage ;;
    tap-generate-ca) cmd_tap_generate_ca ;;
    tap-install-ca)  cmd_tap_install_ca ;;
    wake-unlock)     cmd_wake_unlock ;;
    go-home)         cmd_go_home ;;
    test)            cmd_test ;;
    *)
        echo "Usage: $0 <command> [args...]"
        echo ""
        echo "Commands:"
        echo "  build            Build the fdroidFullDebug APK"
        echo "  install          Install the APK on the connected device"
        echo "  launch           Launch the app via launcher alias"
        echo "  screenshot [name] Take a screenshot (saved to screenshots/)"
        echo "  dump-ui [path]   Dump UI hierarchy and print text"
        echo "  tap-plus         Tap the Plus tab in bottom navigation"
        echo "  tap-manage       Tap 'Manage Filters' button"
        echo "  tap-generate-ca  Tap 'GENERATE CA CERTIFICATE' button"
        echo "  tap-install-ca   Tap 'Install CA Certificate' button"
        echo "  wake-unlock      Wake and unlock the device"
        echo "  go-home          Return to home screen"
        echo "  test             Run unit tests"
        exit 1
        ;;
esac
