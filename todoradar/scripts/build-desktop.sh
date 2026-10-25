#!/usr/bin/env bash
# Build the engine as a host JNI library for the desktop client and stage it for packaging.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
cd "$HERE/rust"
CARGO_BUILD_JOBS=${CARGO_BUILD_JOBS:-2} cargo build -p radar-jni --release
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) LIB=radar_jni.dll; DIR=windows ;;
  Darwin) LIB=libradar_jni.dylib; DIR=macos ;;
  *) LIB=libradar_jni.so; DIR=linux ;;
esac
mkdir -p "$HERE/client/desktopApp/resources/$DIR"
cp "target/release/$LIB" "$HERE/client/desktopApp/resources/$DIR/"
echo "staged $LIB -> client/desktopApp/resources/$DIR"
