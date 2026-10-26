#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

usage() {
  echo "Usage: ./build.sh [arm64-v8a] [x86_64] | --check | --all"
  echo "  (default) builds Android debug APK and runs lint for ARM64 and x86_64"
  echo "  --check   Rust format, Clippy, tests, smoke test and desktop JNI check"
  echo "  --all     run checks and then build Android"
}

check() {
  cargo fmt --check
  cargo clippy --locked --all-targets --all-features -- -D warnings
  cargo test --locked --all-features
  cargo build --locked --all-features
  python3 -c 'import PIL' || { echo "Install Pillow: python3 -m pip install Pillow" >&2; exit 1; }
  python3 scripts/smoke.py
  mkdir -p target/jvm
  javac -d target/jvm android/app/src/main/java/ke/co/pichalite/{PichaRuntime,NativeJobs}.java scripts/JniSmoke.java
  TOKIO_WORKER_THREADS=1 java -Xcheck:jni -Djava.library.path=target/debug -cp target/jvm JniSmoke target/jni-smoke
}

case "${1:-}" in
  --help|-h) usage ;;
  --check) check ;;
  --all) check; shift; bash scripts/build-android.sh "$@" ;;
  *) bash scripts/build-android.sh "$@" ;;
esac
