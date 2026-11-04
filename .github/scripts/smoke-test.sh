#!/usr/bin/env bash
# Opens the app on the emulator and fails if it crashes. Prints the crash details.
set -u
PKG=com.aistudio.mediadownloader.ftpxyz
APK=apk/app-x86_64-debug.apk

adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb logcat -c

check() {
  local step="$1"
  sleep 25
  adb logcat -d > "logcat-$step.txt"
  if grep -A3 "FATAL EXCEPTION" "logcat-$step.txt" | grep -q "Process: $PKG"; then
    echo "::error::App crashed during step: $step"
    grep -A60 "FATAL EXCEPTION" "logcat-$step.txt" | head -120
    exit 1
  fi
  if ! adb shell pidof "$PKG" >/dev/null; then
    echo "::error::App is no longer running after step: $step"
    grep -i "$PKG\|AndroidRuntime\|ActivityManager" "logcat-$step.txt" | tail -80
    exit 1
  fi
  echo "OK: $step"
}

# 1. Normal launch.
adb shell am start -W -n "$PKG/com.example.MainActivity"
check launch

# 2. A link shared from another app (like YouTube's Share button).
adb shell am start -W -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://www.youtube.com/watch?v=jNQXAC9IVRw" \
  -n "$PKG/com.example.MainActivity"
check share

echo "Smoke test passed: the app opened and stayed open."
