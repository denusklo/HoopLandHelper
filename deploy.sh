#!/bin/bash
# Build, install APK, and stream logs — all from WSL2
set -e

ADB="adb.exe"
APK="app/build/outputs/apk/debug/app-debug.apk"
WIN_APK="C:\\Projects\\HoopLandHelper\\HoopLandHelper-v1.0-debug.apk"
LOG_DIR="logs"
mkdir -p "$LOG_DIR"

echo "Building..."
./gradlew assembleDebug

echo "Copying APK to Windows..."
cp "$APK" "/mnt/c/Projects/HoopLandHelper/HoopLandHelper-v1.0-debug.apk"

echo "Installing..."
"$ADB" install -r "$WIN_APK"

echo "Clearing logcat and log file..."
"$ADB" logcat -c
> "$LOG_DIR/latest.log"

echo "Streaming logs → logs/latest.log (Ctrl+C to stop)"
"$ADB" logcat -s HoopLandHelper 2>&1 | tee "$LOG_DIR/latest.log"
