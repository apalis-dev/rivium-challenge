#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
ndk="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ "$(uname -s)" == Darwin ]]; then toolchain="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin"; fi
if [[ $# == 0 ]]; then set -- arm64-v8a x86_64; fi
for abi in "$@"; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android; cc=aarch64-linux-android26-clang ;;
        x86_64) target=x86_64-linux-android; cc=x86_64-linux-android26-clang ;;
        *) echo "Supported ABIs: arm64-v8a x86_64" >&2; exit 1 ;;
    esac
    rustup target add "$target"
    key="${target//-/_}"
    upper="${key^^}"
    env "CC_${key}=$toolchain/$cc" "AR_${key}=$toolchain/llvm-ar" \
        "CARGO_TARGET_${upper}_LINKER=$toolchain/$cc" \
        "CARGO_TARGET_${upper}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384" \
        cargo build --locked --release --lib --no-default-features --features jni --target "$target"
    mkdir -p "android/app/src/main/jniLibs/$abi"
    cp "target/$target/release/libpichalite.so" "android/app/src/main/jniLibs/$abi/"
    "$toolchain/llvm-strip" --strip-unneeded "android/app/src/main/jniLibs/$abi/libpichalite.so"
done
if [[ "${PICHALITE_NATIVE_ONLY:-0}" == 1 ]]; then exit 0; fi

# Gradle 8.11 with Kotlin 2.1 runs only on JDK 17-21. GitHub's ubuntu-latest
# now defaults to JDK 25, so switch to a supported JDK when one is installed.
java_major() {
    "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1
}
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/java}"
java_bin="${java_bin:-$(command -v java || true)}"
major="$([[ -n "$java_bin" ]] && java_major "$java_bin" || true)"
if [[ ! "$major" =~ ^(17|18|19|20|21)$ ]]; then
    found=""
    mac_jdk() { [[ "$(uname -s)" == Darwin ]] && /usr/libexec/java_home -v "$1" 2>/dev/null || true; }
    for jdk in "${JAVA_HOME_17_X64:-}" "${JAVA_HOME_17_arm64:-}" "${JAVA_HOME_21_X64:-}" \
        "${JAVA_HOME_21_arm64:-}" "$(mac_jdk 17)" "$(mac_jdk 21)"; do
        if [[ -n "$jdk" && -x "$jdk/bin/java" ]]; then found="$jdk"; break; fi
    done
    if [[ -z "$found" ]]; then
        echo "Android build needs JDK 17-21 (found ${major:-none}). Set JAVA_HOME to one." >&2
        exit 1
    fi
    export JAVA_HOME="$found"
    echo "Using JDK at $JAVA_HOME (default JDK ${major:-none} is not supported by Gradle 8.11)"
fi

cd android
./gradlew --no-daemon assembleDebug lintDebug
