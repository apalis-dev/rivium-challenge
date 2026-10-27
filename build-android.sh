#!/usr/bin/env bash
# Build libtaskping.so for Android, then the debug APK.
set -euo pipefail
cd "$(dirname "$0")"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/30.0.16248370}"
(cd rust && cargo ndk -t arm64-v8a -t x86_64 -P 26 -o ../android/app/src/main/jniLibs build --release)
(cd android && ./gradlew assembleDebug)
echo "APK: android/app/build/outputs/apk/debug/app-debug.apk"
