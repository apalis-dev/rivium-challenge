#!/usr/bin/env bash
# Cross-compile the engine for arm64 phones and drop it into the Android app.
# Needs: rustup target add aarch64-linux-android; cargo install cargo-ndk; ANDROID_NDK_HOME.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
cd "$HERE/rust"
CARGO_BUILD_JOBS=${CARGO_BUILD_JOBS:-2} cargo ndk -t arm64-v8a -P 26 \
  -o "$HERE/client/androidApp/src/main/jniLibs" build -p radar-jni --release
ls -la "$HERE/client/androidApp/src/main/jniLibs/arm64-v8a"
